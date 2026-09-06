package com.aegis.vpnclient.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wraps EncryptedSharedPreferences (AES-256-GCM under the hood, key held in
 * the Android Keystore) so the session token never sits in plaintext prefs.
 * We store the session token — never the raw password.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "aegis_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var sessionToken: String?
        get() = prefs.getString("session_token", null)
        set(value) = prefs.edit().putString("session_token", value).apply()

    var username: String?
        get() = prefs.getString("username", null)
        set(value) = prefs.edit().putString("username", value).apply()

    var lastConfigJson: String?
        get() = prefs.getString("last_config_json", null)
        set(value) = prefs.edit().putString("last_config_json", value).apply()

    fun clear() = prefs.edit().clear().apply()
}
