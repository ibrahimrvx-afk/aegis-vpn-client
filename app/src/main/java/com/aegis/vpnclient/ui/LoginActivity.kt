package com.aegis.vpnclient.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aegis.vpnclient.R
import com.aegis.vpnclient.network.ApiClient
import com.aegis.vpnclient.network.LoginRequest
import com.aegis.vpnclient.util.DeviceIdentity
import com.aegis.vpnclient.util.SecureStore
import com.google.gson.Gson
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var store: SecureStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        store = SecureStore(this)

        val usernameInput = findViewById<EditText>(R.id.usernameInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val errorText = findViewById<TextView>(R.id.errorText)
        val spinner = findViewById<ProgressBar>(R.id.loadingSpinner)
        val loginButton = findViewById<Button>(R.id.loginButton)

        findViewById<Button>(R.id.signupButton).setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        loginButton.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (username.isEmpty() || password.isEmpty()) {
                errorText.text = "Enter both username and password"
                return@setOnClickListener
            }

            spinner.visibility = View.VISIBLE
            errorText.text = ""
            loginButton.isEnabled = false

            lifecycleScope.launch {
                try {
                    val response = ApiClient.instance.login(
                        LoginRequest(
                            username = username,
                            password = password,
                            deviceId = DeviceIdentity.getDeviceId(this@LoginActivity),
                            deviceName = DeviceIdentity.getDeviceName()
                        )
                    )
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        store.sessionToken = body.sessionToken
                        store.username = body.username
                        store.lastConfigJson = Gson().toJson(body)
                        startActivity(Intent(this@LoginActivity, DashboardActivity::class.java))
                        finish()
                    } else {
                        errorText.text = response.errorBody()?.string() ?: "Login failed"
                    }
                } catch (e: Exception) {
                    errorText.text = "Network error: ${e.message}"
                } finally {
                    spinner.visibility = View.GONE
                    loginButton.isEnabled = true
                }
            }
        }
    }
}
