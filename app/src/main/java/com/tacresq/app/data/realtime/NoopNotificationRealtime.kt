package com.tacresq.app.data.realtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class NoopNotificationRealtime : NotificationRealtime {
    override val state = MutableStateFlow(RealtimeConnectionState.UNAVAILABLE)
    override val notificationHints = MutableSharedFlow<Unit>()
    override fun start() = Unit
    override fun stop() = Unit
}
