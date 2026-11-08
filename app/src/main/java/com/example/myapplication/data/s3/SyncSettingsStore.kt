package com.example.myapplication.data.s3

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapplication.domain.SyncSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.syncDataStore by preferencesDataStore(name = "sync_settings")

/**
 * Настройки подключения (SYNC_SPEC.md, раздел 2, блок «Хранение»).
 * Обычные поля — в DataStore. Ключи доступа — AES-GCM, ключ шифрования в Android Keystore.
 */
class SyncSettingsStore(private val context: Context) {

    private val dataStore get() = context.syncDataStore

    /** Настройки или null, если подключение не настроено. */
    val settings: Flow<SyncSettings?> = dataStore.data.map { prefs ->
        val accessEnc = prefs[KEY_ACCESS]
        val secretEnc = prefs[KEY_SECRET]
        if (accessEnc == null || secretEnc == null) {
            null
        } else {
            SyncSettings(
                endpoint = prefs[KEY_ENDPOINT].orEmpty(),
                bucket = prefs[KEY_BUCKET].orEmpty(),
                region = prefs[KEY_REGION] ?: "us-east-1",
                pathStyle = prefs[KEY_PATH_STYLE] ?: true,
                accessKey = decrypt(accessEnc),
                secretKey = decrypt(secretEnc),
            )
        }
    }

    suspend fun save(settings: SyncSettings) {
        dataStore.edit { prefs ->
            prefs[KEY_ENDPOINT] = settings.endpoint
            prefs[KEY_BUCKET] = settings.bucket
            prefs[KEY_REGION] = settings.region
            prefs[KEY_PATH_STYLE] = settings.pathStyle
            prefs[KEY_ACCESS] = encrypt(settings.accessKey)
            prefs[KEY_SECRET] = encrypt(settings.secretKey)
        }
    }

    /** Удаляет ключи и настройки. Локальная БД не затрагивается (SYNC_SPEC.md, раздел 2). */
    suspend fun clear() {
        dataStore.edit { it.clear() }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    /** Текущие настройки одним снимком, для разовых операций. */
    suspend fun snapshot(): SyncSettings? = settings.first()

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val data = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sync_settings_aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128

        val KEY_ENDPOINT = stringPreferencesKey("endpoint")
        val KEY_BUCKET = stringPreferencesKey("bucket")
        val KEY_REGION = stringPreferencesKey("region")
        val KEY_PATH_STYLE = booleanPreferencesKey("path_style")
        val KEY_ACCESS = stringPreferencesKey("access_key_enc")
        val KEY_SECRET = stringPreferencesKey("secret_key_enc")
    }
}
