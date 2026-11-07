package com.tacresq.app.data.realtime

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Connection state is separate from the last authoritative RPC refresh time. */
enum class RealtimeConnectionState {
    UNAVAILABLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FALLBACK_SYNC,
    ERROR,
}

interface NotificationRealtime {
    val state: StateFlow<RealtimeConnectionState>
    val notificationHints: SharedFlow<Unit>
    fun start()
    fun stop()
}
