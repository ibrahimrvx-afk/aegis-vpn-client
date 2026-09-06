package com.aegis.vpnclient.network

data class LoginRequest(val username: String, val password: String, val deviceId: String, val deviceName: String)
data class SignupRequest(val username: String, val password: String, val deviceId: String, val deviceName: String)

data class ConfigEntry(val protocol: String, val remark: String, val link: String)

data class PackageInfo(
    val name: String,
    val deviceLimit: Int,
    val trafficLimitGB: Double,
    val allowedDomains: List<String> = emptyList()
)

data class DeviceInfo(val id: String, val boundAt: String)

data class AccessResponse(
    val username: String,
    val isTrial: Boolean,
    val `package`: PackageInfo,
    val apiHost: String,
    val expiresAt: Long,
    val trafficUsedGB: Double,
    val device: DeviceInfo,
    val configs: List<ConfigEntry>,
    val sessionToken: String
)

data class VerifyResponse(
    val ok: Boolean,
    val status: String? = null,
    val expiresAt: Long? = null,
    val trafficUsedGB: Double? = null,
    val trafficLimitGB: Double? = null,
    val secondsRemaining: Long? = null,
    val error: String? = null,
    val code: String? = null
)

data class ApiErrorBody(val error: String?, val code: String?)
