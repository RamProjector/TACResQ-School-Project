package com.tacresq.app.ui.responder

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.User
import com.tacresq.app.data.model.nextSteps
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.ui.common.Banner
import com.tacresq.app.ui.common.IncidentCard
import com.tacresq.app.ui.common.PollWhileStarted
import com.tacresq.app.ui.common.ScreenHeader
import com.tacresq.app.ui.common.actionLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResponderUiState(
    val incidents: List<Incident> = emptyList(),
    val dutyStatus: DutyStatus = DutyStatus.OFF_DUTY,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Used for both the Field Responder and Driver roles. */
class ResponderViewModel(private val repo: TacRepository) : ViewModel() {
    private val _state = MutableStateFlow(ResponderUiState())
    val state: StateFlow<ResponderUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val me = repo.me()
            val mine = repo.myIncidents()
            _state.update { s ->
                s.copy(
                    loading = false,
                    dutyStatus = me.getOrNull()?.dutyStatus ?: s.dutyStatus,
                    incidents = mine.getOrNull() ?: s.incidents,
                    error = (me.exceptionOrNull() ?: mine.exceptionOrNull())?.message,
                )
            }
        }
    }

    fun setDuty(status: DutyStatus) = act { repo.setDutyStatus(status) }

    fun advance(incident: Incident, to: IncidentStatus) =
        act { repo.updateIncidentStatus(incident.id, to) }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun act(call: suspend () -> Result<*>) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = call()
            _state.update { it.copy(busy = false, error = result.exceptionOrNull()?.message) }
            if (result.isSuccess) {
                refreshJob?.cancel()
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

    val active = state.incidents.filter { it.status.isActive }
    val history = state.incidents.filterNot { it.status.isActive }

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

        item {
            DutyCard(
                current = state.dutyStatus,
                canChange = active.isEmpty(),
                busy = state.busy,
                onChange = vm::setDuty,
            )
        }

        state.error?.let { message ->
            item { Banner(message, isError = true, onDismiss = vm::dismissError) }
        }

        if (active.isEmpty()) {
            item {
                Text(
                    if (state.dutyStatus == DutyStatus.AVAILABLE) {
                        "Waiting for dispatch. New assignments appear here."
                    } else {
                        "Set yourself to Available to receive assignments."
                    }
                )
            }
        }

        items(active, key = { it.id }) { incident ->
            IncidentCard(incident) {
                val contact = listOfNotNull(incident.requesterName, incident.requesterPhone)
                    .joinToString(", ")
                if (contact.isNotBlank()) Text("Requester: $contact")

                OutlinedButton(
                    onClick = { openNavigation(context, incident.latitude, incident.longitude) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Navigate in Google Maps")
                }

                incident.status.nextSteps().forEach { next ->
                    Button(
                        onClick = { vm.advance(incident, next) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(next.actionLabel())
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            item { Text("Completed", style = MaterialTheme.typography.titleMedium) }
            items(history, key = { it.id }) { incident ->
                IncidentCard(incident)
            }
        }
    }
}

@Composable
private fun DutyCard(
    current: DutyStatus,
    canChange: Boolean,
    busy: Boolean,
    onChange: (DutyStatus) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Duty status", style = MaterialTheme.typography.titleMedium)
            Text(current.label, style = MaterialTheme.typography.headlineSmall)
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
                Text("Your status updates automatically as you move the incident forward.")
            }
        }
    }
}

/** Opens turn-by-turn navigation in Google Maps, or any maps app as a fallback. */
private fun openNavigation(context: Context, lat: Double, lng: Double) {
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
