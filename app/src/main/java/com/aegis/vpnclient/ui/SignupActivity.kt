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
import com.aegis.vpnclient.network.SignupRequest
import com.aegis.vpnclient.util.DeviceIdentity
import com.aegis.vpnclient.util.SecureStore
import com.google.gson.Gson
import kotlinx.coroutines.launch

class SignupActivity : AppCompatActivity() {

    private lateinit var store: SecureStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)
        store = SecureStore(this)

        val usernameInput = findViewById<EditText>(R.id.usernameInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val errorText = findViewById<TextView>(R.id.errorText)
        val spinner = findViewById<ProgressBar>(R.id.loadingSpinner)
        val signupButton = findViewById<Button>(R.id.signupSubmitButton)

        findViewById<Button>(R.id.goToLoginButton).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        signupButton.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (username.isEmpty() || password.length < 6) {
                errorText.text = "Username required, password must be 6+ characters"
                return@setOnClickListener
            }

            spinner.visibility = View.VISIBLE
            errorText.text = ""
            signupButton.isEnabled = false

            lifecycleScope.launch {
                try {
                    val response = ApiClient.instance.signup(
                        SignupRequest(
                            username = username,
                            password = password,
                            deviceId = DeviceIdentity.getDeviceId(this@SignupActivity),
                            deviceName = DeviceIdentity.getDeviceName()
                        )
                    )
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        store.sessionToken = body.sessionToken
                        store.username = body.username
                        store.lastConfigJson = Gson().toJson(body)
                        startActivity(Intent(this@SignupActivity, DashboardActivity::class.java))
                        finish()
                    } else {
                        // Surfaces TRIAL_USED and other structured errors from the backend directly.
                        errorText.text = response.errorBody()?.string() ?: "Sign up failed"
                    }
                } catch (e: Exception) {
                    errorText.text = "Network error: ${e.message}"
                } finally {
                    spinner.visibility = View.GONE
                    signupButton.isEnabled = true
                }
            }
        }
    }
}
