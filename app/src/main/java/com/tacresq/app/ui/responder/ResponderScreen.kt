package com.tacresq.app.ui.responder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.User
import com.tacresq.app.data.model.nextSteps
import com.tacresq.app.data.model.statusFor
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.location.LocationCaptureResult
import com.tacresq.app.location.LocationProvider
import com.tacresq.app.ui.common.Banner
import com.tacresq.app.ui.common.IncidentCard
import com.tacresq.app.ui.common.PollWhileStarted
import com.tacresq.app.ui.common.ScreenHeader
import com.tacresq.app.ui.common.SyncStatus
import com.tacresq.app.ui.common.SectionHeading
import com.tacresq.app.ui.common.StatusPill
import com.tacresq.app.ui.common.actionLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResponderUiState(
    val incidents: List<Incident> = emptyList(),
    val dutyStatus: DutyStatus = DutyStatus.OFF_DUTY,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val trackingIncidentId: Long? = null,
    val trackingError: String? = null,
    val lastLocationSentAt: Long? = null,
    val lastLocationAccuracyM: Float? = null,
    val syncError: String? = null,
    val lastSyncedAtEpochMs: Long? = null,
)

/** Used for both the Field Responder and Driver roles. */
class ResponderViewModel(
    private val repo: TacRepository,
    private val location: LocationProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(ResponderUiState())
    val state: StateFlow<ResponderUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var refreshRequested = false

    fun refresh() {
        if (refreshJob?.isActive == true) {
            // If an action completes during a refresh, queue one follow-up so the
            // screen cannot finish with the pre-action server snapshot.
            refreshRequested = true
            return
        }
        _state.update { it.copy(refreshing = true) }
        refreshJob = viewModelScope.launch {
            try {
                val me = repo.me()
                val mine = repo.myIncidents()
                val refreshError = me.exceptionOrNull() ?: mine.exceptionOrNull()
                val currentUserId = me.getOrNull()?.id
                _state.update { s ->
                    val serverIncidents = mine.getOrNull()
                    // The aggregate can stay en-route while another unit is still travelling.
                    // Stop this device's GPS stream when this responder's own assignment advances.
                    val trackingEnded = s.trackingIncidentId != null && serverIncidents != null &&
                        (currentUserId == null || serverIncidents.none {
                            it.id == s.trackingIncidentId && it.statusFor(currentUserId) == IncidentStatus.EN_ROUTE
                        })
                    s.copy(
                        loading = false,
                        refreshing = false,
                        dutyStatus = me.getOrNull()?.dutyStatus ?: s.dutyStatus,
                        incidents = serverIncidents ?: s.incidents,
                        trackingIncidentId = if (trackingEnded) null else s.trackingIncidentId,
                        trackingError = if (trackingEnded) "Location sharing stopped because this incident is no longer assigned en route." else s.trackingError,
                        syncError = refreshError?.message,
                        lastSyncedAtEpochMs = if (refreshError == null) System.currentTimeMillis() else s.lastSyncedAtEpochMs,
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Unexpected repository exceptions must not leave the refresh spinner stuck
                // or unlock duty controls as if the assignment check had succeeded.
                _state.update { s ->
                    s.copy(
                        loading = false,
                        refreshing = false,
                        syncError = failure.message ?: "Could not verify assignments. Refresh and try again.",
                    )
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
                val runQueuedRefresh = refreshRequested
                refreshRequested = false
                refreshJob = null
                if (runQueuedRefresh) refresh()
            }
        }
    }

    fun setDuty(status: DutyStatus) = act { repo.setDutyStatus(status) }

    fun startResponse(incident: Incident, shareLocation: Boolean) {
        act {
            val result = repo.updateIncidentStatus(incident.id, IncidentStatus.EN_ROUTE)
            if (result.isSuccess) {
                _state.update { it.copy(
                    trackingIncidentId = incident.id.takeIf { shareLocation },
                    trackingError = if (shareLocation) null else "Response started without sharing responder location.",
                    lastLocationSentAt = null,
                    lastLocationAccuracyM = null,
                ) }
            }
            result
        }
    }

    fun resumeTracking(incidentId: Long) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    trackingError = null,
                    // A resumed session must not present a previous session's send as current.
                    lastLocationSentAt = null,
                    lastLocationAccuracyM = null,
                )
            }
            val current = repo.myIncidents()
            val me = repo.me()
            val incident = current.getOrNull()?.firstOrNull { it.id == incidentId }
            val ownStatus = incident?.assignedUserStatuses
                ?.firstOrNull { it.userId == me.getOrNull()?.id }?.status ?: incident?.status
            val failure = when {
                current.isFailure -> current.exceptionOrNull()?.message ?: "Could not verify the active assignment."
                me.isFailure -> me.exceptionOrNull()?.message ?: "Could not verify your responder identity."
                ownStatus != IncidentStatus.EN_ROUTE -> "Location sharing is allowed only for your own en-route unit status. Refresh the assignment and try again."
                !location.hasApproximatePermission() -> "Approximate location permission is not available. Location sharing remains off."
                else -> null
            }
            _state.update { it.copy(
                busy = false,
                trackingIncidentId = if (failure == null) incidentId else null,
                trackingError = failure,
            ) }
        }
    }

    fun advance(incident: Incident, to: IncidentStatus) {
        if (to != IncidentStatus.EN_ROUTE) stopTracking("Location sharing stopped as the incident advanced to ${to.label.lowercase()}.")
        act { repo.updateIncidentStatus(incident.id, to) }
    }

    suspend fun collectForegroundLocation(incidentId: Long) {
        location.responderForegroundUpdates().collect { capture ->
            if (_state.value.trackingIncidentId != incidentId) return@collect
            when (capture) {
                is LocationCaptureResult.Success -> {
                    if (!location.isFresh(capture.fix)) {
                        _state.update { it.copy(trackingError = "The location fix expired before sending; waiting for another current fix.") }
                    } else {
                        val result = repo.recordResponderLocation(incidentId, capture.fix)
                        _state.update { it.copy(
                            lastLocationSentAt = if (result.isSuccess) System.currentTimeMillis() else it.lastLocationSentAt,
                            lastLocationAccuracyM = if (result.isSuccess) capture.fix.accuracyM else it.lastLocationAccuracyM,
                            trackingError = result.exceptionOrNull()?.message,
                        ) }
                    }
                }
                LocationCaptureResult.PermissionDenied -> stopTracking("Approximate location permission was denied or withdrawn; no further location will be sent.")
                LocationCaptureResult.ServicesDisabled -> stopTracking("Location services are off. Enable them and resume location sharing.")
                LocationCaptureResult.InvalidFix, LocationCaptureResult.TimedOut, LocationCaptureResult.Unavailable ->
                    stopTracking("A current location is unavailable; no location was sent. Retry location sharing when a fresh fix is available.")
            }
        }
    }

    fun stopTracking(message: String = "Location sharing stopped.") {
        _state.update { it.copy(trackingIncidentId = null, trackingError = message) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun act(call: suspend () -> Result<*>) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = try {
                call()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure<Nothing>(failure)
            }
            _state.update { s ->
                s.copy(
                    busy = false,
                    error = result.exceptionOrNull()?.message
                        ?: if (result.isFailure) "The action failed. Refresh and try again." else null,
                )
            }
            if (result.isSuccess) {
                // refresh() queues a follow-up when an earlier sync is still active.
                // Do not cancel and replace the job: its finally block could clear the
                // reference to the newer job and make overlapping refreshes possible.
                refresh()
            }
        }
    }
}

