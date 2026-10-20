package com.tacresq.app.ui.requester

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.locationbutton.compose.LocationButton
import androidx.core.locationbutton.compose.LocationButtonTextType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tacresq.app.data.model.GeoFix
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentType
import com.tacresq.app.data.model.User
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.location.LocationCaptureResult
import com.tacresq.app.location.LocationProvider
import com.tacresq.app.ui.common.Banner
import com.tacresq.app.ui.common.IncidentCard
import com.tacresq.app.ui.common.PollWhileStarted
import com.tacresq.app.ui.common.ScreenHeader
import com.tacresq.app.ui.common.SyncStatus
import com.tacresq.app.ui.common.formatTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

data class RequesterUiState(
    val incidents: List<Incident> = emptyList(),
    val loading: Boolean = true,
    val sending: Boolean = false,
    val capturingLocation: Boolean = false,
    val locationFix: GeoFix? = null,
    val locationError: String? = null,
    val notice: String? = null,
    val error: String? = null,
    val syncError: String? = null,
    val lastSyncedAtEpochMs: Long? = null,
)

class RequesterViewModel(
    private val repo: TacRepository,
    private val location: LocationProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(RequesterUiState())
    val state: StateFlow<RequesterUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    fun isFresh(fix: GeoFix): Boolean = location.isFresh(fix)

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            repo.myIncidents()
                .onSuccess { list ->
                    _state.update { it.copy(incidents = list, loading = false, syncError = null, lastSyncedAtEpochMs = System.currentTimeMillis()) }
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, syncError = e.message ?: "Couldn't refresh incident updates.") }
                }
        }
    }

    /** Called only after the user activates the Android LocationButton. */
    fun captureLocation() {
        if (_state.value.capturingLocation || _state.value.sending) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    capturingLocation = true,
                    locationFix = null,
                    locationError = null,
                    error = null,
                )
            }
            val result = try {
                location.currentFix()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LocationCaptureResult.Unavailable
            }
            when (result) {
                is LocationCaptureResult.Success -> _state.update {
                    it.copy(
                        capturingLocation = false,
                        locationFix = result.fix,
                        locationError = null,
                    )
                }
                LocationCaptureResult.PermissionDenied -> showLocationError(
                    "Location wasn't shared. You can try the location button again or send the SOS without a GPS pin."
                )
                LocationCaptureResult.ServicesDisabled -> showLocationError(
                    "Device Location is turned off. Turn it on and retry, or send the SOS without a GPS pin."
                )
                LocationCaptureResult.TimedOut -> showLocationError(
                    "A fresh GPS fix wasn't available within 15 seconds. Move near a window and retry, or send without a pin."
                )
                LocationCaptureResult.InvalidFix -> showLocationError(
                    "The location provider returned an invalid or stale fix. No pin was attached; retry or send without GPS."
                )
                LocationCaptureResult.Unavailable -> showLocationError(
                    "Current location is temporarily unavailable. Retry, add a landmark, or send without a GPS pin."
                )
            }
        }
    }

    fun onLocationDenied() = showLocationError(
        "Location permission wasn't granted. No GPS data was read. You can still send an SOS without a pin."
    )

    fun onLocationButtonError() = showLocationError(
        "The secure location prompt couldn't start. Retry the location button or send without GPS."
    )

    fun clearLocationFix() {
        _state.update { it.copy(locationFix = null, locationError = null) }
    }

    fun expireStaleLocation() {
        val fix = _state.value.locationFix ?: return
        if (!location.isFresh(fix)) {
            _state.update {
                it.copy(
                    locationFix = null,
                    locationError = "The saved GPS fix is older than two minutes and will not be sent. Capture a fresh fix or review the SOS without a pin.",
                )
            }
        }
    }

    private fun showLocationError(message: String) {
        _state.update { it.copy(capturingLocation = false, locationFix = null, locationError = message) }
    }

    fun sendSos(type: IncidentType, description: String, locationNotes: String) {
        if (_state.value.sending) return
        val current = _state.value
        if (current.locationFix != null && !location.isFresh(current.locationFix)) {
            _state.update {
                it.copy(
                    locationFix = null,
                    locationError = "The GPS fix expired while you were reviewing. No request was sent; capture a new fix or review again without a pin.",
                )
            }
            return
        }
        val fix = current.locationFix
        viewModelScope.launch {
            _state.update { it.copy(sending = true, notice = null, error = null) }
            val report = description.trim().ifBlank { null }
            val landmark = locationNotes.trim().take(500).ifBlank { null }
            repo.createIncident(type, report, fix, landmark)
                .onSuccess { created ->
                    val locationConfirmed = locationResponseMatches(fix, landmark, created)
                    val status = when {
                        !locationConfirmed -> "SOS request was accepted by the server, but its location response could not be verified."
                        fix != null -> "SOS sent. Dispatch confirmed a one-time GPS pin; TACResQ is not tracking you continuously."
                        else -> "SOS sent without a GPS pin. Dispatch received your request and can call you; any landmark note is included."
                    }
                    _state.update {
                        it.copy(
                            sending = false,
                            locationFix = null,
                            locationError = null,
                            notice = status,
                            error = if (locationConfirmed) null else "Do not resend this SOS. The server accepted the request, but the GPS pin or location note did not match what you approved. Contact dispatch to verify the location.",
                            incidents = listOf(created) + it.incidents,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(sending = false, error = e.message) } }
        }
    }

    private fun locationResponseMatches(fix: GeoFix?, notes: String?, incident: Incident): Boolean {
        if (incident.locationNotes?.trim().orEmpty() != notes?.trim().orEmpty()) return false
        if (fix == null) {
            return incident.latitude == null && incident.longitude == null &&
                incident.accuracyM == null && incident.locationRecordedAt.isNullOrBlank()
        }
        val latitude = incident.latitude ?: return false
        val longitude = incident.longitude ?: return false
        if (abs(latitude - fix.latitude) > 0.000001 || abs(longitude - fix.longitude) > 0.000001) return false
        val accuracyMatches = when {
            fix.accuracyM == null -> incident.accuracyM == null
            incident.accuracyM == null -> false
            else -> abs(incident.accuracyM.toDouble() - fix.accuracyM.toDouble()) <= 1.0
        }
        if (!accuracyMatches) return false
        val returnedAt = incident.locationRecordedAt?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        } ?: return false
        return abs(returnedAt - fix.capturedAtEpochMillis) <= 2_000L
    }

    fun dismissMessages() {
        _state.update { it.copy(notice = null, error = null, locationError = null) }
    }
}

