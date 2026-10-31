package com.tacresq.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.tacresq.app.data.model.IncidentMessage
import com.tacresq.app.data.model.User
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.ui.common.ScreenHeader
import kotlinx.coroutines.Job
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
import java.util.UUID

data class IncidentChatUiState(
    val items: List<IncidentMessage> = emptyList(),
    val draft: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingOlder: Boolean = false,
    val sending: Boolean = false,
    val hasOlder: Boolean = true,
    val error: String? = null,
    val sendError: String? = null,
)

class IncidentChatViewModel(private val repo: TacRepository) : ViewModel() {
    private val _state = MutableStateFlow(IncidentChatUiState())
    val state: StateFlow<IncidentChatUiState> = _state.asStateFlow()
    private var activeIncidentId: Long? = null
    private var refreshJob: Job? = null
    private var olderJob: Job? = null
    private var sendJob: Job? = null
    private var pendingClientMessageId: String? = null

    fun open(incidentId: Long) {
        if (activeIncidentId != incidentId) {
            refreshJob?.cancel()
            olderJob?.cancel()
            sendJob = null
            activeIncidentId = incidentId
            pendingClientMessageId = null
            _state.value = IncidentChatUiState()
        }
        refresh(incidentId)
    }

    fun refresh(incidentId: Long) {
        if (activeIncidentId != incidentId || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = it.items.isEmpty(),
                    refreshing = it.items.isNotEmpty(),
                    error = null,
                )
            }
            repo.myIncidentMessages(incidentId, limit = 50)
                .onSuccess { latest ->
                    if (activeIncidentId != incidentId) return@onSuccess
                    _state.update { current ->
                        val merged = (current.items + latest).distinctBy { it.id }.sortedBy { it.id }
                        current.copy(
                            items = merged,
                            loading = false,
                            refreshing = false,
                            hasOlder = current.hasOlder && latest.size >= 50,
                            error = null,
                        )
                    }
                }
                .onFailure { failure ->
                    if (activeIncidentId != incidentId) return@onFailure
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = failure.message ?: "Could not load this incident conversation.",
                        )
                    }
                }
        }
    }

    fun loadOlder(incidentId: Long) {
        if (activeIncidentId != incidentId || olderJob?.isActive == true) return
        val cursor = _state.value.items.firstOrNull()?.id ?: return
        if (!_state.value.hasOlder) return
        olderJob = viewModelScope.launch {
            _state.update { it.copy(loadingOlder = true, error = null) }
            repo.myIncidentMessages(incidentId, limit = 50, beforeId = cursor)
                .onSuccess { older ->
                    if (activeIncidentId != incidentId) return@onSuccess
                    _state.update { current ->
                        current.copy(
                            items = (older + current.items).distinctBy { it.id }.sortedBy { it.id },
                            loadingOlder = false,
                            hasOlder = older.size >= 50,
                            error = null,
                        )
                    }
                }
                .onFailure { failure ->
                    if (activeIncidentId != incidentId) return@onFailure
                    _state.update {
                        it.copy(
                            loadingOlder = false,
                            error = failure.message ?: "Could not load older messages.",
                        )
                    }
                }
        }
    }

    fun updateDraft(value: String) {
        val next = value.take(2000)
        if (next != _state.value.draft) pendingClientMessageId = null
        _state.update { it.copy(draft = next, sendError = null) }
    }

    fun send(incidentId: Long) {
        val message = _state.value.draft.trim()
        if (activeIncidentId != incidentId || sendJob?.isActive == true || message.isEmpty() || message.length > 2000) return
        val clientMessageId = pendingClientMessageId ?: UUID.randomUUID().toString().also {
            pendingClientMessageId = it
        }
        sendJob = viewModelScope.launch {
            _state.update { it.copy(sending = true, sendError = null, error = null) }
            repo.sendIncidentMessage(incidentId, message, clientMessageId)
                .onSuccess { sent ->
                    if (activeIncidentId != incidentId) return@onSuccess
                    pendingClientMessageId = null
                    _state.update { current ->
                        current.copy(
                            items = (current.items + sent).distinctBy { it.id }.sortedBy { it.id },
                            draft = "",
                            sending = false,
                            sendError = null,
                        )
                    }
                }
                .onFailure { failure ->
                    if (activeIncidentId != incidentId) return@onFailure
                    _state.update {
                        it.copy(
                            sending = false,
                            sendError = failure.message ?: "Message was not confirmed. Retry safely.",
                        )
                    }
                }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null, sendError = null) }

    fun closeConversation() {
        refreshJob?.cancel()
        olderJob?.cancel()
        sendJob = null
        activeIncidentId = null
        pendingClientMessageId = null
        _state.value = IncidentChatUiState()
    }
}