@Composable
fun ResponderScreen(vm: ResponderViewModel, user: User, onLogout: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    PollWhileStarted { vm.refresh() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var responseToStart by remember { mutableStateOf<Incident?>(null) }
    var permissionActionIncident by remember { mutableStateOf<Incident?>(null) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val incident = permissionActionIncident
        permissionActionIncident = null
        if (incident != null) {
            if (incident.statusFor(user.id) == IncidentStatus.ASSIGNED) vm.startResponse(incident, granted)
            else if (granted) vm.resumeTracking(incident.id)
            else vm.stopTracking("Approximate location permission was denied; location sharing remains off.")
        }
    }
    DisposableEffect(vm, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                vm.stopTracking("Location sharing stopped because the app left the foreground. Resume it explicitly when ready.")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopTracking("Location sharing stopped because the responder screen closed.")
        }
    }
    val trackingIncidentId = state.trackingIncidentId
    LaunchedEffect(vm, lifecycleOwner, trackingIncidentId) {
        if (trackingIncidentId != null) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.collectForegroundLocation(trackingIncidentId)
            }
        }
    }

    fun beginOrResumeWithPermission(incident: Incident) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (incident.statusFor(user.id) == IncidentStatus.ASSIGNED) vm.startResponse(incident, shareLocation = true)
            else vm.resumeTracking(incident.id)
        } else {
            permissionActionIncident = incident
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    val active = state.incidents.filter { it.status.isActive }
    val history = state.incidents.filterNot { it.status.isActive }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(
                title = "Current call",
                subtitle = "",
                onLogout = {
                    vm.stopTracking("Location sharing stopped because you signed out.")
                    onLogout()
                },
            )
        }

        if (state.syncError != null) item { SyncStatus(state.lastSyncedAtEpochMs, state.syncError) }

        item {
            OutlinedButton(
                onClick = vm::refresh,
                enabled = !state.refreshing && !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.refreshing || state.loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text("Refreshing assignments…")
                } else {
                    Text("Refresh assignments")
                }
            }
        }

        item {
            DutyCard(
                current = state.dutyStatus,
                canChange = active.isEmpty() && !state.loading && !state.refreshing && state.syncError == null,
                unavailableMessage = when {
                    state.loading || state.refreshing -> "Checking assignments before allowing duty changes."
                    state.syncError != null -> "Could not verify assignments. Refresh before changing duty status."
                    else -> "Status follows your incident progress."
                },
                busy = state.busy,
                onChange = vm::setDuty,
            )
        }

        state.error?.let { message ->
            item { Banner(message, isError = true, onDismiss = vm::dismissError) }
        }

        if (active.isEmpty() && (state.loading || state.syncError != null || state.dutyStatus == DutyStatus.AVAILABLE)) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                when {
                                    state.loading -> "Checking assignments"
                                    state.syncError != null -> "Assignments unavailable"
                                    else -> "No active assignments"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = if (state.syncError != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                when {
                                    state.loading -> "Please wait."
                                    state.syncError != null -> "Refresh before assuming there are no active calls."
                                    else -> "You're available for new calls."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        items(active, key = { it.id }) { incident ->
            IncidentCard(incident) {
                Text(
                    "Your unit: ${incident.statusFor(user.id).label}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val contact = listOfNotNull(incident.requesterName, incident.requesterPhone)
                    .joinToString(", ")
                if (contact.isNotBlank()) Text("Requester: $contact")

                val latitude = incident.latitude
                val longitude = incident.longitude
                if (
                    latitude != null && longitude != null &&
                    latitude.isFinite() && longitude.isFinite() &&
                    latitude in -90.0..90.0 && longitude in -180.0..180.0
                ) {
                    OutlinedButton(
                        onClick = { openNavigation(context, latitude, longitude) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open navigation app")
                    }
                } else {
                    Text(
                        "No valid map pin. Use the landmark and confirm the destination with dispatch.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                incident.statusFor(user.id).nextSteps().forEach { next ->
                    Button(
                        onClick = {
                            if (next == IncidentStatus.EN_ROUTE) {
                                responseToStart = incident
                            } else {
                                vm.advance(incident, next)
                            }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(next.actionLabel())
                    }
                }
                if (incident.statusFor(user.id) == IncidentStatus.EN_ROUTE) {
                    val sharing = state.trackingIncidentId == incident.id
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val lastLocationSentAt = state.lastLocationSentAt
                            Text(
                                when {
                                    !sharing -> "Location sharing is off."
                                    lastLocationSentAt == null && state.trackingError != null ->
                                        "Waiting for the first confirmed location update."
                                    lastLocationSentAt == null ->
                                        "Waiting for location…"
                                    state.trackingError != null ->
                                        "Last location update failed."
                                    else ->
                                        "Sharing approximate location while this app is open."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (sharing && lastLocationSentAt != null) {
                                val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                                    .format(java.util.Date(lastLocationSentAt))
                                Text(
                                    "Last sent $time${state.lastLocationAccuracyM?.let { " · reported accuracy ±${it.toInt()} m" } ?: " · accuracy unavailable"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            state.trackingError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            if (sharing) {
                                OutlinedButton(onClick = { vm.stopTracking("Location sharing stopped by you.") }, enabled = !state.busy) {
                                    Text("Stop location sharing")
                                }
                            } else {
                                Button(onClick = { responseToStart = incident }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                    Text("Resume location sharing")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            item { SectionHeading("Completed") }
            items(history, key = { it.id }) { incident ->
                IncidentCard(incident)
            }
        }
    }

    responseToStart?.let { incident ->
        val startingResponse = incident.statusFor(user.id) == IncidentStatus.ASSIGNED
        AlertDialog(
            onDismissRequest = { if (!state.busy) responseToStart = null },
            title = { Text(if (startingResponse) "Start response?" else "Resume location sharing?") },
            text = {
                Text(
                    if (startingResponse) {
                        "Share approximate location with authorized dispatch while TACResQ is open. Sharing stops when you leave this screen. You can start without sharing."
                    } else {
                        "Share approximate location with authorized dispatch while TACResQ is open. Sharing stops when you leave this screen."
                    },
                )
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            responseToStart = null
                            beginOrResumeWithPermission(incident)
                        },
                        enabled = !state.busy,
                    ) { Text(if (startingResponse) "Share location & start" else "Resume sharing") }
                    if (startingResponse) {
                        TextButton(
                            onClick = {
                                responseToStart = null
                                vm.startResponse(incident, shareLocation = false)
                            },
                            enabled = !state.busy,
                        ) { Text("Start without sharing") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { responseToStart = null }, enabled = !state.busy) { Text("Go back") }
            },
        )
    }
}

@Composable
private fun DutyCard(
    current: DutyStatus,
    canChange: Boolean,
    unavailableMessage: String,
    busy: Boolean,
    onChange: (DutyStatus) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Duty status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusPill(
                    current.label,
                    if (current == DutyStatus.AVAILABLE) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canChange) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = current == DutyStatus.AVAILABLE,
                        onClick = { onChange(DutyStatus.AVAILABLE) },
                        enabled = !busy,
                        label = { Text("Available") },
                    )
                    FilterChip(
                        selected = current == DutyStatus.OFF_DUTY,
                        onClick = { onChange(DutyStatus.OFF_DUTY) },
                        enabled = !busy,
                        label = { Text("Off duty") },
                    )
                }
            } else {
                Text(unavailableMessage, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Opens turn-by-turn navigation only for a valid coordinate pair. */
private fun openNavigation(context: Context, lat: Double, lng: Double) {
    // Defense in depth: do not launch an external maps app with malformed API data.
    if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
        Toast.makeText(
            context,
            "Incident coordinates are invalid. Confirm the destination with dispatch before navigating.",
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    val navigate = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=d"))
        .setPackage("com.google.android.apps.maps")
    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))
    try {
        context.startActivity(navigate)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(fallback)
        } catch (e2: ActivityNotFoundException) {
            Toast.makeText(context, "No maps app found on this phone.", Toast.LENGTH_SHORT).show()
        }
    }
}
