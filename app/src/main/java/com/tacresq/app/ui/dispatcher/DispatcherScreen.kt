package com.tacresq.app.ui.dispatcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.ResponderLocation
import com.tacresq.app.data.model.User
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.ui.common.Banner
import com.tacresq.app.ui.common.IncidentCard
import com.tacresq.app.ui.common.PollWhileStarted
import com.tacresq.app.ui.common.ScreenHeader
import com.tacresq.app.ui.common.SyncStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

data class DispatcherUiState(
    val incidents: List<Incident> = emptyList(),
    val units: List<User> = emptyList(),
    val responderLocations: List<ResponderLocation> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val syncError: String? = null,
    // Assignment is safe only when queue and unit availability are fresh.
    val assignmentDataFresh: Boolean = false,
    val lastSyncedAtEpochMs: Long? = null,
)

class DispatcherViewModel(private val repo: TacRepository) : ViewModel() {
    private val _state = MutableStateFlow(DispatcherUiState())
    val state: StateFlow<DispatcherUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true) return
        _state.update { it.copy(loading = true, assignmentDataFresh = false) }
        refreshJob = viewModelScope.launch {
            val incidents = repo.allIncidents()
            val units = repo.units()
            val responderLocations = repo.activeResponderLocations()
            val refreshError = incidents.exceptionOrNull() ?: units.exceptionOrNull() ?: responderLocations.exceptionOrNull()
            _state.update { current ->
                current.copy(
                    incidents = incidents.getOrNull() ?: current.incidents,
                    units = units.getOrNull() ?: current.units,
                    responderLocations = responderLocations.getOrNull() ?: current.responderLocations,
                    loading = false,
                    syncError = refreshError?.message,
                    assignmentDataFresh = incidents.isSuccess && units.isSuccess,
                    lastSyncedAtEpochMs = if (refreshError == null) System.currentTimeMillis() else current.lastSyncedAtEpochMs,
                )
            }
        }
    }

    fun assign(incident: Incident, userIds: List<Long>) {
        if (_state.value.busy || !_state.value.assignmentDataFresh || userIds.isEmpty() || userIds.size > 5) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            repo.assignIncident(incident.id, userIds)
                .onSuccess { updated ->
                    val names = _state.value.units.filter { it.id in userIds }.joinToString(", ") { it.name }
                    _state.update { state ->
                        state.copy(
                            busy = false,
                            notice = "Incident #${updated.id} assigned to ${names.ifBlank { "the selected response team" }}.",
                            incidents = state.incidents.map { if (it.id == updated.id) updated else it },
                        )
                    }
                    refreshJob?.cancel()
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    fun dismissMessages() = _state.update { it.copy(notice = null, error = null) }
}

@Composable
fun DispatcherScreen(vm: DispatcherViewModel, user: User, onLogout: () -> Unit, focusedIncidentId: Long? = null, onOpenChat: (Long) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    PollWhileStarted { vm.refresh() }
    var assignmentCandidate by remember { mutableStateOf<Incident?>(null) }
    var selectedUnitIds by remember { mutableStateOf(setOf<Long>()) }
    var queueClock by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            queueClock = Instant.now()
        }
    }

    // Oldest known submissions stay at the top. Missing/invalid timestamps are surfaced
    // first with an explicit warning rather than silently treating them as recent.
    val pending = state.incidents
        .filter { it.status == IncidentStatus.PENDING }
        .sortedWith(compareBy<Incident> { incident ->
            incident.createdAt?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() } ?: Instant.MIN
        }.thenBy { it.id })
    val active = state.incidents.count { it.status.isActive }
    val availableUnits = state.units.filter { it.dutyStatus == DutyStatus.AVAILABLE }
    val focusedIncident = focusedIncidentId?.let { targetId -> state.incidents.firstOrNull { it.id == targetId } }
    // The dialog must use the latest queue snapshot, not the Incident object captured
    // when it opened. A refresh can change assignment state while the dialog is visible.
    val currentAssignmentCandidate = assignmentCandidate?.let { candidate ->
        state.incidents.firstOrNull { current ->
            current.id == candidate.id && current.status in listOf(
                IncidentStatus.PENDING, IncidentStatus.ASSIGNED, IncidentStatus.EN_ROUTE,
            )
        }
    }
    LaunchedEffect(state.assignmentDataFresh, availableUnits) {
        if (state.assignmentDataFresh) {
            val currentlyAvailableIds = availableUnits.map { it.id }.toSet()
            selectedUnitIds = selectedUnitIds.intersect(currentlyAvailableIds)
        }
    }
    val existingTeamCount = currentAssignmentCandidate?.let { candidate ->
        if (candidate.status == IncidentStatus.PENDING) 0
        else candidate.assignedUserIds.ifEmpty { listOfNotNull(candidate.assignedTo) }.distinct().size
    } ?: 0
    val maxTeamSelection = (5 - existingTeamCount).coerceAtLeast(0)

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(
                title = "Dispatch queue",
                subtitle = "Oldest emergency requests first · verify availability before assigning.",
                onLogout = onLogout,
            )
        }
        if (focusedIncidentId != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Opened from alert", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    when {
                        focusedIncident != null -> IncidentCard(focusedIncident) {
                            TextButton(onClick = { onOpenChat(focusedIncident.id) }) { Text("Open conversation") }
                        }
                        state.loading -> Text("Loading the incident from the current dispatch queue…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text(
                            "This incident is not in the current authorized dispatch queue. Refresh before acting on an older alert.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (state.syncError != null) item { SyncStatus(state.lastSyncedAtEpochMs, state.syncError) }
        if (!state.loading && !state.assignmentDataFresh) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Assignment paused", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        Text(
                            "The latest incident queue or field-unit availability could not be confirmed. Refresh successfully before assigning or adding responders; the information currently shown may be out of date.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = vm::refresh,
                enabled = !state.busy && !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Refreshing queue…")
                } else {
                    Text("Refresh queue")
                }
            }
        }
        item {
            SummaryCard(
                total = state.incidents.size,
                pending = pending.size,
                active = active,
                available = availableUnits.size,
            )
        }
        state.notice?.let { item { Banner(it, isError = false, onDismiss = vm::dismissMessages) } }
        state.error?.let { item { Banner(it, isError = true, onDismiss = vm::dismissMessages) } }

        item { Text("Pending incidents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (state.loading && state.incidents.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Loading incident queue", fontWeight = FontWeight.SemiBold)
                            Text("Fetching incidents and field-unit availability.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else if (pending.isEmpty()) {
            item {
                Text(
                    if (state.syncError != null && state.incidents.isEmpty())
                        "The queue could not be loaded. Check your connection and refresh to try again."
                    else "No pending incidents.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(pending, key = { it.id }) { incident ->
            IncidentCard(incident) {
                val waitSeconds = waitingAgeSeconds(incident.createdAt, queueClock)
                Text(
                    waitingAgeLabel(incident.createdAt, queueClock),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (waitSeconds != null && waitSeconds >= 15 * 60)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (waitSeconds != null && waitSeconds >= 15 * 60)
                        FontWeight.SemiBold else FontWeight.Normal,
                )
                Text("Build a response team from available field units.", fontWeight = FontWeight.SemiBold)
                if (availableUnits.isEmpty()) {
                    Text("No responder or driver is currently marked Available.")
                } else {
                    OutlinedButton(
                        onClick = {
                            selectedUnitIds = emptySet()
                            assignmentCandidate = incident
                        },
                        enabled = !state.busy && state.assignmentDataFresh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Select response team")
                    }
                }
                TextButton(onClick = { onOpenChat(incident.id) }) { Text("Open conversation") }
            }
        }

        item { Text("All incidents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(state.incidents.filter { it.status != IncidentStatus.PENDING }, key = { it.id }) { incident ->
            IncidentCard(incident) {
                val teamIds = incident.assignedUserIds.ifEmpty { listOfNotNull(incident.assignedTo) }
                val teamNames = state.units.filter { it.id in teamIds }.joinToString(", ") { it.name }
                val remainingTeamSlots = (5 - teamIds.distinct().size).coerceAtLeast(0)
                Text("Assigned team: ${teamNames.ifBlank { "Not assigned" }}")
                incident.assignedUserStatuses.forEach { memberStatus ->
                    val unit = state.units.firstOrNull { it.id == memberStatus.userId }
                    Text(
                        "${unit?.name ?: "Unit #${memberStatus.userId}"} · ${memberStatus.status.label}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (incident.status == IncidentStatus.ASSIGNED || incident.status == IncidentStatus.EN_ROUTE) {
                    if (remainingTeamSlots == 0) {
                        Text("Response team is full (5 units).")
                    } else if (availableUnits.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                selectedUnitIds = emptySet()
                                assignmentCandidate = incident
                            },
                            enabled = !state.busy && state.assignmentDataFresh,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add responders") }
                    }
                }
                TextButton(onClick = { onOpenChat(incident.id) }) { Text("Open conversation") }
            }
        }

        item { Text("Field units", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (state.loading && state.units.isEmpty()) {
            item { Text("Loading field-unit availability…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (state.units.isEmpty()) {
            item {
                Text(
                    if (state.syncError != null) "Field-unit availability is unavailable. Refresh after checking your connection."
                    else "No field units are available to display.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.units, key = { it.id }) { unit -> UnitCard(unit) }
    }

    currentAssignmentCandidate?.let { incident ->
        AlertDialog(
            onDismissRequest = { assignmentCandidate = null; selectedUnitIds = emptySet() },
            title = { Text("Build response team") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (incident.status == IncidentStatus.PENDING)
                            "Choose 1–5 available responders or drivers for incident #${incident.id}. Assignments are submitted together."
                        else
                            "Choose up to ${maxTeamSelection} additional available responders or drivers. New members can be added only before the incident reaches the scene."
                    )
                    availableUnits.forEach { unit ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = unit.id in selectedUnitIds,
                                enabled = !state.busy || unit.id in selectedUnitIds,
                                onCheckedChange = { checked ->
                                    selectedUnitIds = if (checked) {
                                        if (selectedUnitIds.size < maxTeamSelection) selectedUnitIds + unit.id else selectedUnitIds
                                    } else selectedUnitIds - unit.id
                                },
                            )
                            Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
                                Text(unit.name, fontWeight = FontWeight.SemiBold)
                                Text("${unit.role.label} · ${unit.dutyStatus?.label ?: "Status unavailable"}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text("${selectedUnitIds.size} of ${maxTeamSelection} available slots selected", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.busy && state.assignmentDataFresh && selectedUnitIds.isNotEmpty() && selectedUnitIds.size <= maxTeamSelection,
                    onClick = {
                        val ids = selectedUnitIds.toList()
                        assignmentCandidate = null
                        selectedUnitIds = emptySet()
                        vm.assign(incident, ids)
                    },
                ) { Text(if (state.busy) "Assigning…" else "Assign team") }
            },
            dismissButton = {
                TextButton(onClick = { assignmentCandidate = null; selectedUnitIds = emptySet() }, enabled = !state.busy) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SummaryCard(total: Int, pending: Int, active: Int, available: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Operational summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Incidents: $total")
                Text("Pending: $pending")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Active: $active")
                Text("Available units: $available")
            }
        }
    }
}

@Composable
private fun UnitCard(unit: User) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(unit.name, fontWeight = FontWeight.SemiBold)
            Text(unit.role.label)
            Text("Status: ${unit.dutyStatus?.label ?: "Not applicable"}")
            unit.phone?.let { Text("Phone: $it") }
        }
    }
}


private fun waitingAgeSeconds(createdAt: String?, now: Instant): Long? {
    val submittedAt = createdAt?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() } ?: return null
    val age = Duration.between(submittedAt, now).seconds
    return age.takeIf { it >= 0L }
}

private fun waitingAgeLabel(createdAt: String?, now: Instant): String {
    val submittedAt = createdAt?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
        ?: return "Waiting time unavailable — verify submission time."
    val seconds = Duration.between(submittedAt, now).seconds
    if (seconds < 0L) return "Submission time is in the future — verify timestamp."
    return when {
        seconds < 60L -> "Waiting ${seconds}s"
        seconds < 3_600L -> "Waiting ${seconds / 60L}m"
        seconds < 86_400L -> "Waiting ${seconds / 3_600L}h ${seconds % 3_600L / 60L}m"
        else -> "Waiting ${seconds / 86_400L}d ${seconds % 86_400L / 3_600L}h"
    }
}
