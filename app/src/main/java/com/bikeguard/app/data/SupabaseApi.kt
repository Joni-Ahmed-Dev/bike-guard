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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thin Supabase client (GoTrue auth + PostgREST) over OkHttp. No heavy SDK, easy to audit. */
class SupabaseApi(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val refreshMutex = Mutex()
    private val base get() = BuildConfig.SUPABASE_URL.trimEnd('/')

    /** Flips to true when the refresh token is rejected, so the UI can send the user back to sign-in. */
    val sessionExpired = MutableStateFlow(false)

    val isConfigured get() = BuildConfig.SUPABASE_URL.startsWith("http") && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    private class Resp(val code: Int, val body: String) { val ok get() = code in 200..299 }

    private fun builder(path: String, token: String?) = Request.Builder()
        .url(base + path)
        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        .header("Authorization", "Bearer " + (token ?: BuildConfig.SUPABASE_ANON_KEY))

    private suspend fun exec(r: Request): Resp = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { Resp(it.code, it.body?.string().orEmpty()) }
    }

    private suspend fun authed(make: (String) -> Request): Resp {
        var resp = exec(make(prefs.accessToken.orEmpty()))
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

    suspend fun registerDevice(id: String, name: String): Boolean {
        val payload = json.encodeToString(DeviceDto.serializer(), DeviceDto(id = id, name = name))
        val r = authed {
            builder("/rest/v1/devices?on_conflict=id", it)
                .header("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(payload.toRequestBody(jsonType)).build()
        }
        return r.ok
    }

    /** Throws IOException when offline; returns null on an HTTP error. */
    suspend fun listDevices(): List<DeviceDto>? {
        val r = authed { builder("/rest/v1/devices?select=id,name,last_seen&order=created_at.asc", it).get().build() }
        return if (r.ok) json.decodeFromString(ListSerializer(DeviceDto.serializer()), r.body) else null
    }

    // ---------- locations ----------

    suspend fun upload(items: List<LocationDto>): UploadResult = try {
        val payload = json.encodeToString(ListSerializer(LocationDto.serializer()), items)
        val r = authed {
            builder("/rest/v1/locations?on_conflict=device_id,recorded_at", it)
                .header("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(payload.toRequestBody(jsonType)).build()
        }
        when {
            r.ok -> UploadResult.OK
            r.code == 409 -> { prefs.deviceRegistered = false; UploadResult.RETRY } // device row missing: re-register
            r.code == 400 || r.code == 422 -> UploadResult.REJECTED                 // malformed: never succeeds, drop it
            else -> UploadResult.RETRY
        }
    } catch (e: IOException) { UploadResult.RETRY }

    /** Newest first. Throws IOException when offline; returns null on an HTTP error. */
    suspend fun recent(deviceId: String, limit: Int): List<LocationDto>? {
        val q = "/rest/v1/locations?device_id=eq.$deviceId" +
            "&select=device_id,lat,lng,speed,bearing,accuracy,battery,is_charging,recorded_at" +
            "&order=recorded_at.desc&limit=$limit"
        val r = authed { builder(q, it).get().build() }
        return if (r.ok) json.decodeFromString(ListSerializer(LocationDto.serializer()), r.body) else null
    }
}
