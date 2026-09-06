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
import com.aegis.vpnclient.ui.DashboardActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class AegisVpnService : VpnService(), CoreCallbackHandler {

    companion object {
        const val ACTION_CONNECT = "com.aegis.vpnclient.CONNECT"
        const val ACTION_DISCONNECT = "com.aegis.vpnclient.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_SESSION_TOKEN = "session_token"
        private const val NOTIF_CHANNEL = "aegis_vpn_status"
        private const val NOTIF_ID = 1

        var isRunning: Boolean = false
            private set
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
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

        val builder = Builder()
            .setSession("Aegis VPN")
            .addAddress("10.10.0.2", 32)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_ : Exception) { /* package always resolvable for our own app; defensive only */ }

        tunInterface = builder.establish()
        if (tunInterface == null) {
            broadcastStatus("Failed to establish VPN interface")
            stopSelf()
            return
        }

        coreController = Libv2ray.newCoreController(this)
        try {
            coreController?.startLoop(configJson, tunInterface!!.fd)
        } catch (e: Exception) {
            broadcastStatus("Xray failed to start: ${e.message}")
            teardown(reason = "Startup failed")
            return
        }

        isRunning = true
        broadcastStatus("Connected")
        updateNotification("Connected")

        serviceScope = CoroutineScope(Dispatchers.IO + Job())
        verificationManager = VerificationManager(
            sessionToken = sessionToken,
            scope = serviceScope!!,
            onVerifyFailed = { reason -> teardown(reason = reason) }
        )
        verificationManager?.runInitialVerify()
        verificationManager?.startHeartbeat()
    }

    private fun teardown(reason: String) {
        verificationManager?.stop()
        serviceScope?.cancel()
        try { coreController?.stopLoop() } catch (_: Exception) {}
        coreController = null
        try { tunInterface?.close() } catch (_: Exception) {}
        tunInterface = null
        isRunning = false
        broadcastStatus(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleUnexpectedCoreDrop() {
        if (userInitiatedDisconnect) return
        updateNotification("Connection lost — blocking traffic until reconnected")
        broadcastStatus("Connection lost — kill switch active")
    }

    override fun startup(): Int = 0

    override fun shutdown(): Int {
        if (!userInitiatedDisconnect) handleUnexpectedCoreDrop()
        return 0
    }

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
