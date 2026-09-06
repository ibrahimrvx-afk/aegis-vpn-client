package com.aegis.vpnclient.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.aegis.vpnclient.network.ConfigEntry
import com.aegis.vpnclient.network.PackageInfo
import com.aegis.vpnclient.ui.DashboardActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet

/**
 * Two things this service enforces on purpose, matching a deliberate product
 * decision (not a default we picked for you):
 *
 *  1. VERIFY FAILURE -> IMMEDIATE DISCONNECT. Any failed /verify call —
 *     whether the server explicitly rejected the session (suspended,
 *     expired, traffic cap, unrecognized device) or the request simply
 *     couldn't be completed — tears the tunnel down right away. No retry
 *     grace period. Trade-off you're accepting: a single flaky network
 *     blip during a heartbeat can disconnect a perfectly legitimate
 *     session. If that turns out to be too aggressive in practice, the
 *     fix is narrowing this in VerificationManager to only hard-disconnect
 *     on an explicit rejection code, and retry a couple of times on plain
 *     network exceptions — flagging it here so it's a known, revisitable
 *     choice, not a silent gap.
 *
 *  2. UNEXPECTED CORE DROP -> STRICT KILL SWITCH. If Xray-core itself dies
 *     unexpectedly (crash, OOM-kill, native error) while the user did NOT
 *     ask to disconnect, this service does NOT close the TUN interface.
 *     With nothing reading the tun fd, the OS simply can't deliver packets
 *     anywhere — real traffic is blocked outright rather than silently
 *     falling back to the raw network. The interface is only closed on an
 *     explicit user disconnect or an authorized verify-driven disconnect.
 */
class AegisVpnService : VpnService(), V2RayVPNServiceSupportsSet {

    companion object {
        const val ACTION_CONNECT = "com.aegis.vpnclient.CONNECT"
        const val ACTION_DISCONNECT = "com.aegis.vpnclient.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_SESSION_TOKEN = "session_token"
        const val EXTRA_ALLOWED_DOMAINS = "allowed_domains"
        private const val NOTIF_CHANNEL = "aegis_vpn_status"
        private const val NOTIF_ID = 1

        var isRunning: Boolean = false
            private set
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var v2rayPoint: V2RayPoint? = null
    private var serviceScope: CoroutineScope? = null
    private var verificationManager: VerificationManager? = null
    private var userInitiatedDisconnect = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                userInitiatedDisconnect = true
                teardown(reason = "Disconnected")
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: return START_NOT_STICKY
                val sessionToken = intent.getStringExtra(EXTRA_SESSION_TOKEN) ?: return START_NOT_STICKY
                startTunnel(configJson, sessionToken)
            }
        }
        return START_STICKY
    }

    private fun startTunnel(configJson: String, sessionToken: String) {
        userInitiatedDisconnect = false
        startForeground(NOTIF_ID, buildNotification("Connecting…"))

        // 1) Bring up the TUN interface.
        val builder = Builder()
            .setSession("Aegis VPN")
            .addAddress("10.10.0.2", 32)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)

        tunInterface = builder.establish()
        if (tunInterface == null) {
            broadcastStatus("Failed to establish VPN interface")
            stopSelf()
            return
        }

        // 2) Start Xray-core against the generated config.
        v2rayPoint = Libv2ray.newV2RayPoint(this, false)
        v2rayPoint?.configureFileContent = configJson
        try {
            v2rayPoint?.runLoop(false)
        } catch (e: Exception) {
            broadcastStatus("Xray failed to start: ${e.message}")
            teardown(reason = "Startup failed")
            return
        }

        // 3) Bridge the TUN fd into Xray's local SOCKS inbound.
        //    AndroidLibXrayLite only runs the proxy core — it does not itself
        //    translate raw IP packets from a tun fd into SOCKS connections.
        //    That's the job of a tun2socks bridge (v2rayNG bundles one as a
        //    native .so — see https://github.com/xjasonlyu/tun2socks or
        //    hev-socks5-tunnel). Wire your chosen tun2socks binary here,
        //    pointing it at tunInterface!!.fd and 127.0.0.1:$LOCAL_SOCKS_PORT.
        //    Left as an explicit TODO rather than guessed at, since the
        //    correct call depends on exactly which tun2socks build you use.
        // TODO: Tun2socksBridge.start(tunInterface!!.fileDescriptor, LOCAL_SOCKS_PORT)

        isRunning = true
        broadcastStatus("Connected")
        updateNotification("Connected")

        // 4) Two-phase verification, per your call.
        serviceScope = CoroutineScope(Dispatchers.IO + Job())
        verificationManager = VerificationManager(
            sessionToken = sessionToken,
            scope = serviceScope!!,
            onVerifyFailed = { reason ->
                // Any failure here — explicit rejection or transient error —
                // is an AUTHORIZED disconnect: tear the tunnel down and
                // restore normal connectivity, per your chosen policy.
                teardown(reason = reason)
            }
        )
        verificationManager?.runInitialVerify()   // phase 1: right after tunnel comes up
        verificationManager?.startHeartbeat()      // phase 2: periodic re-verify while connected
    }

    /** Explicit, authorized teardown — closes the interface and restores normal networking. */
    private fun teardown(reason: String) {
        verificationManager?.stop()
        serviceScope?.cancel()
        try { v2rayPoint?.stopLoop() } catch (_: Exception) {}
        v2rayPoint = null
        try { tunInterface?.close() } catch (_: Exception) {}
        tunInterface = null
        isRunning = false
        broadcastStatus(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Called only when Xray-core itself reports it has stopped unexpectedly
     * (see onEmitStatus below) while the user did NOT request a disconnect.
     * Deliberately does NOT close tunInterface — see the class-level kill
     * switch note.
     */
    private fun handleUnexpectedCoreDrop() {
        if (userInitiatedDisconnect) return
        updateNotification("Connection lost — blocking traffic until reconnected")
        broadcastStatus("Connection lost — kill switch active")
        // A real implementation should attempt a bounded number of automatic
        // reconnect attempts here (rebuild v2rayPoint, runLoop again) while
        // leaving tunInterface untouched so no packets leak in the meantime.
    }

    // ── V2RayVPNServiceSupportsSet (Xray-core -> app callbacks) ──────────
    override fun setup(conf: String?): Int {
        // Some AndroidLibXrayLite builds call this to request the TUN fd be
        // established (rather than the app doing it upfront, as this class
        // does in startTunnel()). Kept as a no-op returning success; if your
        // specific AAR build expects Setup() to actually create/return the
        // interface, move the `builder.establish()` call from startTunnel()
        // into here instead — check the AAR's exact contract before
        // shipping, since this varies between AndroidLibXrayLite forks.
        return 0
    }
    override fun shutdown(): Int {
        if (!userInitiatedDisconnect) handleUnexpectedCoreDrop()
        return 0
    }
    override fun prepare(): Int = 0
    override fun protect(socketFd: Int): Boolean = super.protect(socketFd)
    override fun onEmitStatus(code: Int, message: String?): Int {
        broadcastStatus(message ?: "status $code")
        return 0
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent("com.aegis.vpnclient.STATUS").putExtra("status", status))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Aegis VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        teardown(reason = "Service destroyed")
        super.onDestroy()
    }
}
