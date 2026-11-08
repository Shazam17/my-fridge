# Правила ProGuard/R8 для проекта.

# Локальная LLM (docs/LOCAL_LLM.md): JNI находит класс и метод по имени, их нельзя переименовывать.
-keep class com.example.myapplication.ai.LocalLlm { *; }
-keep interface com.example.myapplication.ai.TokenSink { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
