package com.greenfleet.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore holds the non-exportable AES key; preferences hold only authenticated ciphertext. */
class SecureSettings(context: Context) {
    private val preferences = context.getSharedPreferences("greenfleet_settings", Context.MODE_PRIVATE)
    private val alias = "greenfleet.settings.aes.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Called on IO; report persistence errors before claiming success.
    @Synchronized fun setToken(value: String) {
        if (value.isBlank()) { check(preferences.edit().remove("access_token").commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(alias.toByteArray())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("access_token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    @Synchronized fun token(): String {
        val stored = preferences.getString("access_token", null) ?: return ""
        return try {
            val encrypted = Base64.decode(stored, Base64.NO_WRAP)
            require(encrypted.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
                updateAAD(alias.toByteArray())
            }
            cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // Device key invalidation requires reprovisioning; never silently store a plaintext fallback.
            preferences.edit().remove("access_token").apply(); ""
        }
    }
    var backendUrl: String
        get() = preferences.getString("backend_url", "") ?: ""
        set(value) { preferences.edit().putString("backend_url", value).apply() }
    var emissionFactor: Double
        get() = preferences.getString("emission_factor", "180")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 1.0..1000.0 } ?: 180.0
        set(value) { preferences.edit().putString("emission_factor", value.toString()).apply() }
    var privacyAccepted: Boolean
        get() = preferences.getBoolean("privacy_accepted_v1", false)
        set(value) { preferences.edit().putBoolean("privacy_accepted_v1", value).apply() }
    val hasToken get() = preferences.contains("access_token")
}
