package com.tacresq.app.data.realtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tacresq.app.AppConfig
import com.tacresq.app.data.repo.TokenHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal Supabase Realtime v1 client for a private, user-scoped broadcast topic.
 * Broadcast payloads are hints only; the notification RPC remains authoritative.
 */
class SupabaseNotificationRealtime(
    private val tokenHolder: TokenHolder,
    private val userIdProvider: () -> Long?,
    private val scope: CoroutineScope,
) : NotificationRealtime {
    private val _state = MutableStateFlow(RealtimeConnectionState.FALLBACK_SYNC)
    override val state: StateFlow<RealtimeConnectionState> = _state
    private val _notificationHints = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val notificationHints: SharedFlow<Unit> = _notificationHints
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val ref = AtomicLong(0)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var stopped = true
    private var retryCount = 0

    override fun start() {
        stopped = false
        reconnectJob?.cancel()
        connect(isReconnect = false)
    }

    override fun stop() {
        stopped = true
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        reconnectJob = null
        heartbeatJob = null
        socket?.close(1000, "screen stopped")
        socket = null
        _state.value = RealtimeConnectionState.FALLBACK_SYNC
    }

    private fun connect(isReconnect: Boolean) {
        if (stopped || socket != null) return
        val token = tokenHolder.value?.takeIf { it.isNotBlank() }
        if (token == null || userIdProvider() == null) {
            _state.value = RealtimeConnectionState.UNAVAILABLE
            return
        }
        _state.value = if (isReconnect) RealtimeConnectionState.RECONNECTING else RealtimeConnectionState.CONNECTING
        val url = AppConfig.SUPABASE_URL.toHttpUrl().newBuilder()
            .scheme("wss")
            .addPathSegments("realtime/v1/websocket")
            .addQueryParameter("apikey", AppConfig.SUPABASE_PUBLISHABLE_KEY)
            .addQueryParameter("vsn", "1.0.0")
            .build()
        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            Listener(token),
        )
    }

    private fun scheduleReconnect() {
        if (stopped || reconnectJob?.isActive == true) return
        heartbeatJob?.cancel()
        socket = null
        retryCount = (retryCount + 1).coerceAtMost(6)
        val delayMs = (1_000L shl (retryCount - 1)).coerceAtMost(30_000L)
        _state.value = RealtimeConnectionState.RECONNECTING
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (!stopped) connect(isReconnect = true)
        }
    }

    private fun sendJoin(webSocket: WebSocket, token: String) {
        val topic = topic()
        val config = JsonObject().apply {
            add("broadcast", JsonObject().apply {
                addProperty("ack", false)
                addProperty("self", false)
            })
            add("presence", JsonObject().apply { addProperty("enabled", false) })
            addProperty("private", true)
        }
        val payload = JsonObject().apply {
            add("config", config)
            addProperty("access_token", token)
        }
        webSocket.send(message("realtime:$topic", "phx_join", payload))
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            try {
                while (!stopped) {
                    delay(25_000L)
                    webSocket.send(message("phoenix", "heartbeat", JsonObject()))
                }
            } catch (_: CancellationException) {
                // Screen stop or socket replacement is expected.
            }
        }
    }

    private fun message(topic: String, event: String, payload: JsonObject): String =
        """{"topic":${quote(topic)},"event":${quote(event)},"payload":$payload,"ref":"${ref.incrementAndGet()}"}"""

    private fun quote(value: String): String =
        com.google.gson.Gson().toJson(value)

    private fun topic(): String {
        // The profile ID comes from the authenticated session. The server independently
        // authorizes this topic with auth.uid(); the client never decodes JWT claims.
        return "user:${userIdProvider() ?: 0L}"
    }

    private inner class Listener(private val token: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            if (stopped) {
                webSocket.close(1000, "screen stopped")
                return
            }
            sendJoin(webSocket, token)
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
            when (message.get("event")?.asString) {
                "phx_reply" -> {
                    val status = message.getAsJsonObject("payload")?.getAsJsonObject("response")?.get("status")?.asString
                    if (status == "ok") {
                        retryCount = 0
                        _state.value = RealtimeConnectionState.CONNECTED
                    } else if (status == "error") {
                        _state.value = RealtimeConnectionState.ERROR
                        scheduleReconnect()
                    }
                }
                "broadcast" -> {
                    val payload = message.getAsJsonObject("payload") ?: return
                    if (payload.get("event")?.asString != "notification_created") return
                    val hint = payload.getAsJsonObject("payload") ?: return
                    if (hint.get("notification_id")?.asLong ?: 0L > 0L &&
                        hint.get("event_type")?.asString in setOf("incident_assigned", "incident_status_changed")
                    ) {
                        _notificationHints.tryEmit(Unit)
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            _state.value = RealtimeConnectionState.ERROR
            scheduleReconnect()
        }
    }
}
