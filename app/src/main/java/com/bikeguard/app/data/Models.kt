package com.bikeguard.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    val armed: Boolean? = null,
    @SerialName("odometer_km") val odometerKm: Double? = null,
    @SerialName("next_service_km") val nextServiceKm: Double? = null,
    @SerialName("speed_limit_kmh") val speedLimitKmh: Int? = null,
)

/** What the tracker phone mirrors to its device row. */
@Serializable
data class DeviceStatePatch(
    val armed: Boolean,
    @SerialName("tracking_expected") val trackingExpected: Boolean,
    @SerialName("odometer_km") val odometerKm: Double,
    @SerialName("next_service_km") val nextServiceKm: Double? = null,
    @SerialName("speed_limit_kmh") val speedLimitKmh: Int,
)

@Serializable
data class LocationDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("owner_id") val ownerId: String? = null,
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

fun isoToMs(s: String): Long =
    runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrDefault(0L)

@Serializable
data class TripDto(
    @SerialName("trip_no") val tripNo: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    val points: Int,
    @SerialName("distance_m") val distanceM: Double,
    @SerialName("max_speed_kmh") val maxSpeedKmh: Double,
    @SerialName("avg_speed_kmh") val avgSpeedKmh: Double,
) {
    val startedMs: Long get() = isoToMs(startedAt)
    val endedMs: Long get() = isoToMs(endedAt)
}

@Serializable
data class PointDto(
    val lat: Double,
    val lng: Double,
    val speed: Float? = null,
    @SerialName("recorded_at") val recordedAt: String,
) {
    val ms: Long get() = isoToMs(recordedAt)
}

@Serializable
data class EventDto(
    val id: Long? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("owner_id") val ownerId: String? = null,
    val type: String,
    val severity: String = "info",
    val lat: Double? = null,
    val lng: Double? = null,
    val data: JsonElement? = null,
    @SerialName("created_at") val createdAt: String,
) {
    val createdMs: Long get() = isoToMs(createdAt)
}

@Serializable
data class CommandDto(
    val id: Long? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val type: String? = null,
    val arg: String? = null,
    val status: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class GeofenceDto(
    val id: String? = null,
    val name: String,
    val lat: Double,
    val lng: Double,
    @SerialName("radius_m") val radiusM: Int,
    val active: Boolean = true,
    @SerialName("device_id") val deviceId: String? = null,
)

@Serializable
data class ShareLinkDto(
    val token: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class PushTokenDto(val token: String, @SerialName("device_id") val deviceId: String)

/** One row per tracker device: the settings the Remote phone edits and the tracker applies. */
@Serializable
data class DeviceSettingsDto(
    @SerialName("device_id") val deviceId: String,
    val mode: String = "manual",
    @SerialName("interval_sec") val intervalSec: Int = 5,
    @SerialName("speed_limit_kmh") val speedLimitKmh: Int = 0,
    @SerialName("guard_armed") val guardArmed: Boolean = false,
    @SerialName("auto_arm") val autoArm: Boolean = false,
    @SerialName("siren_on_alert") val sirenOnAlert: Boolean = false,
    @SerialName("sos_sms") val sosSms: Boolean = false,
    @SerialName("crash_enabled") val crashEnabled: Boolean = true,
    @SerialName("sos_number") val sosNumber: String = "",
    @SerialName("service_km") val serviceKm: Int = 0,
    @SerialName("guard_schedule_start") val guardScheduleStart: Int? = null,
    @SerialName("guard_schedule_end") val guardScheduleEnd: Int? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("applied_at") val appliedAt: String? = null,
)

@Serializable
data class FamilyInviteDto(
    val id: String? = null,
    val code: String,
    val role: String = "viewer",
    @SerialName("used_by") val usedBy: String? = null,
)

@Serializable
data class ParkingSpotDto(
    val id: String? = null,
    @SerialName("device_id") val deviceId: String,
    val lat: Double,
    val lng: Double,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val createdMs: Long get() = isoToMs(createdAt.orEmpty())
}
