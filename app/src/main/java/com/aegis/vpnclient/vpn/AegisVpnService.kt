package com.aegis.vpnclient.vpn

import android.app.Service
import android.content.Intent
import android.content.Context
import android.os.IBinder

class AegisVpnService : Service() {
    
    companion object {
        const val ACTION_CONNECT = "com.aegis.vpnclient.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.aegis.vpnclient.ACTION_DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_SESSION_TOKEN = "session_token"
        
        var isRunning = false
    }
    
    private var userInitiatedDisconnect = false
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun broadcastStatus(message: String) {
        sendBroadcast(Intent("com.aegis.vpnclient.STATUS").apply {
            putExtra("status", message)
        })
    }
    
    override fun startup(): Long = 0L

    override fun shutdown(): Long {
        if (!userInitiatedDisconnect) handleUnexpectedCoreDrop()
        return 0L
    }

    override fun onEmitStatus(code: Int, message: String?): Long {
        broadcastStatus(message ?: "status $code")
        return 0L
    }
    
    private fun handleUnexpectedCoreDrop() {
        // Handle unexpected disconnection
    }
}
