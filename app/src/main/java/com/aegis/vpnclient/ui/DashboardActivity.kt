package com.aegis.vpnclient.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aegis.vpnclient.R
import com.aegis.vpnclient.network.AccessResponse
import com.aegis.vpnclient.util.SecureStore
import com.aegis.vpnclient.vpn.AegisVpnService
import com.aegis.vpnclient.vpn.XrayConfigBuilder
import com.google.gson.Gson
import java.text.DateFormat
import java.util.Date

class DashboardActivity : AppCompatActivity() {

    private lateinit var store: SecureStore
    private lateinit var access: AccessResponse
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpn() else statusText.text = "VPN permission denied"
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            runOnUiThread {
                statusText.text = status
                connectButton.text = if (AegisVpnService.isRunning) "Disconnect" else "Connect"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        store = SecureStore(this)

        val configJson = store.lastConfigJson
        if (configJson == null || store.sessionToken == null) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        access = Gson().fromJson(configJson, AccessResponse::class.java)

        findViewById<TextView>(R.id.usernameText).text = access.username + if (access.isTrial) " (Trial)" else ""
        findViewById<TextView>(R.id.planText).text =
            "${access.`package`.name} · ${access.trafficUsedGB}GB / ${if (access.`package`.trafficLimitGB > 0) "${access.`package`.trafficLimitGB}GB" else "unlimited"}"
        findViewById<TextView>(R.id.expiryText).text = "Expires: ${DateFormat.getDateTimeInstance().format(Date(access.expiresAt))}"

        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        connectButton.text = if (AegisVpnService.isRunning) "Disconnect" else "Connect"

        connectButton.setOnClickListener {
            if (AegisVpnService.isRunning) disconnectVpn() else requestVpnPermissionAndConnect()
        }

        findViewById<Button>(R.id.logoutButton).setOnClickListener {
            disconnectVpn()
            store.clear()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.aegis.vpnclient.STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    private fun requestVpnPermissionAndConnect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent) else startVpn()
    }

    private fun startVpn() {
        // Picks the first config entry by default. If a package includes
        // multiple protocols/servers, surface a picker instead of always
        // taking configs[0].
        val chosen = access.configs.firstOrNull() ?: run {
            statusText.text = "No server configuration available for your plan"
            return
        }
        val xrayJson = XrayConfigBuilder.build(chosen, access.`package`, access.apiHost)

        val intent = Intent(this, AegisVpnService::class.java).apply {
            action = AegisVpnService.ACTION_CONNECT
            putExtra(AegisVpnService.EXTRA_CONFIG_JSON, xrayJson)
            putExtra(AegisVpnService.EXTRA_SESSION_TOKEN, store.sessionToken)
        }
        startForegroundService(intent)
    }

    private fun disconnectVpn() {
        startService(Intent(this, AegisVpnService::class.java).apply { action = AegisVpnService.ACTION_DISCONNECT })
    }
}
