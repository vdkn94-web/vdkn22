package com.splayer.splay

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecurePreferences {
    private const val PREFS = "splay_settings"
    private const val KEY_ALIAS = "splay_settings_key"

    private fun prefs() = AppContextHolder.context!!.getSharedPreferences(PREFS, 0)

    fun get(key: String): String = prefs().getString(key, "") ?: ""
    fun getFavorites(): Set<String> = prefs().getStringSet("favorite_paths", emptySet())?.toSet() ?: emptySet()
    fun putFavorites(paths: Set<String>) { prefs().edit().putStringSet("favorite_paths", paths.toSet()).apply() }
    fun put(key: String, value: String) { prefs().edit().putString(key, value.trim()).apply() }

    fun putSecret(key: String, value: String) {
        if (value.isEmpty()) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs().edit().putString(key, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun getSecret(key: String): String {
        val packed = prefs().getString(key, null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val payload = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
