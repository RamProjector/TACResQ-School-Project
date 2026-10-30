package com.tacresq.app.ui.requester

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.locationbutton.compose.LocationButton
import androidx.core.locationbutton.compose.LocationButtonTextType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tacresq.app.data.model.GeoFix
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentType
import com.tacresq.app.data.model.User
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.location.LocationCaptureResult
import com.tacresq.app.location.LocationProvider
import com.tacresq.app.location.LocationSettingsCheck
import com.tacresq.app.ui.common.Banner
import com.tacresq.app.ui.common.IncidentCard
import com.tacresq.app.ui.common.PollWhileStarted
import com.tacresq.app.ui.common.ScreenHeader
import com.tacresq.app.ui.common.SectionHeading
import com.tacresq.app.ui.common.StatusPill
import com.tacresq.app.ui.common.pillColor
import com.tacresq.app.ui.common.SyncStatus
import com.tacresq.app.ui.common.formatTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.io.File
import java.util.Locale
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import kotlin.math.abs
import kotlin.math.ceil

data class RequesterUiState(
    val incidents: List<Incident> = emptyList(),
    val loading: Boolean = true,
    val sending: Boolean = false,
    val pendingSosAvailable: Boolean = false,
    val pendingSosCheckInProgress: Boolean = true,
    val pendingSosCheckError: String? = null,
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
    private var pendingSosCheckJob: Job? = null

    fun refreshPendingSos() {
        if (pendingSosCheckJob?.isActive == true) return
        _state.update {
            it.copy(pendingSosCheckInProgress = true, pendingSosCheckError = null)
        }
        pendingSosCheckJob = viewModelScope.launch {
            try {
                val pending = repo.hasPendingIncident()
                _state.update {
                    it.copy(
                        pendingSosAvailable = pending,
                        pendingSosCheckInProgress = false,
                        pendingSosCheckError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        pendingSosCheckInProgress = false,
                        pendingSosCheckError = "TACResQ couldn't verify whether an earlier SOS is still awaiting confirmation. Check again before creating another request.",
                    )
                }
            }
        }
    }

    fun retryPendingSos() {
        if (_state.value.sending) return
        _state.update { it.copy(sending = true, error = null, notice = null) }
        viewModelScope.launch {
            repo.retryPendingIncident()
                .onSuccess { created ->
                    _state.update {
                        it.copy(
                            sending = false,
                            pendingSosAvailable = false,
                            notice = "The previously saved SOS was confirmed by the server. Review the request below and contact dispatch directly if the emergency is still active.",
                            incidents = listOf(created) + it.incidents.filterNot { existing -> existing.id == created.id },
                        )
                    }
                    refresh()
                }
                .onFailure { e ->
                    _state.update { it.copy(sending = false, error = e.message ?: "The saved SOS is still unresolved. Do not create a replacement request.") }
                    refreshPendingSos()
                }
        }
    }

    fun isFresh(fix: GeoFix): Boolean = location.isFresh(fix)

    suspend fun checkLocationSettings() = location.checkLocationSettings()

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

    fun setManualLocation(latitude: Double, longitude: Double) {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0) {
            showLocationError("The selected map pin is invalid. Move the pin and try again.")
            return
        }
        _state.update {
            it.copy(
                locationFix = GeoFix(
                    latitude = latitude,
                    longitude = longitude,
                    accuracyM = null,
                    capturedAtEpochMillis = System.currentTimeMillis(),
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    hasPrecisePermission = false,
                    manuallySelected = true,
                ),
                locationError = null,
                error = null,
            )
        }
    }

    fun clearLocationFix() {
        _state.update { it.copy(locationFix = null, locationError = null) }
    }

    fun expireStaleLocation() {
        val fix = _state.value.locationFix ?: return
        if (!location.isFresh(fix)) {
            _state.update {
                it.copy(
                    locationFix = null,
                    locationError = "The selected location pin is older than two minutes and will not be sent. Capture a fresh GPS fix, choose the map pin again, or review without a pin.",
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
        if (current.pendingSosCheckInProgress || current.pendingSosCheckError != null) {
            _state.update {
                it.copy(error = "TACResQ hasn't verified whether an earlier SOS is still pending. Check again before sending another request.")
            }
            return
        }
        if (current.pendingSosAvailable) {
            _state.update {
                it.copy(error = "An earlier SOS is still awaiting confirmation. Retry that saved request before creating another SOS.")
            }
            return
        }
        val existingActive = current.incidents.firstOrNull { it.status.isActive }
        if (existingActive != null) {
            _state.update {
                it.copy(error = "You already have active request #${existingActive.id}. Cancel it while waiting for dispatch or contact dispatch if a unit may be responding.")
            }
            return
        }
        if (current.locationFix != null && !location.isFresh(current.locationFix)) {
            _state.update {
                it.copy(
                    locationFix = null,
                    locationError = "The location pin expired while you were reviewing. No request was sent; capture a fresh GPS fix, choose the map pin again, or review again without a pin.",
                )
            }
            return
        }
        val fix = current.locationFix
        if (fix == null && locationNotes.isBlank()) {
            _state.update {
                it.copy(error = "Add a location pin or a landmark/access detail before sending. Dispatch needs a usable location to coordinate help.")
            }
            return
        }
        // Claim the submission synchronously before launching the coroutine. This closes
        // the small double-tap window where two taps could both observe sending == false.
        _state.update { it.copy(sending = true, notice = null, error = null) }
        viewModelScope.launch {
            val report = description.trim().ifBlank { null }
            val landmark = locationNotes.trim().take(500).ifBlank { null }
            repo.createIncident(type, report, fix, landmark)
                .onSuccess { created ->
                    val locationConfirmed = locationResponseMatches(fix, landmark, created)
                    val status = when {
                        !locationConfirmed -> "SOS request was accepted by the server, but its location response could not be verified."
                        fix?.manuallySelected == true -> "SOS sent. Dispatch confirmed the map pin you selected."
                        fix != null -> "SOS sent. Dispatch confirmed the one-time GPS pin you submitted."
                        else -> "SOS sent without a GPS pin. Dispatch received your request; any landmark note is included."
                    }
                    _state.update {
                        it.copy(
                            sending = false,
                            pendingSosAvailable = false,
                            locationFix = null,
                            locationError = null,
                            notice = status,
                            error = if (locationConfirmed) null else "Do not resend this SOS. The server accepted the request, but the GPS pin or location note did not match what you approved. Contact dispatch to verify the location.",
                            incidents = listOf(created) + it.incidents,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(sending = false, error = e.message) }
                    refreshPendingSos()
                }
        }
    }

    private fun locationResponseMatches(fix: GeoFix?, notes: String?, incident: Incident): Boolean {
        if (incident.locationNotes?.trim().orEmpty() != notes?.trim().orEmpty()) return false
        val expectedSource = when {
            fix == null -> "none"
            fix.manuallySelected -> "manual"
            else -> "gps"
        }
        if (incident.locationSource != expectedSource) return false
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

    fun updateMyIncident(
        incident: Incident,
        type: IncidentType,
        description: String,
        locationNotes: String,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Float?,
        locationRecordedAt: String?,
        locationSource: String,
        confirmReview: Boolean,
    ) {
        if (_state.value.sending) return
        _state.update { it.copy(sending = true, error = null, notice = null) }
        viewModelScope.launch {
            repo.updateMyIncident(
                incident, type, description, locationNotes, latitude, longitude,
                accuracyM, locationRecordedAt, locationSource, confirmReview,
            ).onSuccess { updated ->
                val matches = updated.type == type &&
                    updated.description.orEmpty() == description.trim() &&
                    updated.locationNotes.orEmpty() == locationNotes.trim() &&
                    updated.latitude == latitude && updated.longitude == longitude &&
                    updated.locationSource == locationSource
                _state.update { current ->
                    current.copy(
                        sending = false,
                        incidents = current.incidents.map { if (it.id == updated.id) updated else it },
                        notice = if (matches) "Request #" + updated.id + " updated. Dispatch can see the corrected details." else null,
                        error = if (matches) null else "The server accepted the edit, but the returned details did not match what you reviewed. Contact dispatch to verify the request before making more changes.",
                    )
                }
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(sending = false, error = e.message ?: "The request could not be edited. Check its current status.") }
                refresh()
            }
        }
    }

    fun cancelPendingIncident(id: Long) {
        if (_state.value.sending) return
        _state.update { it.copy(sending = true, error = null, notice = null) }
        viewModelScope.launch {
            repo.cancelMyIncident(id)
                .onSuccess { cancelled ->
                    _state.update { current ->
                        current.copy(
                            sending = false,
                            incidents = current.incidents.map { if (it.id == cancelled.id) cancelled else it },
                            notice = "Request #${cancelled.id} was cancelled before dispatch assignment.",
                        )
                    }
                    refresh()
                }
                .onFailure { e ->
                    _state.update { it.copy(sending = false, error = e.message ?: "The request could not be cancelled. Check its current status.") }
                    refresh()
                }
        }
    }

    fun dismissMessages() {
        _state.update { it.copy(notice = null, error = null, locationError = null) }
    }
}

@Composable
fun RequesterScreen(
    vm: RequesterViewModel,
    user: User,
    onLogout: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onOpenNotifications: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var retryCaptureAfterSettings by rememberSaveable { mutableStateOf(false) }
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            retryCaptureAfterSettings = true
        } else {
            vm.onLocationButtonError()
        }
    }
    LaunchedEffect(retryCaptureAfterSettings) {
        if (retryCaptureAfterSettings) {
            retryCaptureAfterSettings = false
            vm.captureLocation()
        }
    }
    fun requestLocationAfterPermission() {
        scope.launch {
            when (val settings = vm.checkLocationSettings()) {
                LocationSettingsCheck.Ready -> vm.captureLocation()
                is LocationSettingsCheck.NeedsResolution -> runCatching {
                    locationSettingsLauncher.launch(
                        IntentSenderRequest.Builder(settings.exception.resolution).build(),
                    )
                }.onFailure { vm.onLocationButtonError() }
                LocationSettingsCheck.Unavailable -> runCatching {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }.onFailure { vm.onLocationButtonError() }
            }
        }
    }
    PollWhileStarted { vm.refresh() }
    LaunchedEffect(Unit) { vm.refreshPendingSos() }

    var selectedType by rememberSaveable { mutableStateOf<IncidentType?>(null) }
    var emergencyTypeError by rememberSaveable { mutableStateOf(false) }
    var descriptionError by rememberSaveable { mutableStateOf(false) }
    var description by rememberSaveable { mutableStateOf("") }
    var locationNotes by rememberSaveable { mutableStateOf("") }
    var locationRequirementError by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingSos by remember { mutableStateOf(false) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var incidentToCancel by rememberSaveable { mutableStateOf<Long?>(null) }
    var incidentToEdit by remember { mutableStateOf<Incident?>(null) }
    var editType by rememberSaveable { mutableStateOf(IncidentType.MEDICAL) }
    var editDescription by rememberSaveable { mutableStateOf("") }
    var editLocationNotes by rememberSaveable { mutableStateOf("") }
    var editLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var editLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var editAccuracyM by rememberSaveable { mutableStateOf<Float?>(null) }
    var editLocationRecordedAt by rememberSaveable { mutableStateOf<String?>(null) }
    var editLocationSource by rememberSaveable { mutableStateOf("none") }
    var showEditMapPicker by rememberSaveable { mutableStateOf(false) }
    var confirmingEdit by remember { mutableStateOf(false) }
    var editNowMillis by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var showManualPinPicker by rememberSaveable { mutableStateOf(false) }
    var showRequestForm by rememberSaveable { mutableStateOf(false) }
    var requestStep by rememberSaveable { mutableStateOf(1) }
    val freshFix = state.locationFix?.takeIf(vm::isFresh)
    val activeIncidents = state.incidents.filter { it.status.isActive }
    val activeIncident = activeIncidents.firstOrNull()
    LaunchedEffect(incidentToEdit?.id, incidentToEdit?.requesterLastEditedAt) {
        while (incidentToEdit != null) {
            editNowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }
    val editLastAtMillis = incidentToEdit?.requesterLastEditedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val editCooldownSeconds = if ((incidentToEdit?.requesterEditCount ?: 0) <= 0 || editLastAtMillis == null) 0L
        else (((editLastAtMillis + 60_000L - editNowMillis).coerceAtLeast(0L) + 999L) / 1000L)
    val urgentLocationCorrection = incidentToEdit?.let { editing ->
        (editLatitude != editing.latitude || editLongitude != editing.longitude) &&
            editType == editing.type &&
            editDescription.trim() == editing.description.orEmpty().trim() &&
            editLocationNotes.trim() == editing.locationNotes.orEmpty().trim()
    } ?: false

    LaunchedEffect(state.notice) {
        val message = state.notice
        if (message?.startsWith("SOS sent") == true ||
            message?.startsWith("SOS request was accepted") == true ||
            message?.startsWith("The previously saved SOS was confirmed") == true
        ) {
            selectedType = null
            emergencyTypeError = false
            descriptionError = false
            description = ""
            locationNotes = ""
            requestStep = 1
            showRequestForm = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(
                title = "Home",
                subtitle = "Request help and track your current response.",
                onOpenNotifications = onOpenNotifications,
            )
        }

        if (state.syncError != null) item { SyncStatus(state.lastSyncedAtEpochMs, state.syncError) }

        if (state.pendingSosCheckInProgress) item {
            Card(
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Checking saved SOS status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Verifying whether an earlier request still needs confirmation before another SOS can be sent.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        state.pendingSosCheckError?.let { message ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Couldn't verify saved SOS status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = vm::refreshPendingSos,
                            enabled = !state.pendingSosCheckInProgress,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("Check again") }
                    }
                }
            }
        }

        if (state.pendingSosAvailable) item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("An earlier SOS needs confirmation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("TACResQ saved the original request. Retry it exactly as submitted; do not create a replacement SOS while its outcome is unknown.")
                    Button(onClick = vm::retryPendingSos, enabled = !state.sending, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        if (state.sending) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Retrying saved SOS…")
                        } else {
                            Text("Retry saved SOS")
                        }
                    }
                }
            }
        }

        if (activeIncident != null) item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Active emergency", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(activeIncident.type.label, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        }
                        StatusPill(activeIncident.status.label, activeIncident.status.pillColor())
                    }
                    Text("Request #${activeIncident.id}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                    Text(
                        if (activeIncident.status == com.tacresq.app.data.model.IncidentStatus.PENDING)
                            "Waiting for dispatch to assign a response unit."
                        else
                            "A unit may already be responding. Contact dispatch before requesting changes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (activeIncidents.size > 1) {
                        Text(
                            "Multiple active requests found. Contact dispatch before sending another SOS.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(
                        onClick = { onOpenChat(activeIncident.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text("Open incident messages")
                    }
                    if (activeIncident.status == com.tacresq.app.data.model.IncidentStatus.PENDING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    incidentToEdit = activeIncident
                                    editType = activeIncident.type
                                    editDescription = activeIncident.description.orEmpty()
                                    editLocationNotes = activeIncident.locationNotes.orEmpty()
                                    editLatitude = activeIncident.latitude
                                    editLongitude = activeIncident.longitude
                                    editAccuracyM = activeIncident.accuracyM
                                    editLocationRecordedAt = activeIncident.locationRecordedAt
                                    editLocationSource = activeIncident.locationSource ?: if (activeIncident.latitude == null) "none" else "gps"
                                    confirmingEdit = false
                                },
                                enabled = !state.sending,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) { Text("Edit") }
                            OutlinedButton(
                                onClick = { incidentToCancel = activeIncident.id },
                                enabled = !state.sending,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) { Text("Cancel") }
                        }
                    }
                }
            }
        } else if (!showRequestForm && !state.pendingSosAvailable &&
            !state.pendingSosCheckInProgress && state.pendingSosCheckError == null
        ) item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(24.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Need emergency help?",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Share what happened and where. GPS is optional.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f),
                            )
                        }
                    }
                    Button(
                        onClick = { showRequestForm = true },
                        enabled = !state.sending && !state.loading,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                            disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                        ),
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Checking requests…", fontWeight = FontWeight.Bold)
                        } else {
                            Text("Request emergency help", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (showRequestForm && (activeIncident != null || state.pendingSosAvailable)) item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Unsent draft paused", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (activeIncident != null) {
                            "Request #${activeIncident.id} is active. Your draft is preserved but cannot be sent while this request is active."
                        } else {
                            "A previously saved SOS still needs confirmation. Your draft is preserved; retry or resolve the saved SOS before creating another request."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = { showDiscardConfirmation = true },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Discard draft") }
                }
            }
        }

        if (showRequestForm && activeIncident == null && !state.pendingSosAvailable &&
            !state.pendingSosCheckInProgress && state.pendingSosCheckError == null
        ) item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Emergency request", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        TextButton(
                            onClick = {
                                val hasDraft = selectedType != null ||
                                    description.isNotBlank() ||
                                    locationNotes.isNotBlank() ||
                                    requestStep != 1 ||
                                    state.locationFix != null
                                if (hasDraft) showDiscardConfirmation = true
                                else {
                                    showRequestForm = false
                                    confirmingSos = false
                                }
                            },
                            enabled = !state.sending && !state.capturingLocation,
                        ) { Text("Close") }
                    }
                    Text(
                        "Step $requestStep of 3 · " + when (requestStep) {
                            1 -> "Details"
                            2 -> "Location"
                            else -> "Review"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = requestStep.toFloat(),
                                range = 1f..3f,
                                steps = 2,
                            )
                        },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        repeat(3) { stepIndex ->
                            Surface(
                                modifier = Modifier.weight(1f).height(4.dp),
                                shape = RoundedCornerShape(50),
                                color = if (stepIndex < requestStep) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            ) {}
                        }
                    }
                    when (requestStep) {
                        1 -> {
                            Text("Emergency type (required)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                            Column(
                                modifier = Modifier.fillMaxWidth().selectableGroup(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                IncidentType.entries.forEach { type ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().selectable(
                                            selected = type == selectedType,
                                            role = Role.RadioButton,
                                            onClick = {
                                                selectedType = type
                                                emergencyTypeError = false
                                                if (type != IncidentType.OTHER) descriptionError = false
                                            },
                                        ),
                                        shape = MaterialTheme.shapes.medium,
                                        border = BorderStroke(
                                            if (type == selectedType) 1.5.dp else 1.dp,
                                            if (type == selectedType) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                        ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (type == selectedType)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surface,
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
                                                .padding(horizontal = 14.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            RadioButton(
                                                selected = type == selectedType,
                                                onClick = null,
                                            )
                                            Text(
                                                type.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = if (type == selectedType) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (type == selectedType) MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurface,
                                            )
                                            Spacer(Modifier.weight(1f))
                                            if (type == selectedType) {
                                                Text("Selected", style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                            if (emergencyTypeError) {
                                Text(
                                    "Choose the emergency type before continuing.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            OutlinedTextField(
                                value = description,
                                onValueChange = {
                                    if (it.length <= 2000) {
                                        description = it
                                        if (it.isNotBlank()) descriptionError = false
                                    }
                                },
                                label = {
                                    Text(if (selectedType == IncidentType.OTHER) "Describe the emergency (required)" else "What is happening? (optional)")
                                },
                                isError = descriptionError,
                                supportingText = {
                                    if (selectedType == IncidentType.OTHER) {
                                        Text(if (descriptionError) "Describe what happened so dispatch can identify the emergency." else "Required when the emergency type is Other.")
                                    } else {
                                        Text("Optional details help dispatch prepare a response.")
                                    }
                                },
                                minLines = 3,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    emergencyTypeError = selectedType == null
                                    descriptionError = selectedType == IncidentType.OTHER && description.isBlank()
                                    if (selectedType != null && !(selectedType == IncidentType.OTHER && description.isBlank())) {
                                        requestStep = 2
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text("Continue") }
                        }
                        2 -> {
                            Text("Confirm your location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                            Text(
                                "Your location is not tracked continuously. Share a one-time GPS pin, choose a map pin, or continue without GPS.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.capturingLocation) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp)); Text("Finding a fresh location…")
                                }
                            } else {
                                LocationButton(modifier = Modifier.fillMaxWidth().height(56.dp), textType = LocationButtonTextType.SharePreciseLocation,
                                    backgroundColor = MaterialTheme.colorScheme.primary, strokeColor = MaterialTheme.colorScheme.primary,
                                    textColor = MaterialTheme.colorScheme.onPrimary, iconTint = MaterialTheme.colorScheme.onPrimary,
                                    onPermissionResult = { granted -> if (granted) requestLocationAfterPermission() else vm.onLocationDenied() },
                                    onError = { vm.onLocationButtonError() })
                            }
                            OutlinedButton(
                                onClick = { showManualPinPicker = true },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                enabled = !state.capturingLocation && !state.sending,
                            ) {
                                Icon(Icons.Default.LocationOn, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (freshFix?.manuallySelected == true) "Adjust map pin" else "Choose location on map")
                            }
                            when {
                                freshFix != null -> LocationFixCard(freshFix, onClear = vm::clearLocationFix)
                                state.locationFix != null -> LocationNoticeCard("Location pin expired", "This pin will not be sent. Capture a fresh GPS fix, choose a map pin again, or continue without a pin.")
                                !state.capturingLocation -> LocationNoticeCard("No GPS pin", "Add a landmark or continue without GPS.")
                            }
                            state.locationError?.let { Banner(it, isError = true, onDismiss = vm::dismissMessages) }
                            OutlinedTextField(
                                value = locationNotes,
                                onValueChange = {
                                    if (it.length <= 500) {
                                        locationNotes = it
                                        if (it.isNotBlank()) locationRequirementError = null
                                    }
                                },
                                label = { Text(if (freshFix == null) "Landmark or access detail (required without a pin)" else "Landmark or access detail (optional)") },
                                isError = freshFix == null && locationRequirementError != null,
                                supportingText = {
                                    when {
                                        freshFix != null -> Text("Optional: add a building, entrance, floor, or nearby landmark.")
                                        locationRequirementError != null -> Text(locationRequirementError!!)
                                        else -> Text("Required when no GPS or map pin is attached. Add a place dispatch can locate.")
                                    }
                                },
                                minLines = 2,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { requestStep = 1 }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Back") }
                                Button(
                                    onClick = {
                                        val hasFreshPin = state.locationFix?.let(vm::isFresh) == true
                                        if (!hasFreshPin && locationNotes.isBlank()) {
                                            locationRequirementError = "Add a location pin or a landmark/access detail before continuing."
                                        } else {
                                            locationRequirementError = null
                                            vm.expireStaleLocation()
                                            requestStep = 3
                                        }
                                    },
                                    enabled = !state.capturingLocation,
                                    modifier = Modifier.weight(1f).height(52.dp),
                                ) { Text("Review request") }
                            }
                        }
                        else -> {
                            Text("Review before sending", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                            Text("Emergency type: ${selectedType?.label ?: "Not selected"}", fontWeight = FontWeight.SemiBold)
                            description.trim().takeIf { it.isNotEmpty() }?.let { Text("Details: $it") }
                            if (freshFix != null) LocationFixCard(freshFix, onClear = { vm.clearLocationFix(); requestStep = 2 })
                            else LocationNoticeCard("Sending without a GPS pin", "Dispatch may need to call you to confirm the exact location.")
                            locationNotes.trim().takeIf { it.isNotEmpty() }?.let { Text("Landmark/access detail: $it") }
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

        // Show one primary operation message. A safety-critical error takes precedence
        // over a success notice when the server accepted an SOS but its location is unverified.
        val feedbackError = state.error
        if (feedbackError != null) {
            item { Banner(feedbackError, isError = true, onDismiss = vm::dismissMessages) }
        } else {
            state.notice?.let { message ->
                item { Banner(message, isError = false, onDismiss = vm::dismissMessages) }
            }
        }

        item { SectionHeading("My requests") }
        if (state.loading && state.incidents.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Loading your requests", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Checking the latest status from the service.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else if (state.incidents.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("No requests yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "When you submit a request, its status and details will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        items(state.incidents, key = { it.id }) { incident -> IncidentCard(incident) }
    }

    if (showManualPinPicker) {
        val initial = freshFix
        ManualLocationPickerDialog(
            initialLatitude = initial?.latitude ?: 11.2443,
            initialLongitude = initial?.longitude ?: 125.0030,
            startsAtTaclobanDefault = initial == null,
            onDismiss = { showManualPinPicker = false },
            onConfirm = { latitude, longitude ->
                vm.setManualLocation(latitude, longitude)
                showManualPinPicker = false
            },
        )
    }

    if (incidentToEdit != null && !confirmingEdit) {
        val editing = incidentToEdit!!
        AlertDialog(
            onDismissRequest = { if (!state.sending) { incidentToEdit = null; confirmingEdit = false } },
            title = { Text("Edit request #" + editing.id) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (editCooldownSeconds > 0 && urgentLocationCorrection)
                            "Correct the map pin now. Other edits remain locked until the timer ends."
                        else
                            "Before assignment, your first correction is immediate. Further changes wait 60 seconds and require review."
                    )
                    IncidentType.entries.forEach { type ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = editType == type, onClick = { editType = type })
                            Text(type.label)
                        }
                    }
                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { if (it.length <= 2000) editDescription = it },
                        label = { Text("What is happening?") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )
                    OutlinedTextField(
                        value = editLocationNotes,
                        onValueChange = { if (it.length <= 500) editLocationNotes = it },
                        label = { Text("Location landmark or access note") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3,
                    )
                    Text(
                        if (editLatitude != null && editLongitude != null)
                            "Current pin: %.5f, %.5f".format(Locale.US, editLatitude, editLongitude)
                        else "No map pin is attached. Add a map pin if the location needs correction.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { showEditMapPicker = true }, modifier = Modifier.fillMaxWidth()) { Text("Correct map pin") }
                    if (editing.requesterEditCount > 0 && editCooldownSeconds > 0 && !urgentLocationCorrection) {
                        Text("You can make another edit in " + editCooldownSeconds + "s.", color = MaterialTheme.colorScheme.error)
                    } else if (editing.requesterEditCount > 0 && editCooldownSeconds > 0 && urgentLocationCorrection) {
                        Text("Location correction can be reviewed now. Other changes remain locked for " + editCooldownSeconds + "s.")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.sending && (editCooldownSeconds == 0L || urgentLocationCorrection),
                    onClick = {
                        if (editing.requesterEditCount == 0) {
                            incidentToEdit = null
                            vm.updateMyIncident(
                                editing, editType, editDescription, editLocationNotes,
                                editLatitude, editLongitude, editAccuracyM, editLocationRecordedAt,
                                editLocationSource, false,
                            )
                        } else {
                            confirmingEdit = true
                        }
                    },
                ) { Text(if (editing.requesterEditCount == 0) "Save correction" else "Review changes") }
            },
            dismissButton = { TextButton(onClick = { incidentToEdit = null; confirmingEdit = false }) { Text("Keep current request") } },
        )
    }

    if (showEditMapPicker) {
        ManualLocationPickerDialog(
            initialLatitude = editLatitude ?: 11.2443,
            initialLongitude = editLongitude ?: 125.0030,
            startsAtTaclobanDefault = editLatitude == null || editLongitude == null,
            onDismiss = { showEditMapPicker = false },
            onConfirm = { latitude, longitude ->
                editLatitude = latitude
                editLongitude = longitude
                editAccuracyM = null
                editLocationRecordedAt = Instant.now().toString()
                editLocationSource = "manual"
                showEditMapPicker = false
            },
        )
    }

    if (confirmingEdit && incidentToEdit != null) {
        val editing = incidentToEdit!!
        AlertDialog(
            onDismissRequest = { confirmingEdit = false },
            title = { Text("Review your correction") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Request #" + editing.id + " will be updated with these details:")
                    Text("Emergency: " + editType.label)
                    Text("Details: " + editDescription.trim().ifBlank { "None provided" })
                    Text("Location note: " + editLocationNotes.trim().ifBlank { "None provided" })
                    Text(if (editLatitude != null && editLongitude != null) "Pin: %.5f, %.5f".format(Locale.US, editLatitude, editLongitude) else "No GPS/map pin")
                    Text("This correction will be recorded for dispatch. Confirm only if these details are accurate.")
                    if (editCooldownSeconds > 0 && urgentLocationCorrection) {
                        Text("You are correcting only the emergency pin, so this safety-critical change can bypass the timer.")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !state.sending && (editCooldownSeconds == 0L || urgentLocationCorrection), onClick = {
                    incidentToEdit = null
                    confirmingEdit = false
                    vm.updateMyIncident(
                        editing, editType, editDescription, editLocationNotes,
                        editLatitude, editLongitude, editAccuracyM, editLocationRecordedAt,
                        editLocationSource, true,
                    )
                }) { Text("Confirm and save") }
            },
            dismissButton = { TextButton(onClick = { confirmingEdit = false }) { Text("Go back") } },
        )
    }

    if (incidentToCancel != null) {
        AlertDialog(
            onDismissRequest = { incidentToCancel = null },
            title = { Text("Cancel emergency request?") },
            text = {
                Text("This will cancel the request only if dispatch has not assigned a unit. If a unit has already been assigned, the server will reject cancellation so you can contact dispatch instead.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = incidentToCancel
                    incidentToCancel = null
                    if (id != null) vm.cancelPendingIncident(id)
                }) { Text("Confirm cancellation") }
            },
            dismissButton = {
                TextButton(onClick = { incidentToCancel = null }) { Text("Keep request") }
            },
        )
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard this request draft?") },
            text = {
                Text("Your unsent details and selected location will be cleared. Nothing has been sent to dispatch yet.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirmation = false
                    confirmingSos = false
                    showManualPinPicker = false
                    selectedType = null
                    emergencyTypeError = false
                    descriptionError = false
                    description = ""
                    locationNotes = ""
                    requestStep = 1
                    vm.clearLocationFix()
                    showRequestForm = false
                }) { Text("Discard draft") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) { Text("Keep editing") }
            },
        )
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
                    Text("Dispatch will receive a ${selectedType?.label?.lowercase() ?: "unselected"} emergency.")
                    description.trim().takeIf { it.isNotEmpty() }?.let { Text(it) }
                    if (freshFix != null) {
                        Text(locationSummary(freshFix))
                        Text("This one-time pin is sent; ongoing tracking is not.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("No GPS pin. Dispatch may call to confirm the location.")
                    }
                    locationNotes.trim().takeIf { it.isNotEmpty() }?.let {
                        Text("Landmark: $it")
                    }
                    Text("Only send if help is needed now.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.sending && !state.capturingLocation &&
                        !state.pendingSosCheckInProgress && state.pendingSosCheckError == null,
                    onClick = {
                        confirmingSos = false
                        selectedType?.let { vm.sendSos(it, description, locationNotes) }
                    },
                ) { Text(if (state.sending) "Sending…" else "Send SOS") }
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
            Text(
                if (fix.manuallySelected) "Map pin selected" else "One-time GPS fix ready",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (fix.manuallySelected) "Coordinates chosen by you on the map · selected $captured"
                else "Reported accuracy: $accuracy · captured $captured",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!fix.manuallySelected) Text(
                if (fix.hasPrecisePermission) "Precise location access was granted for this action."
                else "Approximate location access is active; the pin may be broad.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!fix.manuallySelected && (fix.accuracyM == null || fix.accuracyM > 100f)) {
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
    if (fix.manuallySelected) {
        return "Your manually selected map pin (" +
            String.format(Locale.US, "%.5f", fix.latitude) + ", " +
            String.format(Locale.US, "%.5f", fix.longitude) +
            "), selected $timestamp, will be sent to dispatch."
    }
    val precision = if (fix.hasPrecisePermission) "GPS pin" else "approximate GPS pin"
    return "One $precision ($accuracy), captured $timestamp, will be sent to dispatch."
}


@Composable
private fun ManualLocationPickerDialog(
    initialLatitude: Double,
    initialLongitude: Double,
    startsAtTaclobanDefault: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double) -> Unit,
) {
    val context = LocalContext.current
    val centerCoordinates = androidx.compose.runtime.remember {
        mutableStateOf(initialLatitude to initialLongitude)
    }
    var userAdjustedMap by androidx.compose.runtime.remember { mutableStateOf(false) }
    val mapView = androidx.compose.runtime.remember {
        val osmdroidBase = File(context.cacheDir, "osmdroid").apply { mkdirs() }
        val tileCache = File(osmdroidBase, "tiles").apply { mkdirs() }
        val config = Configuration.getInstance()
        config.setOsmdroidBasePath(osmdroidBase)
        config.setOsmdroidTileCache(tileCache)
        config.userAgentValue = "TACResQ/1.0.0 (" + context.packageName + "; Android)"
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_MOVE) userAdjustedMap = true
                false
            }
            isTilesScaledToDpi = true
            controller.setZoom(16.5)
            controller.setCenter(GeoPoint(initialLatitude, initialLongitude))
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    val point = mapCenter
                    centerCoordinates.value = point.latitude to point.longitude
                    return true
                }
                override fun onZoom(event: ZoomEvent?): Boolean {
                    val point = mapCenter
                    centerCoordinates.value = point.latitude to point.longitude
                    return true
                }
            })
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(mapView, lifecycleOwner) {
        var mapIsResumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (!mapIsResumed) {
                        mapView.onResume()
                        mapIsResumed = true
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (mapIsResumed) {
                        mapView.onPause()
                        mapIsResumed = false
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (mapIsResumed) mapView.onPause()
            mapView.onDetach()
        }
    }

    // LifeLink-inspired: the map is the screen, with compact floating controls and a
    // persistent bottom confirmation surface instead of a small AlertDialog preview.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize().semantics { paneTitle = "Choose emergency location" },
            color = MaterialTheme.colorScheme.surface,
        ) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize(),
                )

                // A fixed center pin makes the chosen coordinate explicit while the map moves beneath it.
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Selected emergency location at map center",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-18).dp).size(42.dp),
                )

                Row(
                    modifier = Modifier.align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 12.dp, end = 12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.material3.FilledTonalIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                "Choose location",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (startsAtTaclobanDefault) "Starts at Tacloban, not your GPS position. Move the map to the emergency site."
                                else "Move the map so the center pin marks the emergency site.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (startsAtTaclobanDefault) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                androidx.compose.material3.FilledTonalIconButton(
                    onClick = {
                        mapView.controller.animateTo(GeoPoint(initialLatitude, initialLongitude))
                        mapView.controller.setZoom(16.5)
                        centerCoordinates.value = initialLatitude to initialLongitude
                    },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(48.dp),
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.MyLocation,
                        contentDescription = "Return to starting location",
                    )
                }

                Card(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Selected location: %.5f, %.5f".format(
                                Locale.US,
                                centerCoordinates.value.first,
                                centerCoordinates.value.second,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (startsAtTaclobanDefault && !userAdjustedMap) {
                            Text(
                                "Move the map before confirming.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text(
                                "Map pin only; it does not measure GPS accuracy. If the map is blank, cancel and share a landmark.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) { Text("Cancel") }
                            Button(
                                enabled = !startsAtTaclobanDefault || userAdjustedMap,
                                onClick = {
                                    val point = mapView.mapCenter
                                    if (point.latitude.isFinite() && point.longitude.isFinite()) {
                                        onConfirm(point.latitude, point.longitude)
                                    }
                                },
                                modifier = Modifier.weight(1.4f).heightIn(min = 52.dp),
                            ) { Text("Use this location") }
                        }
                        Text(
                            "Map data © OpenStreetMap contributors",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
