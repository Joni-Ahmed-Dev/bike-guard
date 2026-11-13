package com.bikeguard.app.data

import com.bikeguard.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

sealed interface RtEvent {
    data class Loc(val loc: LocationDto) : RtEvent
    data class Ev(val ev: EventDto) : RtEvent
    data class Dev(val dev: DeviceDto) : RtEvent
}

/**
 * Supabase Realtime over a plain OkHttp WebSocket (Phoenix protocol).
 * One shared connection; screens call acquire()/release(). Row Level Security applies,
 * so you only ever receive your own rows. Reconnects with back-off.
 */
class RealtimeClient(private val api: SupabaseApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val events = MutableSharedFlow<RtEvent>(extraBufferCapacity = 64)
    val connected = MutableStateFlow(false)

    private var refs = 0
    private var job: Job? = null

    @Synchronized fun acquire() {
        refs++
        if (job == null) job = scope.launch { loop() }
    }

    @Synchronized fun release() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs == 0) { job?.cancel(); job = null; connected.value = false }
    }

    private suspend fun loop() {
        var backoff = 1_000L
        while (scope.isActive) {
            val token = api.validToken()
            if (token == null || !api.isConfigured) { delay(5_000); continue }
            val closed = CompletableDeferred<Unit>()
            val url = BuildConfig.SUPABASE_URL.trimEnd('/').replaceFirst("http", "ws") +
                "/realtime/v1/websocket?apikey=${BuildConfig.SUPABASE_ANON_KEY}&vsn=1.0.0"
            val ws = http.newWebSocket(Request.Builder().url(url).build(), Listener(token, closed) { backoff = 1_000L })
            val beat = scope.launch { heartbeat(ws, token) }
            try { closed.await() } finally { beat.cancel(); ws.cancel(); connected.value = false }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun heartbeat(ws: WebSocket, initialToken: String) {
        var sentToken = initialToken
        var n = 100
        while (true) {
            delay(25_000)
            ws.send(frame("phoenix", "heartbeat", buildJsonObject {}, (n++).toString()))
            val fresh = api.validToken()
            if (fresh != null && fresh != sentToken) {
                sentToken = fresh
                ws.send(frame(TOPIC, "access_token", buildJsonObject { put("access_token", fresh) }, (n++).toString()))
            }
        }
    }

    private fun frame(topic: String, event: String, payload: kotlinx.serialization.json.JsonObject, ref: String) =
        buildJsonObject { put("topic", topic); put("event", event); put("payload", payload); put("ref", ref) }.toString()

    private fun change(event: String, table: String) = buildJsonObject {
        put("event", event); put("schema", "public"); put("table", table)
    }

    private inner class Listener(
        private val token: String,
        private val closed: CompletableDeferred<Unit>,
        private val onJoined: () -> Unit,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val payload = buildJsonObject {
                put("config", buildJsonObject {
                    put("postgres_changes", buildJsonArray {
                        add(change("INSERT", "locations")); add(change("INSERT", "events")); add(change("UPDATE", "devices"))
                    })
                })
                put("access_token", token)
            }
            webSocket.send(frame(TOPIC, "phx_join", payload, "1"))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val o = json.parseToJsonElement(text).jsonObject
                val payload = o["payload"]?.jsonObject
                when (o["event"]?.jsonPrimitive?.content) {
                    "phx_reply" -> if (o["ref"]?.jsonPrimitive?.content == "1") {
                        if (payload?.get("status")?.jsonPrimitive?.content == "ok") { connected.value = true; onJoined() }
                        else closed.complete(Unit)
                    }
                    "phx_close", "phx_error" -> closed.complete(Unit)
                    "postgres_changes" -> {
                        val data = payload?.get("data")?.jsonObject ?: return
                        val rec = data["record"] ?: return
                        when (data["table"]?.jsonPrimitive?.content) {
                            "locations" -> events.tryEmit(RtEvent.Loc(json.decodeFromJsonElement(LocationDto.serializer(), rec)))
                            "events" -> events.tryEmit(RtEvent.Ev(json.decodeFromJsonElement(EventDto.serializer(), rec)))
                            "devices" -> events.tryEmit(RtEvent.Dev(json.decodeFromJsonElement(DeviceDto.serializer(), rec)))
                        }
                    }
                }
            } catch (e: Exception) { /* ignore a malformed frame */ }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
    }

    private companion object { const val TOPIC = "realtime:bikeguard" }
}
