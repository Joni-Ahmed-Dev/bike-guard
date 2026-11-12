package com.bikeguard.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class AuthUser(val id: String, val email: String? = null)

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val user: AuthUser? = null,
)

@Serializable
data class DeviceDto(
    val id: String? = null,
    val name: String,
    @SerialName("last_seen") val lastSeen: String? = null,
)

@Serializable
data class LocationDto(
    @SerialName("device_id") val deviceId: String,
    val lat: Double,
    val lng: Double,
    val speed: Float? = null,
    val bearing: Float? = null,
    val accuracy: Float? = null,
    val battery: Int? = null,
    @SerialName("is_charging") val isCharging: Boolean? = null,
    @SerialName("recorded_at") val recordedAt: String,
) {
    val recordedAtMs: Long
        get() = runCatching { OffsetDateTime.parse(recordedAt).toInstant().toEpochMilli() }.getOrDefault(0L)
}

sealed interface AuthResult {
    data object Success : AuthResult
    data object NeedsConfirmation : AuthResult
    data class Error(val message: String) : AuthResult
}

enum class UploadResult { OK, RETRY, REJECTED }
