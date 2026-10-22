package com.tacresq.app.ui.requester

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    fun dismissMessages() {
        _state.update { it.copy(notice = null, error = null, locationError = null) }
    }
}

@Composable
fun RequesterScreen(vm: RequesterViewModel, user: User, onLogout: () -> Unit) {
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

    var selectedType by rememberSaveable { mutableStateOf(IncidentType.MEDICAL) }
    var description by rememberSaveable { mutableStateOf("") }
    var locationNotes by rememberSaveable { mutableStateOf("") }
    var confirmingSos by rememberSaveable { mutableStateOf(false) }
    var showManualPinPicker by rememberSaveable { mutableStateOf(false) }
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(
                title = "Home",
                subtitle = "",
                onLogout = onLogout,
            )
        }

        if (state.syncError != null) item { SyncStatus(state.lastSyncedAtEpochMs, state.syncError) }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Emergency request", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Step $requestStep of 3 · " + when (requestStep) {
                            1 -> "Details"
                            2 -> "Location"
                            else -> "Review"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (requestStep) {
                        1 -> {
                            Text("What kind of help do you need?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                IncidentType.entries.forEach { type ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (type == selectedType)
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else MaterialTheme.colorScheme.surface,
                                        ),
                                        onClick = { selectedType = type },
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            RadioButton(
                                                selected = type == selectedType,
                                                onClick = { selectedType = type },
                                            )
                                            Text(type.label, style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(value = description, onValueChange = { if (it.length <= 2000) description = it },
                                label = { Text("What is happening? (optional)") }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { requestStep = 2 }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue to location") }
                        }
                        2 -> {
                            Text("Confirm your location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Add a GPS pin or continue without one.",
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
                            OutlinedTextField(value = locationNotes, onValueChange = { if (it.length <= 500) locationNotes = it },
                                label = { Text("Landmark or access detail (optional)") }, minLines = 2, maxLines = 3,
                                modifier = Modifier.fillMaxWidth())
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
                        Text("Only this pin is included with the SOS; ongoing location updates are not sent.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("No GPS pin. Dispatch may call to confirm your location.")
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
            modifier = Modifier.fillMaxSize(),
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
                            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close location picker",
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
                                "Choose emergency location",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (startsAtTaclobanDefault) "The map starts at Tacloban as a reference only—not your phone's location. Move the map to the actual emergency location before confirming."
                                else "Drag the map until the exact location is under the center pin. This can be different from your phone's GPS location.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (startsAtTaclobanDefault) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.FilledTonalIconButton(
                        onClick = { mapView.controller.zoomIn() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                    androidx.compose.material3.FilledTonalIconButton(
                        onClick = { mapView.controller.zoomOut() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Text("−", style = MaterialTheme.typography.titleLarge)
                    }
                    androidx.compose.material3.FilledTonalIconButton(
                        onClick = {
                            mapView.controller.animateTo(GeoPoint(initialLatitude, initialLongitude))
                            mapView.controller.setZoom(16.5)
                            centerCoordinates.value = initialLatitude to initialLongitude
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.MyLocation,
                            contentDescription = "Return to starting location",
                        )
                    }
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
                            "Selected center: %.5f, %.5f".format(
                                Locale.US,
                                centerCoordinates.value.first,
                                centerCoordinates.value.second,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            String.format(
                                Locale.US,
                                "%.5f, %.5f",
                                centerCoordinates.value.first,
                                centerCoordinates.value.second,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (startsAtTaclobanDefault && !userAdjustedMap) {
                            Text(
                                "This is only a Tacloban reference view, not your phone's location. Move the map before confirming.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text(
                                "This is a map-selected pin, not a GPS accuracy measurement. Wait until roads and landmarks appear before confirming. If the map stays blank, cancel and use a landmark instead.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (startsAtTaclobanDefault && !userAdjustedMap) {
                            Text(
                                "Move the map to the real emergency location to enable confirmation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
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
