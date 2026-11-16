package com.bikeguard.app.data

import com.bikeguard.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A newer GitHub Release, when one exists. [apkUrl] is the direct download
 *  link of the release APK, when the release has one (v1.2.30: the app
 *  downloads it itself instead of opening the release page). */
data class UpdateInfo(val version: String, val url: String, val apkUrl: String? = null)

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

/**
 * In-app update check (v1.2.21, review item #23): Actions artifacts expire
 * and need a GitHub login, so Releases are the distribution channel — the
 * workflow already publishes one on every v* tag. This checks the latest
 * release on GitHub (public API, no login) and compares it with the
 * running version. Best-effort only: offline, or no releases published
 * yet (404), both come back as Failed / UpToDate, never a crash.
 * Never throws.
 */
object UpdateChecker {
    private const val LATEST_URL =
        "https://api.github.com/repos/Joni-Ahmed-Dev/bike-guard/releases/latest"
    private const val LATEST_PAGE_URL =
        "https://github.com/Joni-Ahmed-Dev/bike-guard/releases/latest"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkLatest(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try { checkViaPage() } catch (e: Exception) { null }
            ?: try { checkViaApi() } catch (e: Exception) { null }
            ?: UpdateCheckResult.Failed
    }

    /**
     * Primary check (v1.2.39): the releases page redirects to /tag/vX.Y.Z, so
     * the tag comes from the final URL — no API call, so the check works even
     * when the phone's shared mobile IP has used up GitHub's unauthenticated
     * API quota (60/hr), which is what left the tracker phone saying "could
     * not check" on working internet. The APK link is the standard release
     * asset URL. Returns null when it cannot tell, so the API gets a turn.
     */
    private fun checkViaPage(): UpdateCheckResult? {
        val req = Request.Builder().url(LATEST_PAGE_URL).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val tag = resp.request.url.toString().substringAfterLast("/tag/", "")
            if (tag.isEmpty()) return null
            val latest = tag.trimStart('v')
            if (!isNewer(latest, BuildConfig.VERSION_NAME)) return UpdateCheckResult.UpToDate
            val apkUrl =
                "https://github.com/Joni-Ahmed-Dev/bike-guard/releases/download/$tag/app-release.apk"
            return UpdateCheckResult.Available(
                UpdateInfo(latest, "https://github.com/Joni-Ahmed-Dev/bike-guard/releases/tag/$tag", apkUrl),
            )
        }
    }

    /** Fallback: the public releases API. Null when it cannot tell either. */
    private fun checkViaApi(): UpdateCheckResult? {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return UpdateCheckResult.UpToDate
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = Json.parseToJsonElement(body).jsonObject
            val tag = json["tag_name"]?.jsonPrimitive?.content ?: return null
            val url = json["html_url"]?.jsonPrimitive?.content ?: return null
            val latest = tag.trimStart('v')
            return if (isNewer(latest, BuildConfig.VERSION_NAME)) {
                UpdateCheckResult.Available(UpdateInfo(latest, url, apkUrl(json)))
            } else {
                UpdateCheckResult.UpToDate
            }
        }
    }

    /** Direct download link of the release APK, when the release has one. */
    private fun apkUrl(json: JsonObject): String? {
        val assets = json["assets"]?.jsonArray ?: return null
        val apk = assets.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == "app-release.apk" }
            ?: assets.firstOrNull { (it.jsonObject["name"]?.jsonPrimitive?.content ?: "").endsWith(".apk") }
            ?: return null
        return apk.jsonObject["browser_download_url"]?.jsonPrimitive?.content
    }

    /** "1.2.21" vs "1.2.20": compare segment by segment, numerically. */
    fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