@Composable
fun IncidentChatScreen(
    vm: IncidentChatViewModel,
    incidentId: Long,
    user: User,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onOpenNotifications: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    var didInitialScroll by remember(incidentId) { mutableStateOf(false) }

    LaunchedEffect(vm, incidentId, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.open(incidentId)
            while (true) {
                delay(10_000)
                vm.refresh(incidentId)
            }
        }
    }

    LaunchedEffect(state.loading, state.items.size, incidentId) {
        if (!state.loading && state.items.isNotEmpty() && !didInitialScroll) {
            listState.scrollToItem(state.items.lastIndex + if (state.hasOlder) 1 else 0)
            didInitialScroll = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to conversations")
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Incident #$incidentId", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Incident conversation", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenNotifications) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Open notifications")
                    }
                    if (state.refreshing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Text(
                        "Keep messages relevant to this incident. Avoid sharing unrelated personal information.",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.error?.let { message ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { vm.refresh(incidentId) }) { Text("Retry") }
                        TextButton(onClick = vm::dismissError) { Text("Dismiss") }
                    }
                }
            }
        }

        if (state.loading && state.items.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.size(10.dp))
                Text("Loading messages…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (state.items.isEmpty() && state.error == null) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.padding(16.dp).size(28.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text("No messages yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Send an update or ask a question about this incident. Authorized participants can follow the conversation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.hasOlder && state.items.isNotEmpty()) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { vm.loadOlder(incidentId) }, enabled = !state.loadingOlder) {
                                if (state.loadingOlder) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("Load earlier messages")
                            }
                        }
                    }
                }
                items(state.items, key = { it.id }) { message ->
                    val ownMessage = message.senderId == user.id
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (ownMessage) Alignment.End else Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            if (ownMessage) "You" else "Incident participant",
                            modifier = Modifier.padding(horizontal = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            modifier = Modifier.widthIn(max = 340.dp),
                            shape = RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 18.dp,
                                bottomEnd = if (ownMessage) 5.dp else 18.dp,
                                bottomStart = if (ownMessage) 18.dp else 5.dp,
                            ),
                            color = if (ownMessage) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (ownMessage) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(message.message, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    formatChatTime(message.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (ownMessage) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        state.sendError?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.send(incidentId) }, enabled = !state.sending && state.draft.isNotBlank()) { Text("Retry") }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = vm::updateDraft,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    placeholder = { Text("Message the incident team…") },
                    maxLines = 4,
                    supportingText = { Text("${state.draft.length}/2000") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.send(incidentId) }),
                    enabled = !state.sending && !state.loading && state.error == null,
                    shape = RoundedCornerShape(22.dp),
                )
                androidx.compose.material3.FilledIconButton(
                    onClick = { vm.send(incidentId) },
                    enabled = !state.sending && !state.loading && state.error == null && state.draft.trim().isNotEmpty(),
                    modifier = Modifier.size(54.dp),
                ) {
                    if (state.sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                }
            }
        }
    }
}

private fun formatChatTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "Time unavailable"
    return runCatching {
        DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(raw))
    }.getOrDefault("Time unavailable")
}
