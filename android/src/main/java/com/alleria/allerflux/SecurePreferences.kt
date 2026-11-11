package com.alleria.allerflux

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Stores companion connection details encrypted with an AES-GCM key held by Android Keystore.
 * Legacy plaintext preferences are migrated once and removed after a successful encrypted save.
 */
class SecurePreferences(context: Context) {
    private val appContext = context.applicationContext
    private val secure = appContext.getSharedPreferences("allerflux_secure", Context.MODE_PRIVATE)
    private val legacy = appContext.getSharedPreferences("allerflux", Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val combined = Base64.decode(value, Base64.NO_WRAP)
        require(combined.size > GCM_IV_BYTES) { "Encrypted preference is invalid" }
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun load(): Pair<String, String> {
        val storedUrl = secure.getString(KEY_SERVER_URL, null)
        val storedKey = secure.getString(KEY_API_KEY, null)
        if (storedUrl != null || storedKey != null) {
            return runCatching { decrypt(storedUrl) to decrypt(storedKey) }
                .getOrDefault("" to "")
        }

        val oldUrl = legacy.getString(KEY_SERVER_URL, "") ?: ""
        val oldKey = legacy.getString(KEY_API_KEY, "") ?: ""
        if (oldUrl.isNotBlank() || oldKey.isNotBlank()) {
            if (save(oldUrl, oldKey)) {
                legacy.edit().remove(KEY_SERVER_URL).remove(KEY_API_KEY).apply()
                return oldUrl to oldKey
            }
            return "" to ""
        }
        return "" to ""
    }

    fun save(serverUrl: String, apiKey: String): Boolean = runCatching {
        val editor = secure.edit()
            .putString(KEY_SERVER_URL, encrypt(serverUrl))
            .putString(KEY_API_KEY, encrypt(apiKey))
        check(editor.commit()) { "Unable to persist encrypted connection settings" }
    }.isSuccess

    companion object {
        private const val KEY_ALIAS = "allerflux_connection_aes_gcm_v1"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
