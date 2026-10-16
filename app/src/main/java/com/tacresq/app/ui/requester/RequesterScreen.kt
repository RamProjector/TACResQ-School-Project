package com.tacresq.app.ui.requester

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentType
import com.tacresq.app.data.model.User
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.location.LocationProvider
import com.tacresq.app.ui.common.Banner
import com.tacresq.app.ui.common.IncidentCard
import com.tacresq.app.ui.common.PollWhileStarted
import com.tacresq.app.ui.common.ScreenHeader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RequesterUiState(
    val incidents: List<Incident> = emptyList(),
    val loading: Boolean = true,
    val sending: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

class RequesterViewModel(
    private val repo: TacRepository,
    private val location: LocationProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(RequesterUiState())
    val state: StateFlow<RequesterUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    fun hasLocationPermission(): Boolean = location.hasPermission()

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            repo.myIncidents()
                .onSuccess { list -> _state.update { it.copy(incidents = list, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun sendSos(type: IncidentType, description: String) {
        if (_state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, notice = null, error = null) }
            val fix = location.current()
            if (fix == null) {
                _state.update {
                    it.copy(sending = false, error = "Couldn't get a recent location. Turn on GPS, wait for a fix, and try again.")
                }
                return@launch
            }
            val note = description.trim().ifBlank { null }
            repo.createIncident(type, note, fix)
                .onSuccess { created ->
                    _state.update {
                        it.copy(
                            sending = false,
                            notice = "Help request sent. Dispatch has your location.",
                            incidents = listOf(created) + it.incidents,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(sending = false, error = e.message) } }
        }
    }

    fun onLocationDenied() {
        _state.update {
            it.copy(error = "Location permission is needed so responders can find you. Allow it and try again.")
        }
    }

    fun dismissMessages() {
        _state.update { it.copy(notice = null, error = null) }
    }
}

@Composable
fun RequesterScreen(vm: RequesterViewModel, user: User, onLogout: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    PollWhileStarted { vm.refresh() }

    var selectedType by rememberSaveable { mutableStateOf(IncidentType.MEDICAL) }
    var description by rememberSaveable { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) vm.sendSos(selectedType, description) else vm.onLocationDenied()
    }

    // Clear the text box once a request has gone through.
    LaunchedEffect(state.notice) {
        if (state.notice != null) description = ""
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

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Need help?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("Choose the emergency type. Your GPS location is sent with the request.")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(IncidentType.entries) { type ->
                            FilterChip(
                                selected = type == selectedType,
                                onClick = { selectedType = type },
                                label = { Text(type.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("What is happening? (optional)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            if (vm.hasLocationPermission()) {
                                vm.sendSos(selectedType, description)
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    )
                                )
                            }
                        },
                        enabled = !state.sending,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) {
                        if (state.sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            Text("Send SOS", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        state.notice?.let { message ->
            item { Banner(message, isError = false, onDismiss = vm::dismissMessages) }
        }
        state.error?.let { message ->
            item { Banner(message, isError = true, onDismiss = vm::dismissMessages) }
        }

        item { Text("My requests", style = MaterialTheme.typography.titleMedium) }
        if (state.incidents.isEmpty() && !state.loading) {
            item { Text("No requests yet.") }
        }
        items(state.incidents, key = { it.id }) { incident ->
            IncidentCard(incident)
        }
    }
}
