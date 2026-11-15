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
import kotlinx.serialization.json.JsonNull
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
            // diagnostics screen and Worker report carry it to the owner.
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
        // A fresh token means the session is alive again: any earlier expiry warning is stale.
        prefs.sessionExpiredPending = false
        // New credentials, fresh start: a 403 from the old account must not keep
        // the sync backed off after a re-sign-in.
        prefs.serverRejectCount = 0; prefs.syncBackoffUntilMs = 0L; prefs.syncBlockedReason = ""
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
                r.code == 400 || r.code == 401 -> {
                    // The refresh token was rejected: the session is dead. clearSession()
                    // stops tracking and disarms the guard, so the owner must be told —
                    // the flag survives clearSession() and re-posts the warning on the
                    // next app start if this process dies before the owner sees it.
                    prefs.clearSession(); prefs.sessionExpiredPending = true; sessionExpired.value = true; false
                }
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
            // 409 = the device row is genuinely gone (FK): re-register once.
            // 403 = the policy refuses this account/row: re-registering the same
            // id cannot fix it, so do NOT clear deviceRegistered — clearing it
            // made every flush re-register, upload, 403 again, forever. The
            // flush backoff in SyncManager bounds the retries instead.
            r.code == 409 -> { prefs.deviceRegistered = false; UploadOutcome(UploadResult.RETRY, r.error) }
            r.code == 403 -> UploadOutcome(UploadResult.RETRY, r.error)
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
            // Same split as upload(): only a 409 (missing device row) justifies
            // re-registering; a 403 is a policy refusal the same id cannot fix.
            r.code == 409 -> { prefs.deviceRegistered = false; UploadOutcome(UploadResult.RETRY, r.error) }
            r.code == 403 -> UploadOutcome(UploadResult.RETRY, r.error)
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

    /** Pending commands with their age computed by the database clock (v1.2.8), so a wrong phone clock cannot expire them. */
    suspend fun pendingCommands(deviceId: String): List<CommandDto>? {
        val body = buildJsonObject { put("p_device", deviceId) }.toString()
        val r = authed { builder("/rest/v1/rpc/pending_commands", it).post(body.toRequestBody(jsonType)).build() }
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

    /** All links for one device, newest first — shown in the Share dialog so each can be revoked alone. */
    suspend fun listShareLinks(deviceId: String): List<ShareLinkRow>? {
        val r = authed { builder("/rest/v1/share_links?device_id=eq.$deviceId&select=token,expires_at,created_at,revoked&order=created_at.desc", it).get().build() }
        return if (r.ok) runCatching { json.decodeFromString(ListSerializer(ShareLinkRow.serializer()), r.body) }.getOrNull() else null
    }

    /** Revokes ONE link by its token. The old revoke-all (device_id filter) is gone: revoking
     *  one link must never kill the others (review item #16). */
    suspend fun revokeShareLink(token: String): Boolean {
        val body = buildJsonObject { put("revoked", true) }.toString()
        return authed {
            builder("/rest/v1/share_links?token=eq.$token", it)
                .header("Prefer", "return=minimal").patch(body.toRequestBody(jsonType)).build()
        }.ok
    }

    // ---------- expenses (v1.2.15: the log syncs to the server so it survives an upgrade) ----------

    /** Uploads expense rows, keyed by the client UUID in [ExpenseDto.id]. Idempotent:
     *  on_conflict=id with ignore-duplicates, so a retry never creates a second row.
     *  A 400/422 (bad category or amount) can never succeed: the caller keeps those
     *  rows unsynced and reports the error instead of dropping a user's expense.
     *  A 409/403 is a plain retry here — it never touches deviceRegistered, which
     *  is about the tracker device row, not expenses. */
    suspend fun uploadExpenses(items: List<ExpenseDto>): UploadOutcome = try {
        val payload = json.encodeToString(ListSerializer(ExpenseDto.serializer()), items)
        val r = authed {
            builder("/rest/v1/expenses?on_conflict=id", it)
                .header("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(payload.toRequestBody(jsonType)).build()
        }
        when {
            r.ok -> UploadOutcome(UploadResult.OK)
            r.code == 400 || r.code == 422 -> UploadOutcome(UploadResult.REJECTED, r.error)
            else -> UploadOutcome(UploadResult.RETRY, r.error)
        }
    } catch (e: IOException) { UploadOutcome(UploadResult.RETRY, ioReason(e)) }

    /** All of the owner's expense rows, newest first. Throws IOException when offline; null on an HTTP error. */
    suspend fun listExpenses(): List<ExpenseDto>? {
        val r = authed { builder("/rest/v1/expenses?select=id,category,amount,note,spent_on,created_at&order=spent_on.desc&limit=500", it).get().build() }
        return if (r.ok) runCatching { json.decodeFromString(ListSerializer(ExpenseDto.serializer()), r.body) }.getOrNull() else null
    }

    /** Deletes one server row by its client UUID. False when offline or refused. */
    suspend fun deleteExpense(serverId: String): Boolean = try {
        authed { builder("/rest/v1/expenses?id=eq.$serverId", it).delete().build() }.ok
    } catch (e: IOException) { false }

    // ---------- v1.1.0: server-synced settings, parking spots ----------

    /** The Remote phone's edits for one tracker device, or null when no row exists yet. */
    suspend fun getDeviceSettings(deviceId: String): DeviceSettingsDto? {
        val r = authed {
            builder("/rest/v1/device_settings?device_id=eq.$deviceId&select=*", it).get().build()
        }
        if (!r.ok) return null
        return runCatching { json.decodeFromString(ListSerializer(DeviceSettingsDto.serializer()), r.body) }.getOrNull()?.firstOrNull()
    }

    /** True when a device_settings row exists for this device, false when the
     *  server answers with an empty list, null when it could not be read.
     *  Lets the tracker tell "no row yet" (local values are the truth) from
     *  "fetch failed" (keep omitting wiped values from the state patch). */
    suspend fun deviceSettingsRowExists(deviceId: String): Boolean? {
        val r = authed {
            builder("/rest/v1/device_settings?device_id=eq.$deviceId&select=device_id&limit=1", it).get().build()
        }
        if (!r.ok) return null
        return runCatching {
            json.parseToJsonElement(r.body).jsonArray.isNotEmpty()
        }.getOrNull()
    }

    /**
     * The Remote phone saves exactly the fields the user just changed — and
     * nothing else (v1.2.17). The old whole-row upsert rebuilt the row from
     * this phone's local Prefs, so a settings change tapped before
     * loadRemoteSettings() finished could overwrite the server row with
     * wiped defaults (service_km 0, an empty schedule, armed false). This is
     * a field-scoped upsert instead: POST with on_conflict=device_id and
     * merge-duplicates, so the row is created when missing (other columns
     * take their DB defaults) and only the listed columns change when it
     * exists. updated_at is always touched, so the tracker sees new work;
     * applied_at is never touched here — the tracker stamps it when it applies.
     * A schedule side is cleared with an explicit null (JsonNull); the
     * serializer would drop nulls, so the body is built by hand.
     */
    suspend fun patchDeviceSettings(
        deviceId: String,
        mode: String? = null,
        intervalSec: Int? = null,
        speedLimitKmh: Int? = null,
        autoArm: Boolean? = null,
        sirenOnAlert: Boolean? = null,
        sosSms: Boolean? = null,
        crashEnabled: Boolean? = null,
        sosNumber: String? = null,
        serviceKm: Int? = null,
        guardScheduleStart: Int? = null,
        guardScheduleEnd: Int? = null,
        clearScheduleStart: Boolean = false,
        clearScheduleEnd: Boolean = false,
    ): Boolean = try {
        val hasChange = mode != null || intervalSec != null || speedLimitKmh != null ||
            autoArm != null || sirenOnAlert != null || sosSms != null || crashEnabled != null ||
            sosNumber != null || serviceKm != null || guardScheduleStart != null ||
            guardScheduleEnd != null || clearScheduleStart || clearScheduleEnd
        if (!hasChange) return true
        val payload = buildJsonObject {
            put("device_id", deviceId)
            mode?.let { put("mode", it) }
            intervalSec?.let { put("interval_sec", it) }
            speedLimitKmh?.let { put("speed_limit_kmh", it) }
            autoArm?.let { put("auto_arm", it) }
            sirenOnAlert?.let { put("siren_on_alert", it) }
            sosSms?.let { put("sos_sms", it) }
            crashEnabled?.let { put("crash_enabled", it) }
            sosNumber?.let { put("sos_number", it) }
            serviceKm?.let { put("service_km", it) }
            if (clearScheduleStart) put("guard_schedule_start", JsonNull)
            else guardScheduleStart?.let { put("guard_schedule_start", it) }
            if (clearScheduleEnd) put("guard_schedule_end", JsonNull)
            else guardScheduleEnd?.let { put("guard_schedule_end", it) }
            put("updated_at", java.time.Instant.now().toString())
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

    /**
     * The tracker reports back exactly what a remote command changed — and nothing else.
     * This used to be a whole-row upsert of this phone's local settings, which could
     * overwrite a Remote edit made in the same ~30 s window with stale local values
     * (and then stamp applied_at over it, so the Remote showed "applied" for a value
     * that had silently reverted). Only the fields the command actually changed are
     * sent, so a Remote edit to any other field survives intact. updated_at is never
     * touched here — there is no trigger on device_settings that bumps it — so this
     * PATCH can neither look like new Remote work nor confuse the applied/waiting
     * display. applied_at rides along in the same request.
     */
    suspend fun patchTrackerSettings(deviceId: String, guardArmed: Boolean?, speedLimitKmh: Int?): Boolean = try {
        val body = buildJsonObject {
            guardArmed?.let { put("guard_armed", it) }
            speedLimitKmh?.let { put("speed_limit_kmh", it) }
            put("applied_at", java.time.Instant.now().toString())
        }.toString()
        authed {
            builder("/rest/v1/device_settings?device_id=eq.$deviceId", it)
                .header("Prefer", "return=minimal").patch(body.toRequestBody(jsonType)).build()
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
