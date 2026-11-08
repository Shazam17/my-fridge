// JNI-мост к llama.cpp для простой генерации текста (см. docs/LOCAL_LLM.md).
// Одна загруженная модель, генерация в одном потоке. Отмена — через флаг stop.

#include <android/log.h>
#include <atomic>
#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "localllm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

llama_model *g_model = nullptr;
std::atomic<bool> g_stop{false};

// Сериализуем загрузку и генерацию: контекст не потокобезопасен.
std::mutex g_mutex;

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_ai_LocalLlm_nativeLoad(JNIEnv *env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_backend_init();
        backend_ready = true;
    }
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0; // только CPU: стабильнее на большинстве телефонов
    g_model = llama_model_load_from_file(jstr(env, path).c_str(), params);
    if (!g_model) {
        LOGE("failed to load model");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_myapplication_ai_LocalLlm_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_example_myapplication_ai_LocalLlm_nativeStop(JNIEnv *, jobject) {
    g_stop.store(true);
}

/**
 * Генерация ответа. system/user — тексты сообщений; onToken(String) вызывается для каждого куска.
 * Возвращает число сгенерированных токенов или -1 при ошибке.
 */
JNIEXPORT jint JNICALL
Java_com_example_myapplication_ai_LocalLlm_nativeGenerate(
        JNIEnv *env, jobject thiz, jstring jsystem, jstring juser, jint maxTokens, jobject onToken) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return -1;
    g_stop.store(false);

    // Колбэк: com.example.myapplication.ai.TokenSink.onBytes(ByteArray). Байты, а не String:
    // символ может быть разбит между токенами, а NewStringUTF не понимает 4-байтовый UTF-8.
    // Ищем метод у интерфейса, а не у класса лямбды: имя класса лямбды не стабильно при минификации.
    jclass sink_cls = env->FindClass("com/example/myapplication/ai/TokenSink");
    if (!sink_cls) return -1;
    jmethodID cb = env->GetMethodID(sink_cls, "onBytes", "([B)V");
    if (!cb) return -1;

    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    // Промпт по шаблону модели (ChatML для Qwen).
    std::string system = jstr(env, jsystem);
    std::string user = jstr(env, juser);
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    std::vector<llama_chat_message> msgs = {{"system", system.c_str()}, {"user", user.c_str()}};
    std::vector<char> buf(system.size() + user.size() + 1024);
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    }
    if (n < 0) return -1;
    std::string prompt(buf.data(), n);

    // Токенизация промпта.
    int32_t n_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) return -1;
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), true, true) < 0) {
        return -1;
    }

    // Контекст: промпт + ответ. Ограничиваем разумным размером для телефона.
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) (n_prompt + maxTokens + 16);
    cparams.n_batch = cparams.n_ctx;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;
    llama_context *ctx = llama_init_from_model(g_model, cparams);
    if (!ctx) {
        LOGE("failed to create context");
        return -1;
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.6f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int generated = 0;
    if (llama_decode(ctx, llama_batch_get_one(tokens.data(), (int32_t) tokens.size())) == 0) {
        while (generated < maxTokens && !g_stop.load()) {
            llama_token id = llama_sampler_sample(smpl, ctx, -1);
            if (llama_vocab_is_eog(vocab, id)) break;

            char piece[256];
            int len = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, false);
            if (len > 0) {
                jbyteArray arr = env->NewByteArray(len);
                env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(piece));
                env->CallVoidMethod(onToken, cb, arr);
                env->DeleteLocalRef(arr);
            }
            generated++;

            llama_token next = id;
            if (llama_decode(ctx, llama_batch_get_one(&next, 1)) != 0) break;
        }
    } else {
        generated = -1;
    }

    llama_sampler_free(smpl);
    llama_free(ctx);
    return generated;
}

} // extern "C"
