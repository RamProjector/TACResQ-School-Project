package com.tacresq.app.ui.responder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.User
import com.tacresq.app.data.model.nextSteps
import com.tacresq.app.data.model.statusFor
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.location.LocationProvider
import com.tacresq.app.location.ResponderLocationTrackingController
import com.tacresq.app.ui.common.Banner
import com.tacresq.app.ui.common.IncidentCard
import com.tacresq.app.ui.common.PollWhileStarted
import com.tacresq.app.ui.common.ScreenHeader
import com.tacresq.app.ui.common.SyncStatus
import com.tacresq.app.ui.common.SectionHeading
import com.tacresq.app.ui.common.StatusPill
import com.tacresq.app.ui.common.actionLabel
import com.tacresq.app.ui.common.pillColor
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
    private val tracking: ResponderLocationTrackingController,
) : ViewModel() {
    private val _state = MutableStateFlow(ResponderUiState())
    val state: StateFlow<ResponderUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var refreshRequested = false

    init {
        viewModelScope.launch {
            tracking.status.collect { status ->
                _state.update {
                    it.copy(
                        trackingIncidentId = status.incidentId,
                        trackingError = status.message,
                        lastLocationSentAt = status.lastSentAtEpochMillis,
                        lastLocationAccuracyM = status.lastAccuracyMeters,
                    )
                }
            }
        }
    }

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
                val serverIncidents = mine.getOrNull()
                val trackingId = tracking.status.value.incidentId
                val trackingEnded = trackingId != null && serverIncidents != null &&
                    (currentUserId == null || serverIncidents.none {
                        it.id == trackingId && it.statusFor(currentUserId) == IncidentStatus.EN_ROUTE
                    })
                if (trackingEnded) {
                    tracking.stop("Location sharing stopped because this incident is no longer assigned en route.")
                }
                _state.update { s ->
                    // The aggregate can stay en-route while another unit is still travelling.
                    // Stop only when this device's own assignment is no longer en route.
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
                if (shareLocation) {
                    tracking.start(incident.id).onFailure { failure ->
                        _state.update {
                            it.copy(
                                trackingIncidentId = null,
                                trackingError = failure.message ?: "Location sharing could not start. Resume it explicitly.",
                            )
                        }
                    }
                } else {
                    tracking.stop("Response started without sharing responder location.")
                }
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
                trackingError = failure,
            ) }
            if (failure == null) {
                tracking.start(incidentId).onFailure { startFailure ->
                    _state.update {
                        it.copy(
                            trackingIncidentId = null,
                            trackingError = startFailure.message ?: "Location sharing could not start. Resume it explicitly.",
                        )
                    }
                }
            }
        }
    }

    fun advance(incident: Incident, to: IncidentStatus) {
        if (to != IncidentStatus.EN_ROUTE) stopTracking("Location sharing stopped as the incident advanced to ${to.label.lowercase()}.")
        act { repo.updateIncidentStatus(incident.id, to) }
    }

    fun stopTracking(message: String = "Location sharing stopped.") {
        tracking.stop(message)
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
fun ResponderScreen(
    vm: ResponderViewModel,
    user: User,
    onLogout: () -> Unit,
    onOpenNotifications: () -> Unit = {},
    onOpenMap: (Long) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    PollWhileStarted { vm.refresh() }
    val context = LocalContext.current
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
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val incident = permissionActionIncident
        if (incident != null && granted) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                permissionActionIncident = null
                if (incident.statusFor(user.id) == IncidentStatus.ASSIGNED) vm.startResponse(incident, shareLocation = true)
                else vm.resumeTracking(incident.id)
            } else {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        } else if (incident != null) {
            permissionActionIncident = null
            if (incident.statusFor(user.id) == IncidentStatus.ASSIGNED) vm.startResponse(incident, shareLocation = false)
            else vm.stopTracking("Notifications are required to show the active location-sharing service.")
        }
    }

    fun beginOrResumeWithPermission(incident: Incident) {
        permissionActionIncident = incident
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            permissionActionIncident = null
            if (incident.statusFor(user.id) == IncidentStatus.ASSIGNED) vm.startResponse(incident, shareLocation = true)
            else vm.resumeTracking(incident.id)
        } else {
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
                subtitle = "Review assignments, update your status, and manage location sharing.",
                onOpenNotifications = onOpenNotifications,
            )
        }

        if (state.syncError != null) item { SyncStatus(state.lastSyncedAtEpochMs, state.syncError) }

        item {
            OutlinedButton(
                onClick = vm::refresh,
                enabled = !state.refreshing && !state.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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

        // A failed sync is already explained by SyncStatus and the disabled DutyCard.
        // Do not render a second "Assignments unavailable" card for the same failure.
        if (active.isEmpty() && (state.loading || (state.syncError == null && state.dutyStatus == DutyStatus.AVAILABLE))) {
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
                                if (state.loading) "Checking assignments" else "No active assignments",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                if (state.loading) "Please wait." else "You're available for new calls.",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Your response", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val ownStatus = incident.statusFor(user.id)
                    StatusPill(ownStatus.label, ownStatus.pillColor())
                }
                Text(
                    "Your status updates are shared with dispatch and the requester. The overall request status reflects the assigned team's progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Text(
                        "This preview sends your approximate current location and this incident's location to TACResQ's configured routing service (Geoapify when enabled). If it fails, no second provider is contacted; you can choose external Maps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { onOpenMap(incident.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text("Open in-app route preview")
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
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text(next.actionLabel())
                    }
                }
                if (incident.statusFor(user.id) == IncidentStatus.EN_ROUTE) {
                    val sharing = state.trackingIncidentId == incident.id
                    val trackingStatusLabel = when {
                        state.trackingError != null -> "Needs attention"
                        sharing && state.lastLocationSentAt == null -> "Starting"
                        sharing -> "Enabled"
                        else -> "Off"
                    }
                    val trackingTint = when {
                        state.trackingError != null -> MaterialTheme.colorScheme.error
                        sharing -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(
                            1.dp,
                            when {
                                state.trackingError != null -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                                sharing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                else -> MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = when {
                                state.trackingError != null -> MaterialTheme.colorScheme.errorContainer
                                sharing -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "Location sharing",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                StatusPill(trackingStatusLabel, trackingTint)
                            }
                            val lastLocationSentAt = state.lastLocationSentAt
                            Text(
                                "GPS sharing reports your location; it does not confirm arrival. Update your response status when you reach the scene.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                                        "Sharing approximate location for this en-route response, including when TACResQ is in the background."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (sharing && lastLocationSentAt != null) {
                                val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                                    .format(java.util.Date(lastLocationSentAt))
                                Text(
                                    "Last sent $time${state.lastLocationAccuracyM?.let { " · reported accuracy ±${it.toInt()} m" } ?: " · accuracy unavailable"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            state.trackingError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (sharing) {
                                OutlinedButton(onClick = { vm.stopTracking("Location sharing stopped by you.") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text("Stop location sharing")
                                }
                            } else {
                                Button(onClick = { responseToStart = incident }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
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
                        "With your permission, TACResQ will share approximate location with authorized dispatch for this assignment while your response is en route, including when you switch apps or lock your screen. A persistent notification shows sharing is active and lets you stop it. Updates can be delayed or unavailable; sharing stops when the assignment advances or you sign out. You can start without sharing."
                    } else {
                        "With your permission, TACResQ will resume sharing approximate location with authorized dispatch for this en-route assignment, including when you switch apps or lock your screen. A persistent notification shows sharing is active and lets you stop it. Updates can be delayed or unavailable; sharing stops when the assignment advances or you sign out."
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
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
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
