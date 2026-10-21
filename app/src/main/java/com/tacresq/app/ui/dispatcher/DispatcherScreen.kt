package com.tacresq.app.ui.dispatcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DispatcherUiState(
    val incidents: List<Incident> = emptyList(),
    val units: List<User> = emptyList(),
    val responderLocations: List<ResponderLocation> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val syncError: String? = null,
    val lastSyncedAtEpochMs: Long? = null,
)

class DispatcherViewModel(private val repo: TacRepository) : ViewModel() {
    private val _state = MutableStateFlow(DispatcherUiState())
    val state: StateFlow<DispatcherUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true) return
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
                    lastSyncedAtEpochMs = if (refreshError == null) System.currentTimeMillis() else current.lastSyncedAtEpochMs,
                )
            }
        }
    }

    fun assign(incident: Incident, unit: User) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            repo.assignIncident(incident.id, unit.id)
                .onSuccess { updated ->
                    _state.update { state ->
                        state.copy(
                            busy = false,
                            notice = "Incident #${updated.id} assigned to ${unit.name}.",
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
fun DispatcherScreen(vm: DispatcherViewModel, user: User, onLogout: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    PollWhileStarted { vm.refresh() }
    var assignmentCandidate by remember { mutableStateOf<Pair<Incident, User>?>(null) }

    val pending = state.incidents.filter { it.status == IncidentStatus.PENDING }
    val active = state.incidents.count { it.status.isActive }
    val availableUnits = state.units.filter { it.dutyStatus == DutyStatus.AVAILABLE }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Dispatcher console",
                subtitle = "${user.name} • ${user.role.label}",
                onLogout = onLogout,
            )
        }
        item { SyncStatus(state.lastSyncedAtEpochMs, state.syncError) }
        item {
            OutlinedButton(onClick = vm::refresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh queue")
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
        if (pending.isEmpty() && !state.loading) {
            item { Text("No pending incidents.") }
        }
        items(pending, key = { it.id }) { incident ->
            IncidentCard(incident) {
                Text("Assign an available unit:", fontWeight = FontWeight.SemiBold)
                if (availableUnits.isEmpty()) {
                    Text("No responder or driver is currently marked Available.")
                } else {
                    availableUnits.forEach { unit ->
                        Button(
                            onClick = { assignmentCandidate = incident to unit },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Assign ${unit.name} (${unit.role.label})")
                        }
                    }
                }
            }
        }

        item { Text("All incidents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(state.incidents.filter { it.status != IncidentStatus.PENDING }, key = { it.id }) { incident ->
            IncidentCard(incident) {
                val assignedName = state.units.firstOrNull { it.id == incident.assignedTo }?.name
                Text("Assigned unit: ${assignedName ?: "Not assigned"}")
            }
        }

        item { Text("Field units", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(state.units, key = { it.id }) { unit -> UnitCard(unit) }
    }

    assignmentCandidate?.let { (incident, unit) ->
        AlertDialog(
            onDismissRequest = { assignmentCandidate = null },
            title = { Text("Confirm assignment") },
            text = { Text("Assign incident #${incident.id} to ${unit.name}? This will move the incident to Unit assigned.") },
            confirmButton = {
                TextButton(onClick = {
                    assignmentCandidate = null
                    vm.assign(incident, unit)
                }) { Text("Assign unit") }
            },
            dismissButton = {
                TextButton(onClick = { assignmentCandidate = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SummaryCard(total: Int, pending: Int, active: Int, available: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(unit.name, fontWeight = FontWeight.SemiBold)
            Text(unit.role.label)
            Text("Status: ${unit.dutyStatus?.label ?: "Not applicable"}")
            unit.phone?.let { Text("Phone: $it") }
        }
    }
}
