package com.aegis.vpnclient.vpn

import com.aegis.vpnclient.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase 1: called once, immediately after the TUN interface + Xray-core come
 *          up — this is the first point a domain-restricted plan can even
 *          reach the backend, since general traffic may be blocked until
 *          the tunnel exists.
 * Phase 2: a recurring heartbeat while connected, so a suspension/expiry/
 *          traffic-cap hit or revoked device applied on the admin side ends
 *          the session promptly instead of the app trusting a session that
 *          was valid only at login time.
 *
 * Per product decision: ANY failure from either phase — explicit rejection
 * or a network/timeout exception — calls onVerifyFailed and the caller
 * (AegisVpnService) tears the tunnel down immediately. No retries, no grace
 * period.
 */
class VerificationManager(
    private val sessionToken: String,
    private val scope: CoroutineScope,
    private val heartbeatIntervalMs: Long = 3 * 60 * 1000L, // every 3 minutes
    private val onVerifyFailed: (reason: String) -> Unit
) {
    private var heartbeatJob: Job? = null

    fun runInitialVerify() {
        scope.launch {
            val ok = doVerify()
            if (!ok) return@launch // onVerifyFailed already invoked inside doVerify
        }
    }

    fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                if (!isActive) break
                val ok = doVerify()
                if (!ok) break // teardown already triggered; stop looping
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
    }

    /** Returns true if the session is still valid; false after triggering onVerifyFailed. */
    private suspend fun doVerify(): Boolean {
        return try {
            val response = ApiClient.instance.verify("Bearer $sessionToken")
            val body = response.body()
            if (response.isSuccessful && body?.ok == true) {
                true
            } else {
                val reason = body?.error ?: "Session verification failed (${response.code()})"
                onVerifyFailed(reason)
                false
            }
        } catch (e: Exception) {
            // Network/timeout/etc — per product decision, treated the same as
            // an explicit rejection: disconnect immediately rather than retry.
            onVerifyFailed("Could not verify session: ${e.message}")
            false
        }
    }
}
