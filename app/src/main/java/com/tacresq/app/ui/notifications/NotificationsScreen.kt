package com.tacresq.app.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.tacresq.app.data.model.User
import com.tacresq.app.data.model.UserNotification
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.ui.common.ScreenHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class NotificationUiState(
    val items: List<UserNotification> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val busyIds: Set<Long> = emptySet(),
    val error: String? = null,
    val actionError: String? = null,
)

class NotificationsViewModel(private val repo: TacRepository) : ViewModel() {
    private val _state = MutableStateFlow(NotificationUiState())
    val state: StateFlow<NotificationUiState> = _state.asStateFlow()
    private var refreshJob: kotlinx.coroutines.Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = it.items.isEmpty(),
                    refreshing = it.items.isNotEmpty(),
                    error = null,
                )
            }
            repo.myNotifications(limit = 50)
                .onSuccess { items ->
                    _state.update {
                        it.copy(
                            items = items,
                            loading = false,
                            refreshing = false,
                            error = null,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = failure.message ?: "Could not load notifications. Try again.",
                        )
                    }
                }
        }
    }

    fun markRead(notification: UserNotification) {
        if (notification.readAt != null || notification.id in _state.value.busyIds) return
        _state.update { it.copy(busyIds = it.busyIds + notification.id, actionError = null) }
        viewModelScope.launch {
            repo.markNotificationRead(notification.id)
                .onSuccess { marked ->
                    if (marked) {
                        val readAt = Instant.now().toString()
                        _state.update { current ->
                            current.copy(
                                items = current.items.map {
                                    if (it.id == notification.id) it.copy(readAt = it.readAt ?: readAt) else it
                                },
                                busyIds = current.busyIds - notification.id,
                                actionError = null,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                busyIds = it.busyIds - notification.id,
                                actionError = "This notification is no longer available to your account. Refresh the list.",
                            )
                        }
                        refresh()
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            busyIds = it.busyIds - notification.id,
                            actionError = failure.message ?: "Could not mark this notification as read.",
                        )
                    }
                }
        }
    }

    fun dismissActionError() = _state.update { it.copy(actionError = null) }
}

@Composable
fun NotificationsAndSafetyScreen(
    vm: NotificationsViewModel,
    user: User,
    onLogout: () -> Unit,
    onOpenIncident: (Long) -> Unit,
    onOpenChat: (Long) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.refresh()
            while (true) {
                delay(30_000)
                vm.refresh()
            }
        }
    }

    val unreadCount = state.items.count { it.readAt == null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(title = "Alerts & safety", subtitle = user.role.label, onLogout = onLogout)
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Notifications",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        when {
                            state.items.isEmpty() -> "No notifications yet"
                            unreadCount == 0 -> "No unread among the latest ${state.items.size} notifications"
                            else -> "$unreadCount unread among the latest ${state.items.size} notifications"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        OutlinedButton(onClick = vm::refresh, enabled = !state.loading && !state.refreshing) {
                            Text("Refresh")
                        }
                    }
                }
            }
        }

        state.actionError?.let { message ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::dismissActionError) { Text("Dismiss") }
                    }
                }
            }
        }

        state.error?.let { message ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Notifications unavailable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = vm::refresh, enabled = !state.refreshing) { Text("Try again") }
                    }
                }
            }
        }

        if (state.loading && state.items.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Loading your notifications…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else if (state.items.isEmpty() && state.error == null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("You're all caught up", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Assignment and incident status alerts will appear here when the backend records them.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            items(state.items, key = { it.id }) { notification ->
                NotificationCard(
                    notification = notification,
                    busy = notification.id in state.busyIds,
                    onMarkRead = { vm.markRead(notification) },
                    onOpenIncident = { incidentId ->
                        if (notification.readAt == null) vm.markRead(notification)
                        if (notification.eventType == "incident_message") onOpenChat(incidentId)
                        else onOpenIncident(incidentId)
                    },
                )
            }
        }

        item {
            Text(
                "Safety guidance",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            GuidanceCard(
                title = "If you need urgent help",
                body = "If someone is in immediate danger, contact the appropriate local emergency service directly when you can. Do not wait for an app status update to seek urgent assistance.",
            )
        }
        item {
            GuidanceCard(
                title = "Confirm the location",
                body = "Check the map pin and landmark together. GPS can be inaccurate indoors or near tall buildings; correct a misplaced pin before sending when it is safe to do so.",
            )
        }
        item {
            GuidanceCard(
                title = "Share location deliberately",
                body = "Field-unit location sharing is visible to authorized dispatch while the app is in use. Stop sharing when the response no longer requires it.",
            )
        }
        item {
            GuidanceCard(
                title = "Protect personal information",
                body = "Share only details relevant to the incident. Do not post medical details, phone numbers, or another person's location publicly.",
            )
        }
    }
}

@Composable
private fun NotificationCard(
    notification: UserNotification,
    busy: Boolean,
    onMarkRead: () -> Unit,
    onOpenIncident: (Long) -> Unit,
) {
    val unread = notification.readAt == null
    val incidentId = notification.incidentId
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (unread) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    notification.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                )
                Text(
                    if (unread) "Unread" else "Read",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(notification.body, style = MaterialTheme.typography.bodyMedium)
            val incidentLabel = incidentId?.let { "Incident #$it · " }.orEmpty()
            Text(
                incidentLabel + formatNotificationTime(notification.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (unread || incidentId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (incidentId != null) {
                        TextButton(
                            onClick = { onOpenIncident(incidentId) },
                            enabled = !busy,
                        ) { Text(if (notification.eventType == "incident_message") "Open conversation" else "Open incident") }
                    }
                    if (unread) {
                        TextButton(onClick = onMarkRead, enabled = !busy) {
                            if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("Mark as read")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidanceCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatNotificationTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "Time unavailable"
    return runCatching {
        val instant = Instant.parse(raw)
        DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault("Time unavailable")
}
