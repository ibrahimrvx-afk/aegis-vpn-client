package com.aegis.vpnclient.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aegis.vpnclient.util.SecureStore

/**
 * Optional: auto-reconnect after device reboot if the user was connected
 * before. Left inert by default (just checks state) — wire up a call into
 * DashboardActivity's startVpn()-equivalent logic if you want this active.
 * Remember: reconnecting still goes through the normal login/session-token
 * flow, it does not bypass verification.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = SecureStore(context)
        if (store.sessionToken == null) return
        // TODO: if desired, start AegisVpnService here using the stored
        // config/session — omitted by default so auto-connect-on-boot is an
        // explicit opt-in you wire up, not a silent default.
    }
}