@Composable
fun RequesterScreen(vm: RequesterViewModel, user: User, onLogout: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    PollWhileStarted { vm.refresh() }

    var selectedType by rememberSaveable { mutableStateOf(IncidentType.MEDICAL) }
    var description by rememberSaveable { mutableStateOf("") }
    var locationNotes by rememberSaveable { mutableStateOf("") }
    var confirmingSos by rememberSaveable { mutableStateOf(false) }
    var requestStep by rememberSaveable { mutableStateOf(1) }
    val freshFix = state.locationFix?.takeIf(vm::isFresh)
    val activeIncident = state.incidents.firstOrNull { it.status.isActive }

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            description = ""
            locationNotes = ""
            requestStep = 1
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Hello, ${user.name.substringBefore(' ')}",
                subtitle = user.role.label,
                onLogout = onLogout,
            )
        }

        item { SyncStatus(state.lastSyncedAtEpochMs, state.syncError) }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Emergency request", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Step $requestStep of 3 · Your request is not sent until you confirm.")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..3).forEach { step ->
                            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(
                                containerColor = if (step == requestStep) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            )) {
                                Text(when (step) { 1 -> "Details"; 2 -> "Location"; else -> "Review" },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (step == requestStep) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    when (requestStep) {
                        1 -> {
                            Text("What kind of help do you need?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(IncidentType.entries) { type ->
                                    FilterChip(selected = type == selectedType, onClick = { selectedType = type }, label = { Text(type.label) })
                                }
                            }
                            OutlinedTextField(value = description, onValueChange = { if (it.length <= 2000) description = it },
                                label = { Text("What is happening? (optional)") }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { requestStep = 2 }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue to location") }
                        }
                        2 -> {
                            Text("Confirm your location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Location is requested only after you use the Android location button. A GPS pin is attached only after a fresh fix is returned and you confirm sending.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.capturingLocation) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp)); Text("Finding a fresh location…")
                                }
                            } else {
                                LocationButton(modifier = Modifier.fillMaxWidth().height(56.dp), textType = LocationButtonTextType.SharePreciseLocation,
                                    backgroundColor = MaterialTheme.colorScheme.primary, strokeColor = MaterialTheme.colorScheme.primary,
                                    textColor = MaterialTheme.colorScheme.onPrimary, iconTint = MaterialTheme.colorScheme.onPrimary,
                                    onPermissionResult = { granted -> if (granted) vm.captureLocation() else vm.onLocationDenied() },
                                    onError = { vm.onLocationButtonError() })
                            }
                            when {
                                freshFix != null -> LocationFixCard(freshFix, onClear = vm::clearLocationFix)
                                state.locationFix != null -> LocationNoticeCard("GPS fix expired", "This fix will not be sent. Capture a new fix or continue without GPS.")
                                !state.capturingLocation -> LocationNoticeCard("No GPS pin selected", "If GPS is unavailable, continue without coordinates and add a landmark. TACResQ will not invent a location.")
                            }
                            state.locationError?.let { Banner(it, isError = true, onDismiss = vm::dismissMessages) }
                            OutlinedTextField(value = locationNotes, onValueChange = { if (it.length <= 500) locationNotes = it },
                                label = { Text("Landmark or access detail (optional)") }, minLines = 2, maxLines = 3,
                                supportingText = { Text("${locationNotes.length}/500 · shared with dispatch") }, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { requestStep = 1 }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Back") }
                                Button(onClick = { vm.expireStaleLocation(); requestStep = 3 }, enabled = !state.capturingLocation,
                                    modifier = Modifier.weight(1f).height(52.dp)) { Text("Review request") }
                            }
                        }
                        else -> {
                            Text("Review before sending", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Emergency type: ${selectedType.label}", fontWeight = FontWeight.SemiBold)
                            description.trim().takeIf { it.isNotEmpty() }?.let { Text("Details: $it") }
                            if (freshFix != null) LocationFixCard(freshFix, onClear = { vm.clearLocationFix(); requestStep = 2 })
                            else LocationNoticeCard("Sending without a GPS pin", "Dispatch may need to call you to confirm the exact location.")
                            locationNotes.trim().takeIf { it.isNotEmpty() }?.let { Text("Landmark/access detail: $it") }
                            activeIncident?.let { Text("You already have active request #${it.id}. Only continue if this is a separate emergency.", color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { requestStep = 2 }, enabled = !state.sending, modifier = Modifier.weight(1f).height(52.dp)) { Text("Back") }
                                Button(onClick = { confirmingSos = true }, enabled = !state.sending && !state.capturingLocation,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f).height(52.dp)) {
                                    if (state.sending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else Text("Confirm SOS", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        state.notice?.let { message -> item { Banner(message, isError = false, onDismiss = vm::dismissMessages) } }
        state.error?.let { message -> item { Banner(message, isError = true, onDismiss = vm::dismissMessages) } }

        item { Text("My requests", style = MaterialTheme.typography.titleMedium) }
        if (state.incidents.isEmpty() && !state.loading) {
            item { Text("No requests yet.") }
        }
        items(state.incidents, key = { it.id }) { incident -> IncidentCard(incident) }
    }

    if (confirmingSos) {
        AlertDialog(
            onDismissRequest = { confirmingSos = false },
            title = { Text("Send emergency request?") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("This will notify dispatch of a ${selectedType.label.lowercase()} emergency.")
                    description.trim().takeIf { it.isNotEmpty() }?.let { Text("Report details: $it") }
                    if (freshFix != null) {
                        Text(locationSummary(freshFix))
                        Text("The pin is a single capture, not live tracking.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("No GPS pin will be attached. Dispatch may need to call you to confirm where you are.")
                    }
                    locationNotes.trim().takeIf { it.isNotEmpty() }?.let {
                        Text("Location note to share: $it")
                    }
                    activeIncident?.let {
                        Text(
                            "You already have active request #${it.id} (${it.status.label}). This will create a second request; continue only if it is a separate emergency.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("Only continue if help is needed now.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSos = false
                    vm.sendSos(selectedType, description, locationNotes)
                }) { Text("Send SOS") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSos = false }) { Text("Go back") }
            },
        )
    }
}

@Composable
private fun LocationFixCard(fix: GeoFix, onClear: () -> Unit) {
    val accuracy = fix.accuracyM?.let { "±${ceil(it.toDouble()).toInt()} m" } ?: "not reported"
    val captured = runCatching { formatTime(Instant.ofEpochMilli(fix.capturedAtEpochMillis).toString()) }
        .getOrDefault("just now")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("One-time GPS fix ready", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Reported accuracy: $accuracy · captured $captured", style = MaterialTheme.typography.bodySmall)
            Text(
                if (fix.hasPrecisePermission) "Precise location access was granted for this action."
                else "Approximate location access is active; the pin may be broad.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (fix.accuracyM == null || fix.accuracyM > 100f) {
                Text(
                    "This fix is broad or has no reported accuracy. Add a landmark, or remove the location pin if you can't confirm it identifies you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onClear) { Text("Remove location pin") }
        }
    }
}

@Composable
private fun LocationNoticeCard(title: String, text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun locationSummary(fix: GeoFix): String {
    val accuracy = fix.accuracyM?.let { "reported accuracy ±${ceil(it.toDouble()).toInt()} m" }
        ?: "accuracy not reported"
    val timestamp = runCatching { formatTime(Instant.ofEpochMilli(fix.capturedAtEpochMillis).toString()) }
        .getOrDefault("just now")
    val precision = if (fix.hasPrecisePermission) "GPS pin" else "approximate GPS pin"
    return "One $precision ($accuracy), captured $timestamp, will be sent to dispatch."
}
