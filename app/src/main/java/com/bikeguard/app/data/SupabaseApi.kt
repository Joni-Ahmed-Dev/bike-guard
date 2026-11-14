package com.bikeguard.app.data

import com.bikeguard.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Thin Supabase client (GoTrue auth + PostgREST) over OkHttp. No heavy SDK, easy to audit. */
class SupabaseApi(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val refreshMutex = Mutex()
    private val base get() = BuildConfig.SUPABASE_URL.trimEnd('/')

    /** Flips to true when the refresh token is rejected, so the UI can send the user back to sign-in. */
    val sessionExpired = MutableStateFlow(false)

    val isConfigured get() = BuildConfig.SUPABASE_URL.startsWith("http") && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    /** One call's outcome: whether it worked, and the local failure reason when it did not. */
    data class ApiResult(val ok: Boolean, val error: String? = null)
    /** An upload's outcome: the queue decision, and the local failure reason when it must retry. */
    data class UploadOutcome(val result: UploadResult, val error: String? = null)

    private class Resp(val code: Int, val body: String, val error: String?) {
        val ok get() = code in 200..299
    }

    private fun builder(path: String, token: String?) = Request.Builder()
        .url(base + path)
        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        .header("Authorization", "Bearer " + (token ?: BuildConfig.SUPABASE_ANON_KEY))

    /** Local reason for an IOException, so the caller can report it without a shared mutable field. */
    private fun ioReason(e: IOException): String =
        if (e is java.net.SocketTimeoutException) "timed out" else "no internet"

    private suspend fun exec(r: Request): Resp = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            // Keep the server's own message: it says exactly why a 403
            // happened (which policy, bad JWT, missing table), and the
            // diagnostics screen and Worker report carry it to Muse.
            val err = if (resp.code in 200..299) null else "server ${resp.code}" +
                errorText(body).takeIf { it.isNotBlank() && it != "Request failed" }
                    ?.let { ": ${it.take(120).replace('\n', ' ').replace('\r', ' ')}" }.orEmpty()
            Resp(resp.code, body, err)
        }
    }

    private suspend fun authed(make: (String) -> Request): Resp {
        // Never send an empty Bearer token: PostgREST treats it as anonymous
        // and RLS rejects the row with 403 instead of asking for a refresh.
        var token = prefs.accessToken.orEmpty()
        if (token.isBlank()) {
            if (!refresh()) return Resp(401, "", "server 401")
            token = prefs.accessToken.orEmpty()
        }
        var resp = exec(make(token))
        if (resp.code == 401 && refresh()) resp = exec(make(prefs.accessToken.orEmpty()))
        return resp
    }

    // ---------- auth ----------

    suspend fun signIn(email: String, password: String) = auth("/auth/v1/token?grant_type=password", email, password)
    suspend fun signUp(email: String, password: String) = auth("/auth/v1/signup", email, password)

    private suspend fun auth(path: String, email: String, password: String): AuthResult = try {
        val payload = buildJsonObject { put("email", email); put("password", password) }.toString()
        val r = exec(builder(path, null).post(payload.toRequestBody(jsonType)).build())
        if (!r.ok) {
            AuthResult.Error(errorText(r.body))
        } else {
            val a = json.decodeFromString<AuthResponse>(r.body)
            if (a.accessToken.isNullOrEmpty()) AuthResult.NeedsConfirmation
            else { save(a); sessionExpired.value = false; AuthResult.Success }
        }
    } catch (e: IOException) {
        AuthResult.Error("No internet connection")
    } catch (e: SerializationException) {
        AuthResult.Error("Unexpected server response")
    }

    private fun save(a: AuthResponse) {
        prefs.accessToken = a.accessToken
        prefs.refreshToken = a.refreshToken
        a.user?.let { prefs.userId = it.id; prefs.email = it.email }
    }

    private fun errorText(body: String): String = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        (o["msg"] ?: o["error_description"] ?: o["message"])?.jsonPrimitive?.content
    }.getOrNull() ?: "Request failed"

    private suspend fun refresh(): Boolean = refreshMutex.withLock {
        val rt = prefs.refreshToken ?: return false
        try {
            val body = buildJsonObject { put("refresh_token", rt) }.toString().toRequestBody(jsonType)
            val r = exec(builder("/auth/v1/token?grant_type=refresh_token", null).post(body).build())
            when {
                r.ok -> { save(json.decodeFromString<AuthResponse>(r.body)); true }
                r.code == 400 || r.code == 401 -> { prefs.clearSession(); sessionExpired.value = true; false }
                else -> false
            }
        } catch (e: IOException) { false } catch (e: SerializationException) { false }
    }

    // ---------- devices ----------

    suspend fun registerDevice(id: String, name: String, ownerId: String? = null): ApiResult = try {
        val payload = json.encodeToString(
            DeviceDto.serializer(), DeviceDto(id = id, name = name, ownerId = ownerId)
        )
        val r = authed {
            builder("/rest/v1/devices?on_conflict=id", it)
                .header("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(payload.toRequestBody(jsonType)).build()
        }
        ApiResult(r.ok, r.error)
    } catch (e: IOException) { ApiResult(false, ioReason(e)) }

    /** The device id already registered under this phone's hardware key (migration 0008),
     *  or null when no row has this key yet. Also null when the server could not be read
     *  (e.g. the 0008 column is not applied yet): the caller then registers normally and
     *  the stamp below retries later, so tracking is never blocked on the lookup.
     *  Throws IOException when offline, so the caller retries before minting a new id. */
    suspend fun findDeviceByHardwareKey(hardwareKey: String): String? {
        val q = "/rest/v1/devices?hardware_key=eq.${URLEncoder.encode(hardwareKey, "UTF-8")}" +
            "&select=id&order=created_at.asc&limit=1"
        val r = authed { builder(q, it).get().build() }
        if (!r.ok) return null
        return runCatching { json.decodeFromString(ListSerializer(DeviceDto.serializer()), r.body) }.getOrNull()?.firstOrNull()?.id
    }

    /** Stamps this phone's hardware key on its server device row (migration 0008), so a
     *  later reinstall can find the row again. Idempotent: stamping the same key twice
     *  changes nothing. False when offline or when the server refuses it (e.g. the 0008
     *  migration is not applied yet, or another row already owns this key). */
    suspend fun stampHardwareKey(deviceId: String, hardwareKey: String): Boolean = try {
        val body = buildJsonObject { put("hardware_key", hardwareKey) }.toString()
        authed {
            builder("/rest/v1/devices?id=eq.$deviceId", it)
                .header("Prefer", "return=minimal").patch(body.toRequestBody(jsonType)).build()
        }.ok
    } catch (e: IOException) { false }

    /** Throws IOException when offline; returns null on an HTTP error. */
    suspend fun listDevices(): List<DeviceDto>? {
        val r = authed { builder("/rest/v1/devices?select=id,name,last_seen,armed,odometer_km,next_service_km,speed_limit_kmh&order=created_at.asc", it).get().build() }
        return if (r.ok) runCatching { json.decodeFromString(ListSerializer(DeviceDto.serializer()), r.body) }.getOrNull() else null
    }

    // ---------- locations ----------

    suspend fun upload(items: List<LocationDto>): UploadOutcome = try {
        val payload = json.encodeToString(ListSerializer(LocationDto.serializer()), items)
        val r = authed {
            builder("/rest/v1/locations?on_conflict=device_id,recorded_at", it)
                .header("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(payload.toRequestBody(jsonType)).build()
        }
        when {
            r.ok -> UploadOutcome(UploadResult.OK)
            r.code == 409 || r.code == 403 -> { prefs.deviceRegistered = false; UploadOutcome(UploadResult.RETRY, r.error) } // device row missing or forbidden: re-register
            r.code == 400 || r.code == 422 -> UploadOutcome(UploadResult.REJECTED, r.error) // malformed: never succeeds, drop it
            else -> UploadOutcome(UploadResult.RETRY, r.error)
        }
    } catch (e: IOException) { UploadOutcome(UploadResult.RETRY, ioReason(e)) }

    /** Newest first. Throws IOException when offline; returns null on an HTTP error. */
    suspend fun recent(deviceId: String, limit: Int): List<LocationDto>? {
        val q = "/rest/v1/locations?device_id=eq.$deviceId" +
            "&select=device_id,lat,lng,speed,bearing,accuracy,battery,is_charging,recorded_at" +
            "&order=recorded_at.desc&limit=$limit"
        val r = authed { builder(q, it).get().build() }
        return if (r.ok) runCatching { json.decodeFromString(ListSerializer(LocationDto.serializer()), r.body) }.getOrNull() else null
    }

    // ---------- trips (Phase 2) ----------

    /** Trips detected by the device_trips() SQL function. Throws IOException offline; null on HTTP error. */
    suspend fun trips(deviceId: String, sinceIso: String): List<TripDto>? {
        val body = buildJsonObject { put("p_device", deviceId); put("p_since", sinceIso) }.toString()
        val r = authed { builder("/rest/v1/rpc/device_trips", it).post(body.toRequestBody(jsonType)).build() }
        return if (r.ok) runCatching { json.decodeFromString(ListSerializer(TripDto.serializer()), r.body) }.getOrNull() else null
    }

    /** All points of one trip, oldest first, fetched page by page (server caps a page at ~1000 rows). */
    suspend fun tripPoints(deviceId: String, fromIso: String, toIso: String): List<PointDto>? {
        val out = ArrayList<PointDto>()
        var offset = 0
        repeat(12) {
            val q = "/rest/v1/locations?device_id=eq.$deviceId" +
                "&recorded_at=gte.${URLEncoder.encode(fromIso, "UTF-8")}" +
                "&recorded_at=lte.${URLEncoder.encode(toIso, "UTF-8")}" +
                "&select=lat,lng,speed,recorded_at&order=recorded_at.asc&limit=1000&offset=$offset"
            val r = authed { builder(q, it).get().build() }
            if (!r.ok) return null
            val page = runCatching { json.decodeFromString(ListSerializer(PointDto.serializer()), r.body) }.getOrNull() ?: return null
            out += page
            if (page.size < 1000) return out
            offset += 1000
        }
        return out
    }

    // ---------- device state, events, commands (Phase 3) ----------

    suspend fun patchDeviceState(deviceId: String, patch: DeviceStatePatch): ApiResult = try {
        val payload = json.encodeToString(DeviceStatePatch.serializer(), patch)
        val r = authed {
            builder("/rest/v1/devices?id=eq.$deviceId", it)
                .header("Prefer", "return=minimal").patch(payload.toRequestBody(jsonType)).build()
        }
        ApiResult(r.ok, r.error)
    } catch (e: IOException) { ApiResult(false, ioReason(e)) }

    suspend fun uploadEvents(items: List<EventDto>): UploadOutcome = try {
        val payload = json.encodeToString(ListSerializer(EventDto.serializer()), items)
        val r = authed {
            builder("/rest/v1/events?on_conflict=device_id,type,created_at", it)
                .header("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(payload.toRequestBody(jsonType)).build()
        }
        when {
            r.ok -> UploadOutcome(UploadResult.OK)
            r.code == 409 || r.code == 403 -> { prefs.deviceRegistered = false; UploadOutcome(UploadResult.RETRY, r.error) }
            r.code == 400 || r.code == 422 -> UploadOutcome(UploadResult.REJECTED, r.error)
            else -> UploadOutcome(UploadResult.RETRY, r.error)
        }
    } catch (e: IOException) { UploadOutcome(UploadResult.RETRY, ioReason(e)) }

    suspend fun listEvents(limit: Int = 100): List<EventDto>? {
        val r = authed { builder("/rest/v1/events?select=*&order=created_at.desc&limit=$limit", it).get().build() }
        return if (r.ok) runCatching { json.decodeFromString(ListSerializer(EventDto.serializer()), r.body) }.getOrNull() else null
    }

    /** @return the new command's id, or null when the server refused it. */
    suspend fun sendCommand(deviceId: String, type: String, arg: String?): Long? {
        val payload = json.encodeToString(CommandDto.serializer(), CommandDto(deviceId = deviceId, type = type, arg = arg))
        val r = authed {
            builder("/rest/v1/commands", it).header("Prefer", "return=representation")
                .post(payload.toRequestBody(jsonType)).build()
        }
        if (!r.ok) return null
        val body = r.body.trim()
        val list = runCatching { json.decodeFromString(ListSerializer(CommandDto.serializer()), body) }.getOrNull()
        if (list != null) return list.firstOrNull()?.id
        return runCatching { json.decodeFromString(CommandDto.serializer(), body.removePrefix("[").removeSuffix("]")) }.getOrNull()?.id
    }

    suspend fun commandStatus(id: Long): String? {
        val r = authed { builder("/rest/v1/commands?id=eq.$id&select=id,status", it).get().build() }
        if (!r.ok) return null
        return runCatching { json.decodeFromString(ListSerializer(CommandDto.serializer()), r.body) }.getOrNull()?.firstOrNull()?.status
    }

    /** The tracker's mirrored tracking_expected flag, or null when the server cannot be read. */
    suspend fun trackingExpected(deviceId: String): Boolean? {
        val r = authed { builder("/rest/v1/devices?id=eq.$deviceId&select=tracking_expected", it).get().build() }
        if (!r.ok) return null
        return runCatching {
            json.parseToJsonElement(r.body).jsonArray.firstOrNull()
                ?.jsonObject?.get("tracking_expected")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        }.getOrNull()
    }

    suspend fun pendingCommands(deviceId: String): List<CommandDto>? {
        val q = "/rest/v1/commands?device_id=eq.$deviceId&status=eq.pending&order=id.asc&select=id,type,arg,created_at"
        val r = authed { builder(q, it).get().build() }
        return if (r.ok) runCatching { json.decodeFromString(ListSerializer(CommandDto.serializer()), r.body) }.getOrNull() else null
    }

    suspend fun ackCommand(id: Long, status: String): Boolean {
        val body = buildJsonObject {
            put("status", status); put("done_at", java.time.Instant.now().toString())
        }.toString()
        return authed {
            builder("/rest/v1/commands?id=eq.$id", it).header("Prefer", "return=minimal")
                .patch(body.toRequestBody(jsonType)).build()
        }.ok
    }

    // ---------- geofences ----------

    suspend fun listGeofences(): List<GeofenceDto>? {
        val r = authed { builder("/rest/v1/geofences?select=*&order=created_at.asc", it).get().build() }
        return if (r.ok) runCatching { json.decodeFromString(ListSerializer(GeofenceDto.serializer()), r.body) }.getOrNull() else null
    }

    suspend fun addGeofence(g: GeofenceDto): Boolean {
        val payload = json.encodeToString(GeofenceDto.serializer(), g)
        return authed {
            builder("/rest/v1/geofences", it).header("Prefer", "return=minimal").post(payload.toRequestBody(jsonType)).build()
        }.ok
    }

    suspend fun deleteGeofence(id: String): Boolean =
        authed { builder("/rest/v1/geofences?id=eq.$id", it).delete().build() }.ok

    // ---------- push token + share links (Phase 3/4) ----------

    suspend fun registerPushToken(token: String, deviceId: String): Boolean = try {
        val payload = json.encodeToString(PushTokenDto.serializer(), PushTokenDto(token, deviceId))
        authed {
            builder("/rest/v1/push_tokens?on_conflict=token", it)
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(payload.toRequestBody(jsonType)).build()
        }.ok
    } catch (e: IOException) { false }

    suspend fun createShareLink(token: String, deviceId: String, expiresIso: String): Boolean {
        val payload = json.encodeToString(ShareLinkDto.serializer(), ShareLinkDto(token, deviceId, expiresIso))
        return authed {
            builder("/rest/v1/share_links", it).header("Prefer", "return=minimal").post(payload.toRequestBody(jsonType)).build()
        }.ok
    }

    suspend fun revokeShareLinks(deviceId: String): Boolean {
        val body = buildJsonObject { put("revoked", true) }.toString()
        return authed {
            builder("/rest/v1/share_links?device_id=eq.$deviceId&revoked=eq.false", it)
                .header("Prefer", "return=minimal").patch(body.toRequestBody(jsonType)).build()
        }.ok
    }

    // ---------- v1.1.0: server-synced settings, family invites, parking spots ----------

    /** The Remote phone's edits for one tracker device, or null when no row exists yet. */
    suspend fun getDeviceSettings(deviceId: String): DeviceSettingsDto? {
        val r = authed {
            builder("/rest/v1/device_settings?device_id=eq.$deviceId&select=*", it).get().build()
        }
        if (!r.ok) return null
        return runCatching { json.decodeFromString(ListSerializer(DeviceSettingsDto.serializer()), r.body) }.getOrNull()?.firstOrNull()
    }

    /**
     * Upsert one settings row. [touchUpdated] is true for a Remote edit (marks it as new work for
     * the tracker) and false when the tracker mirrors its own state back (keeps the old timestamp,
     * so the Remote phone does not see its own change as "waiting" again).
     */
    suspend fun saveDeviceSettings(dto: DeviceSettingsDto, touchUpdated: Boolean = true): Boolean = try {
        // Built by hand (not the serializer): clearing the schedule must send an explicit null,
        // and the serializer would drop nulls, so merge-duplicates would keep the old value.
        val payload = buildJsonObject {
            put("device_id", dto.deviceId)
            put("mode", dto.mode)
            put("interval_sec", dto.intervalSec)
            put("speed_limit_kmh", dto.speedLimitKmh)
            put("guard_armed", dto.guardArmed)
            put("auto_arm", dto.autoArm)
            put("siren_on_alert", dto.sirenOnAlert)
            put("sos_sms", dto.sosSms)
            put("crash_enabled", dto.crashEnabled)
            put("sos_number", dto.sosNumber)
            put("service_km", dto.serviceKm)
            put("guard_schedule_start", dto.guardScheduleStart)
            put("guard_schedule_end", dto.guardScheduleEnd)
            if (touchUpdated) put("updated_at", java.time.Instant.now().toString())
        }.toString()
        authed {
            builder("/rest/v1/device_settings?on_conflict=device_id", it)
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(payload.toRequestBody(jsonType)).build()
        }.ok
    } catch (e: IOException) { false }

    /** The tracker calls this after it has applied the Remote phone's settings. */
    suspend fun markSettingsApplied(deviceId: String): Boolean = try {
        val body = buildJsonObject { put("applied_at", java.time.Instant.now().toString()) }.toString()
        authed {
            builder("/rest/v1/device_settings?device_id=eq.$deviceId", it)
                .header("Prefer", "return=minimal").patch(body.toRequestBody(jsonType)).build()
        }.ok
    } catch (e: IOException) { false }

    suspend fun createFamilyInvite(code: String): Boolean = try {
        val payload = json.encodeToString(FamilyInviteDto.serializer(), FamilyInviteDto(code = code))
        authed {
            builder("/rest/v1/family_invites", it).header("Prefer", "return=minimal")
                .post(payload.toRequestBody(jsonType)).build()
        }.ok
    } catch (e: IOException) { false }

    /** The tracker saves one row each time parking mode is armed, so the Remote phone can show it. */
    suspend fun saveParkingSpot(deviceId: String, lat: Double, lng: Double): Boolean = try {
        val payload = json.encodeToString(
            ParkingSpotDto.serializer(), ParkingSpotDto(deviceId = deviceId, lat = lat, lng = lng)
        )
        authed {
            builder("/rest/v1/parking_spots", it).header("Prefer", "return=minimal")
                .post(payload.toRequestBody(jsonType)).build()
        }.ok
    } catch (e: IOException) { false }

    suspend fun latestParkingSpot(deviceId: String): ParkingSpotDto? {
        val r = authed {
            builder("/rest/v1/parking_spots?device_id=eq.$deviceId&select=*&order=created_at.desc&limit=1", it).get().build()
        }
        if (!r.ok) return null
        return runCatching { json.decodeFromString(ListSerializer(ParkingSpotDto.serializer()), r.body) }.getOrNull()?.firstOrNull()
    }

    /** Updates the note on the newest parking spot for this device. */
    suspend fun updateLatestParkingNote(deviceId: String, note: String): Boolean = try {
        val body = buildJsonObject { put("note", note) }.toString()
        authed {
            builder("/rest/v1/parking_spots?device_id=eq.$deviceId&order=created_at.desc&limit=1", it)
                .header("Prefer", "return=minimal").patch(body.toRequestBody(jsonType)).build()
        }.ok
    } catch (e: IOException) { false }

    // ---------- token for the Realtime websocket ----------

    /** A usable access token; refreshes first when the current one is about to expire. Null when signed out. */
    suspend fun validToken(): String? {
        val t = prefs.accessToken ?: return null
        val exp = runCatching {
            val payload = String(Base64.getUrlDecoder().decode(t.split(".")[1]))
            json.parseToJsonElement(payload).jsonObject["exp"]!!.jsonPrimitive.content.toLong()
        }.getOrDefault(0L)
        if (exp - System.currentTimeMillis() / 1000 < 60) {
            if (!refresh()) return prefs.accessToken?.takeIf { exp > System.currentTimeMillis() / 1000 }
        }
        return prefs.accessToken
    }
}
