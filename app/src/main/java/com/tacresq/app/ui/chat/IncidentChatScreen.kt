package com.tacresq.app.ui.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.animateScrollToItem
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.tacresq.app.data.local.SessionStore
import com.tacresq.app.data.model.IncidentMessage
import com.tacresq.app.data.model.User
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.ui.common.ScreenHeader
import com.tacresq.app.ui.common.repositoryCallOrFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import java.security.MessageDigest


private const val MAX_CHAT_IMAGE_BYTES = 5 * 1024 * 1024

/** Read a selected image with a hard byte limit before allocating the full payload. */
private fun readChatImageBytes(context: android.content.Context, uri: android.net.Uri): ByteArray? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_CHAT_IMAGE_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }.getOrNull()

data class IncidentChatUiState(
    val items: List<IncidentMessage> = emptyList(),
    val draft: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingOlder: Boolean = false,
    val sending: Boolean = false,
    val restoringPending: Boolean = false,
    val pendingImageRetry: Boolean = false,
    val hasOlder: Boolean = true,
    val error: String? = null,
    val sendError: String? = null,
    val attachmentBytes: ByteArray? = null,
    val attachmentMimeType: String? = null,
)

internal fun chatImageSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class IncidentChatViewModel(private val repo: TacRepository) : ViewModel() {
    private val _state = MutableStateFlow(IncidentChatUiState())
    val state: StateFlow<IncidentChatUiState> = _state.asStateFlow()
    private var activeIncidentId: Long? = null
    private var conversationGeneration: Long = 0L

    private fun isCurrentConversation(incidentId: Long, generation: Long): Boolean =
        activeIncidentId == incidentId && conversationGeneration == generation
    private var refreshJob: Job? = null
    private var olderJob: Job? = null
    private var sendJob: Job? = null
    private var pendingRestoreJob: Job? = null
    private data class PendingTextMessage(val incidentId: Long, val message: String, val clientMessageId: String)
    // Retain uncertain sends across conversation close/reopen. Never mint a new key for the same payload.
    private var pendingTextMessage: PendingTextMessage? = null
    private var pendingClientMessageId: String? = null
    private var     private var     private var pendingImageRecord: SessionStore.PendingChatImage? = null

    fun open(incidentId: Long) {
        if (activeIncidentId != incidentId) {
            conversationGeneration += 1
            refreshJob?.cancel()
            olderJob?.cancel()
            sendJob?.cancel()
            refreshJob = null
            olderJob = null
            sendJob = null
            activeIncidentId = incidentId
            val recoverableDraft = pendingTextMessage?.takeIf { it.incidentId == incidentId }?.message.orEmpty()
            _state.value = IncidentChatUiState(draft = recoverableDraft, restoringPending = true)
            pendingRestoreJob?.cancel()
            pendingRestoreJob = viewModelScope.launch {
                repo.pendingChatMessage()
                    .onSuccess { saved ->
                        if (!isCurrentConversation(incidentId, conversationGeneration)) return@onSuccess
                        pendingTextMessage = saved?.let { PendingTextMessage(it.incidentId, it.message, it.clientMessageId) }
                        pendingClientMessageId = saved?.clientMessageId
                        if (saved != null && saved.incidentId != incidentId) {
                            _state.update { it.copy(restoringPending = true, sendError = "A previous message is unresolved in another incident. Open that incident and retry it first.") }
                            return@onSuccess
                        }
                        repo.pendingChatImage()
                            .onSuccess { image ->
                                if (!isCurrentConversation(incidentId, conversationGeneration)) return@onSuccess
                                pendingImageRecord = image
                                                                                                if (image != null && image.incidentId != incidentId) {
                                    _state.update { it.copy(restoringPending = true, sendError = "An earlier image message is unresolved in another incident. Open that incident and reselect the same image to retry it.") }
                                } else {
                                    _state.update {
                                        it.copy(
                                            draft = saved?.message ?: image?.caption.orEmpty(),
                                            restoringPending = false,
                                            pendingImageRetry = image != null,
                                            sendError = if (image != null) "An earlier image send is unconfirmed. Re-select the exact same image to safely retry it." else null,
                                        )
                                    }
                                }
                            }
                            .onFailure { failure ->
                                if (!isCurrentConversation(incidentId, conversationGeneration)) return@onFailure
                                _state.update { it.copy(restoringPending = true, sendError = failure.message ?: "Could not safely restore the pending image. Sending is blocked.") }
                            }
                    }
                    .onFailure { failure ->
                        if (!isCurrentConversation(incidentId, conversationGeneration)) return@onFailure
                        _state.update { it.copy(restoringPending = true, sendError = failure.message ?: "Could not safely restore the pending message. Sending is blocked.") }
                    }
            }
        }
        refresh(incidentId)
    }

    fun refresh(incidentId: Long) {
        if (activeIncidentId != incidentId || refreshJob?.isActive == true) return
        val generation = conversationGeneration
        refreshJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = it.items.isEmpty(),
                    refreshing = it.items.isNotEmpty(),
                    error = null,
                )
            }
            repositoryCallOrFailure { repo.myIncidentMessages(incidentId, limit = 50) }
                .onSuccess { latest ->
                    if (!isCurrentConversation(incidentId, generation)) return@onSuccess
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
                    if (!isCurrentConversation(incidentId, generation)) return@onFailure
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
        val generation = conversationGeneration
        olderJob = viewModelScope.launch {
            _state.update { it.copy(loadingOlder = true, error = null) }
            repositoryCallOrFailure { repo.myIncidentMessages(incidentId, limit = 50, beforeId = cursor) }
                .onSuccess { older ->
                    if (!isCurrentConversation(incidentId, generation)) return@onSuccess
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
                    if (!isCurrentConversation(incidentId, generation)) return@onFailure
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
        val pending = pendingTextMessage
        if (pending != null && next.trim() != pending.message) {
            _state.update { it.copy(draft = pending.message, sendError = "A previous message may already have been sent. Retry its original text in the same incident before editing it.") }
            return
        }
        if (next != _state.value.draft) {
                                }
        _state.update { it.copy(draft = next, sendError = null) }
    }

    fun send(incidentId: Long) {
        val message = _state.value.draft.trim()
        if (pendingImageRecord != null) {
            _state.update { it.copy(sendError = "An image send is still unresolved. Re-select the original image before retrying.") }
            return
        }
        if (activeIncidentId != incidentId || _state.value.restoringPending || sendJob?.isActive == true || message.isEmpty() || message.length > 2000) return
        val pending = pendingTextMessage
        if (pending != null && (pending.incidentId != incidentId || pending.message != message)) {
            _state.update { it.copy(sendError = "Resolve the earlier uncertain message in its original incident before sending different content.") }
            return
        }
        val clientMessageId = pending?.clientMessageId ?: UUID.randomUUID().toString().also {
            pendingClientMessageId = it
            pendingTextMessage = PendingTextMessage(incidentId, message, it)
        }
        val generation = conversationGeneration
        sendJob = viewModelScope.launch {
            _state.update { it.copy(sending = true, sendError = null, error = null) }
            val saved = repo.persistPendingChatMessage(incidentId, message, clientMessageId)
            if (saved.isFailure) {
                if (isCurrentConversation(incidentId, generation)) {
                    _state.update { it.copy(sending = false, sendError = saved.exceptionOrNull()?.message ?: "Could not safely save this message for retry.") }
                }
                return@launch
            }
            repositoryCallOrFailure { repo.sendIncidentMessage(incidentId, message, clientMessageId) }
                .onSuccess { sent ->
                    if (!isCurrentConversation(incidentId, generation)) return@onSuccess
                    val cleared = repo.clearPendingChatMessage(clientMessageId)
                    if (cleared.isFailure) {
                        _state.update { it.copy(sending = false, sendError = "Message was sent, but its retry record could not be cleared. Retry safely to confirm the same message.") }
                        return@onSuccess
                    }
                    if (pendingTextMessage?.clientMessageId == clientMessageId) pendingTextMessage = null
                    pendingClientMessageId = null
                    _state.update { current ->
                        current.copy(
                            items = (current.items + sent).distinctBy { it.id }.sortedBy { it.id },
                            draft = "",
                            sending = false,
                            sendError = null,
                            pendingImageRetry = false,
                        )
                    }
                }
                .onFailure { failure ->
                    if (!isCurrentConversation(incidentId, generation)) return@onFailure
                    _state.update {
                        it.copy(
                            sending = false,
                            sendError = failure.message ?: "Message was not confirmed. Retry safely.",
                        )
                    }
                }
        }
    }

    fun setImageAttachment(bytes: ByteArray, mimeType: String) {
        if (mimeType !in setOf("image/jpeg", "image/png", "image/webp") ||
            bytes.isEmpty() || bytes.size > MAX_CHAT_IMAGE_BYTES
        ) {
            _state.update { it.copy(sendError = "Choose a JPG, PNG, or WebP image up to 5 MB.") }
            return
        }
        val restored = pendingImageRecord
        val digest = chatImageSha256(bytes)
        val matchesPending = restored == null ||
            (restored.sha256 == digest && restored.mimeType == mimeType && restored.incidentId == activeIncidentId)
        _state.update {
            it.copy(
                attachmentBytes = bytes.copyOf(),
                attachmentMimeType = mimeType,
                sendError = when {
                    !matchesPending -> "This is not the same image as the unconfirmed send. Re-select the original image to avoid creating a duplicate."
                    restored != null -> "Original image restored. Retry to confirm the earlier send."
                    else -> null
                },
            )
        }
    }

    fun clearImageAttachment() {
        _state.update {
            it.copy(
                attachmentBytes = null,
                attachmentMimeType = null,
                sendError = if (pendingImageRecord != null)
                    "The earlier image send is still unresolved. Re-select the original image to retry it safely."
                else null,
            )
        }
    }

    fun sendImage(incidentId: Long, senderId: Long) {
        val current = _state.value
        val bytes = current.attachmentBytes ?: return
        val mimeType = current.attachmentMimeType ?: return
        val caption = current.draft.trim()
        if (activeIncidentId != incidentId || senderId < 1 || sendJob?.isActive == true || caption.length > 2000) return
        val digest = sha256(bytes)
        val restored = pendingImageRecord
        if (restored != null && (
                restored.incidentId != incidentId ||
                    restored.caption != caption ||
                    restored.mimeType != mimeType ||
                    restored.sha256 != digest
            )
        ) {
            _state.update { it.copy(sendError = "This does not match the unconfirmed image. Re-select the original image and keep its original caption before retrying.") }
            return
        }
        val clientMessageId = restored?.clientMessageId ?: UUID.randomUUID().toString()
        val generation = conversationGeneration
        sendJob = viewModelScope.launch {
            _state.update { it.copy(sending = true, sendError = null, error = null) }
            if (restored == null) {
                repositoryCallOrFailure {
                    repo.persistPendingChatImage(incidentId, caption, clientMessageId, mimeType, digest)
                }.onFailure { failure ->
                    if (isCurrentConversation(incidentId, generation)) {
                        _state.update { it.copy(sending = false, sendError = failure.message ?: "Could not safely save this image retry. Image was not sent.") }
                    }
                    return@launch
                }
                pendingImageRecord = SessionStore.PendingChatImage(
                    incidentId, caption, clientMessageId, mimeType, digest, senderId,
                )
                                                _state.update { it.copy(pendingImageRetry = true) }
            }
            repositoryCallOrFailure { repo.sendIncidentImageMessage(incidentId, senderId, caption, clientMessageId, mimeType, bytes) }
                .onSuccess { sent ->
                    if (!isCurrentConversation(incidentId, generation)) return@onSuccess
                    val cleared = repo.clearPendingChatImage(clientMessageId)
                    if (cleared.isFailure) {
                        _state.update { it.copy(sending = false, sendError = "Image was sent, but its retry record could not be cleared. Refresh the conversation before retrying; the same message ID is retained.") }
                        refresh(incidentId)
                        return@onSuccess
                    }
                    pendingImageRecord = null
                                                            _state.update { state ->
                        state.copy(
                            items = (state.items + sent).distinctBy { it.id }.sortedBy { it.id },
                            draft = "",
                            attachmentBytes = null,
                            attachmentMimeType = null,
                            sending = false,
                            sendError = null,
                            pendingImageRetry = false,
                        )
                    }
                }
                .onFailure { failure ->
                    if (!isCurrentConversation(incidentId, generation)) return@onFailure
                    _state.update {
                        it.copy(
                            sending = false,
                            sendError = failure.message ?: "Image was not confirmed. Re-select the same image and retry safely.",
                        )
                    }
                }
        }
    }

    suspend fun downloadImage(path: String): ByteArray? =
        repo.downloadIncidentImage(path).getOrNull()

    fun dismissError() = _state.update { it.copy(error = null, sendError = null) }

    fun closeConversation() {
        conversationGeneration += 1
        refreshJob?.cancel()
        olderJob?.cancel()
        sendJob?.cancel()
        refreshJob = null
        olderJob = null
        sendJob = null
        pendingRestoreJob?.cancel()
        pendingRestoreJob = null
        activeIncidentId = null
        // Do not clear an uncertain text-send identity here: the server may have committed
        // it even though cancellation prevented the response from reaching this ViewModel.
                        // Keep the encrypted retry identity in SessionStore; it must survive navigation.
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
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = readChatImageBytes(context, uri)
            if (bytes == null) vm.setImageAttachment(byteArrayOf(), mimeType)
            else vm.setImageAttachment(bytes, mimeType)
        }
    }
    val selectedImageBitmap by produceState<ImageBitmap?>(initialValue = null, state.attachmentBytes) {
        val bytes = state.attachmentBytes
        value = bytes?.let {
            withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
            }
        }
    }
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

    // Follow newly received messages only while the reader is already near the bottom.
    // Do not pull someone away from older messages while they are reviewing the thread.
    LaunchedEffect(state.items.lastOrNull()?.id, state.items.size, state.hasOlder) {
        if (didInitialScroll && state.items.isNotEmpty()) {
            val layout = listState.layoutInfo
            val lastVisibleIndex = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisibleIndex >= layout.totalItemsCount - 2) {
                listState.animateScrollToItem(state.items.lastIndex + if (state.hasOlder) 1 else 0)
            }
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
                        Text("Visible to authorized incident participants", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenNotifications) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Open notifications")
                    }
                    if (state.refreshing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
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
                            if (ownMessage) "You" else message.senderName?.takeIf { it.isNotBlank() } ?: "Incident participant",
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
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                message.attachmentPath?.let { path ->
                                    IncidentMessageImage(path = path, vm = vm)
                                }
                                if (message.message.isNotBlank()) {
                                    Text(message.message, modifier = Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodyLarge)
                                }
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
                    TextButton(
                        onClick = {
                            if (state.attachmentBytes != null) vm.sendImage(incidentId, user.id)
                            else if (!state.pendingImageRetry) vm.send(incidentId)
                        },
                        enabled = !state.sending && if (state.attachmentBytes != null) true
                            else state.draft.isNotBlank() && !state.pendingImageRetry,
                    ) { Text("Retry") }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.attachmentBytes != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (selectedImageBitmap != null) {
                                Image(
                                    bitmap = selectedImageBitmap,
                                    contentDescription = "Selected image preview",
                                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Photo ready to send", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "${((state.attachmentBytes?.size ?: 0) / 1024).coerceAtLeast(1)} KB · Private to this incident",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = vm::clearImageAttachment, enabled = !state.sending) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove selected image")
                            }
                        }
                    }
                }
                Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(
                    onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = !state.sending && !state.restoringPending && !state.loading && state.error == null,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Attach image")
                }
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = vm::updateDraft,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    placeholder = { Text("Message the incident team…") },
                    maxLines = 4,
                    supportingText = if (state.draft.length >= 1800) {
                        { Text("${state.draft.length}/2000") }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (state.attachmentBytes != null) vm.sendImage(incidentId, user.id)
                        else if (!state.pendingImageRetry) vm.send(incidentId)
                    }),
                    enabled = !state.sending && !state.restoringPending && !state.loading && state.error == null && !state.pendingImageRetry,
                    shape = RoundedCornerShape(22.dp),
                )
                androidx.compose.material3.FilledIconButton(
                    onClick = {
                        if (state.attachmentBytes != null) vm.sendImage(incidentId, user.id)
                        else if (!state.pendingImageRetry) vm.send(incidentId)
                    },
                    enabled = !state.sending && !state.restoringPending && !state.loading && state.error == null &&
                        (state.attachmentBytes != null || (!state.pendingImageRetry && state.draft.trim().isNotEmpty())),
                    modifier = Modifier.size(54.dp),
                ) {
                    if (state.sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                }
                }
            }
        }
    }
}

@Composable
private fun IncidentMessageImage(path: String, vm: IncidentChatViewModel) {
    var retryCount by remember(path) { mutableStateOf(0) }
    val loadState by produceState<Pair<ImageBitmap?, Boolean>>(initialValue = null to false, path, retryCount) {
        val bytes = vm.downloadImage(path)
        val decoded = bytes?.let {
            withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
            }
        }
        value = decoded to (decoded == null)
    }
    val image = loadState.first
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "Image shared in this incident conversation",
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loadState.second) {
                    Text("Image unavailable", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { retryCount++ }) { Text("Retry") }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading shared image…", style = MaterialTheme.typography.labelMedium)
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
