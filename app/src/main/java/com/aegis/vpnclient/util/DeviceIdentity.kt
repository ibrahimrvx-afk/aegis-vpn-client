package com.aegis.vpnclient.util

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Produces a stable per-install device identifier for the backend's
 * device-limit / hardware-ID binding logic.
 *
 * Deliberately does NOT use IMEI, serial number, or MAC address:
 *   - Android has blocked app access to those for years (needs system/carrier
 *     privileges), so trying to read them will fail or return garbage on
 *     modern devices.
 *   - They're also unnecessarily invasive for what's really just "tell one
 *     install of this app apart from another."
 *
 * Instead this combines ANDROID_ID (resets on factory reset — which is
 * actually desirable, a reset device should be able to re-bind) with a
 * per-install random salt stored in SharedPreferences, then hashes the
 * result. The backend only ever sees the SHA-256 hex digest, never the
 * inputs.
 */
object DeviceIdentity {
    private const val PREFS = "aegis_device_prefs"
    private const val KEY_SALT = "install_salt"

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var salt = prefs.getString(KEY_SALT, null)
        if (salt == null) {
            salt = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_SALT, salt).apply()
        }

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val raw = "$androidId:$salt:${android.os.Build.MODEL}"

        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun getDeviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
}
