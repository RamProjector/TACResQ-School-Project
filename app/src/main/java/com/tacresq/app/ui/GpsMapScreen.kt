package com.tacresq.app.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.locationbutton.compose.LocationButton
import androidx.core.locationbutton.compose.LocationButtonTextType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tacresq.app.ui.common.incidentLocationText
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import com.tacresq.app.data.model.GeoFix
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.ResponderLocation
import com.tacresq.app.data.model.User
import com.tacresq.app.ui.common.StatusPill
import com.tacresq.app.ui.common.pillColor
import com.tacresq.app.location.LocationCaptureResult
import com.tacresq.app.location.LocationSettingsCheck
import com.tacresq.app.location.LocationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import java.io.File

private const val TACLOBAN_LATITUDE = 11.2443
private const val TACLOBAN_LONGITUDE = 125.0030

private fun ConnectivityManager.hasValidatedInternet(): Boolean {
    val network = activeNetwork ?: return false
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun Incident.hasValidMapCoordinates(): Boolean {
    val lat = latitude ?: return false
    val lng = longitude ?: return false
    return lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
}

private enum class MapLocationFreshness(val label: String) {
    FRESH("Fresh"),
    AGING("Aging"),
    STALE("Stale"),
}

private fun ResponderLocation.mapAgeSeconds(now: Instant = Instant.now()): Long? {
    if (!latitude.isFinite() || !longitude.isFinite() ||
        latitude !in -90.0..90.0 || longitude !in -180.0..180.0
    ) return null
    val recorded = runCatching { Instant.parse(recordedAt) }.getOrNull() ?: return null
    // Duration.seconds truncates sub-second negative durations toward zero.
    // Compare instants first so even a timestamp milliseconds in the future is invalid.
    if (recorded.isAfter(now)) return null
    return runCatching { Duration.between(recorded, now).seconds }.getOrNull()
}

private fun ResponderLocation.mapFreshness(now: Instant = Instant.now()): MapLocationFreshness? {
    val age = mapAgeSeconds(now) ?: return null
    return when {
        age < 30L -> MapLocationFreshness.FRESH
        age <= 120L -> MapLocationFreshness.AGING
        else -> MapLocationFreshness.STALE
    }
}

private fun formatLocationAge(ageSeconds: Long): String = when {
    ageSeconds < 60L -> "${ageSeconds}s ago"
    ageSeconds < 3600L -> "${ageSeconds / 60L}m ago"
    else -> "${ageSeconds / 3600L}h ago"
}

/**
 * Accuracy is a radius in meters, not a point coordinate. Never present malformed
 * server values (NaN, infinity, or negative numbers) as trustworthy precision.
 */
private fun Float?.validAccuracyLabel(prefix: String): String? =
    this?.takeIf { it.isFinite() && it >= 0f }
        ?.let { "$prefix${it.roundToInt()} m" }

/**
 * Incident coordinates may be valid but old. Keep the pin visible, while making
 * the recorded age explicit so users can confirm the destination before navigating.
 */
private fun Incident.locationAgeStatus(now: Instant = Instant.now()): Pair<String, Boolean> {
    val rawTimestamp = locationRecordedAt
        ?: return "Location time unavailable — confirm before navigating." to true
    val recordedAt = runCatching { Instant.parse(rawTimestamp) }.getOrNull()
        ?: return "Location timestamp invalid — confirm before navigating." to true
    // Compare instants before calculating whole seconds; sub-second future times
    // otherwise truncate to zero and could be presented as a current location.
    if (recordedAt.isAfter(now)) {
        return "Location timestamp is in the future — confirm before navigating." to true
    }
    val ageSeconds = runCatching { Duration.between(recordedAt, now).seconds }.getOrNull()
        ?: return "Location timestamp invalid — confirm before navigating." to true
    if (ageSeconds < 0L) {
        return "Location timestamp is in the future — confirm before navigating." to true
    }
    val recordedLabel = "Location recorded ${formatLocationAge(ageSeconds)}"
    return if (ageSeconds > 600L) {
        "$recordedLabel — confirm before navigating." to true
    } else {
        recordedLabel to false
    }
}

@Composable
private fun GpsMapFilterButtons(
    role: Role,
    hasIncidents: Boolean,
    hasActiveUnits: Boolean,
    onShowIncidents: () -> Unit,
    onShowActiveUnits: () -> Unit,
) {
    if (!hasIncidents && !(role == Role.DISPATCHER && hasActiveUnits)) return

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasIncidents) {
            androidx.compose.material3.AssistChip(
                onClick = onShowIncidents,
                modifier = Modifier.heightIn(min = 44.dp).semantics {
                    contentDescription = "Center map on active incidents"
                },
                label = { Text("Incidents", maxLines = 1) },
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
        if (role == Role.DISPATCHER && hasActiveUnits) {
            androidx.compose.material3.AssistChip(
                onClick = onShowActiveUnits,
                modifier = Modifier.heightIn(min = 44.dp).semantics {
                    contentDescription = "Center map on responder field units"
                },
                label = { Text("Field units", maxLines = 1) },
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpsMapScreen(
    role: Role,
    user: User,
    incidents: List<Incident>,
    locationProvider: LocationProvider,
    responderLocations: List<ResponderLocation> = emptyList(),
    onRefreshResponderLocations: () -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // Keep the latest callback without restarting the polling coroutine on recomposition.
    val refreshResponderLocations by rememberUpdatedState(onRefreshResponderLocations)
    var deviceFix by remember { mutableStateOf<GeoFix?>(null) }
    var selectedIncidentId by rememberSaveable { mutableStateOf<Long?>(null) }
    val mapSheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = androidx.compose.material3.rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
        ),
    )
    // System Back collapses the selected-place sheet before leaving the map.
    BackHandler {
        if (mapSheetState.bottomSheetState.currentValue == SheetValue.Expanded) {
            scope.launch { mapSheetState.bottomSheetState.partialExpand() }
        } else {
            onBack()
        }
    }
    val latestDeviceFix by rememberUpdatedState(deviceFix)
    var hasAutoFramedInitialMapPoints by remember { mutableStateOf(false) }
    var hasAutoFramedIncidentPoints by remember { mutableStateOf(false) }
    var freshnessTick by remember { mutableStateOf(0) }
    var locationStatus by remember { mutableStateOf<String?>(null) }
    var isLocationRequestInProgress by remember { mutableStateOf(false) }
    var retryLocationAfterSettings by remember { mutableStateOf(false) }
    var showAppSettingsAction by remember { mutableStateOf(false) }
    var hasValidatedInternet by remember { mutableStateOf<Boolean?>(null) }
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    DisposableEffect(connectivityManager) {
        if (connectivityManager == null) {
            hasValidatedInternet = false
            onDispose {}
        } else {
            fun refreshConnectivity() {
                val connected = runCatching { connectivityManager.hasValidatedInternet() }.getOrDefault(false)
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    hasValidatedInternet = connected
                } else {
                    Handler(Looper.getMainLooper()).post { hasValidatedInternet = connected }
                }
            }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshConnectivity()
                override fun onLost(network: Network) = refreshConnectivity()
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    refreshConnectivity()
            }
            refreshConnectivity()
            runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
                .onFailure { hasValidatedInternet = false }
            onDispose {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        }
    }
    val visibleIncidents = when (role) {
        Role.REQUESTER, Role.RESPONDER, Role.DRIVER -> incidents.filter { it.status.isActive }
        Role.DISPATCHER -> incidents.filter { it.status.isActive || it.status.name == "PENDING" }
        Role.ADMIN -> emptyList()
    }
    val validIncidentPoints = visibleIncidents.mapNotNull { incident ->
        val lat = incident.latitude
        val lng = incident.longitude
        if (incident.hasValidMapCoordinates() && lat != null && lng != null) {
            GeoPoint(lat, lng)
        } else {
            null
        }
    }
    val mapNow = remember(freshnessTick) { Instant.now() }
    val selectedIncident = visibleIncidents.firstOrNull { it.id == selectedIncidentId }
    val validResponderLocations = if (role == Role.DISPATCHER) {
        responderLocations.filter { it.mapAgeSeconds(mapNow) != null }
    } else {
        emptyList()
    }
    val freshResponderCount = validResponderLocations.count { it.mapFreshness(mapNow) == MapLocationFreshness.FRESH }
    val agingResponderCount = validResponderLocations.count { it.mapFreshness(mapNow) == MapLocationFreshness.AGING }
    val staleResponderCount = validResponderLocations.count { it.mapFreshness(mapNow) == MapLocationFreshness.STALE }
    val hasConfirmedIncidentCoordinates = visibleIncidents.any { it.hasValidMapCoordinates() }
    val validResponderPoints = validResponderLocations.map { GeoPoint(it.latitude, it.longitude) }
    // Keep stale last-known positions visible, but never let them dominate the initial
    // camera bounds. Fresh/aging units can still be framed when no incident pins exist;
    // dispatchers can explicitly center the map on stale units with the Field units action.
    val responderPointsForInitialFrame = validResponderLocations
        .filter { it.mapFreshness(mapNow) != MapLocationFreshness.STALE }
        .map { GeoPoint(it.latitude, it.longitude) }
    val initialMapPoints = if (role == Role.DISPATCHER) {
        validIncidentPoints + responderPointsForInitialFrame
    } else {
        validIncidentPoints
    }
    val incidentPinSummary = validIncidentPoints.size.takeIf { it > 0 }?.let { count ->
        if (count == 1) "1 incident pin on map" else "$count incident pins on map"
    }
    val defaultMapStatus = when {
        validIncidentPoints.isNotEmpty() ->
            "$incidentPinSummary."
        visibleIncidents.isNotEmpty() ->
            "Active incidents have no valid map pins."
        else ->
            "No incident pins yet."
    }
    val roleMapTitle = selectedIncident?.let { "Incident #${it.id} · ${it.type.label}" } ?: when (role) {
        Role.REQUESTER -> if (deviceFix != null) "Your location" else "Your map"
        Role.RESPONDER, Role.DRIVER -> "Response map"
        Role.DISPATCHER -> "Dispatch overview"
        Role.ADMIN -> "Operations map"
    }
    val roleMapSummary = when (role) {
        Role.REQUESTER -> when {
            showAppSettingsAction -> "Location access is off · incident pins remain visible."
            deviceFix != null -> "One-time location preview · this device only."
            else -> defaultMapStatus
        }
        Role.RESPONDER, Role.DRIVER -> defaultMapStatus
        Role.DISPATCHER -> if (validResponderLocations.isNotEmpty()) {
            "$defaultMapStatus $freshResponderCount field units currently fresh."
        } else {
            defaultMapStatus
        }
        Role.ADMIN -> "Read-only map overview."
    }
    val collapsedMapSummary = when {
        showAppSettingsAction -> "Location access is off · incident pins remain visible."
        locationStatus != null -> locationStatus!!
        else -> roleMapSummary
    }
    val emptyMapMessage = when (role) {
        Role.REQUESTER -> "No active incident locations are available right now. You can still explore the map."
        Role.RESPONDER, Role.DRIVER -> "No active response locations are available right now."
        Role.DISPATCHER -> "No incident or field-unit locations are available right now."
        Role.ADMIN -> "The admin map is read-only. Incident and responder locations are not exposed here."
    }

    // Clear stale action text when the incident set changes; the default is derived from current map data.
    androidx.compose.runtime.LaunchedEffect(visibleIncidents) {
        locationStatus = null
        if (selectedIncidentId != null && visibleIncidents.none { it.id == selectedIncidentId }) {
            selectedIncidentId = null
        }
    }

    // Pause periodic work whenever the app/screen is not STARTED. This avoids
    // background recomposition/network polling and refreshes freshness on return.
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(15_000)
                freshnessTick += 1
            }
        }
    }

    // Dispatcher location polling is foreground-only and performs an immediate
    // refresh on every return to STARTED; cancellation on STOPPED ends the loop.
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner, role, user.id) {
        if (role == Role.DISPATCHER) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                refreshResponderLocations()
                while (true) {
                    delay(15_000)
                    refreshResponderLocations()
                }
            }
        }
    }

    val mapView = remember(context, role) {
        // Keep osmdroid's disk cache in app-private storage. Legacy external-storage
        // defaults can be unavailable on modern Android targets without storage access.
        val osmdroidBase = File(context.cacheDir, "osmdroid").apply { mkdirs() }
        val tileCache = File(osmdroidBase, "tiles").apply { mkdirs() }
        val osmdroidConfig = Configuration.getInstance()
        osmdroidConfig.setOsmdroidBasePath(osmdroidBase)
        osmdroidConfig.setOsmdroidTileCache(tileCache)
        // Identify this app to public tile servers rather than using a generic library UA.
        osmdroidConfig.userAgentValue = "TACResQ/1.0.0 (" + context.packageName + "; Android)"
        MapView(context).apply {
            contentDescription = if (role == Role.DISPATCHER) {
                "Emergency response map centered on Tacloban City. Select an incident pin to open its details. Drag the bottom sheet to review incident locations and responder freshness."
            } else {
                "Emergency response map centered on Tacloban City. Select an incident pin to open its details. Drag the bottom sheet to review active incident locations."
            }
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(TACLOBAN_LATITUDE, TACLOBAN_LONGITUDE))
        }
    }

    fun showIncidentPins() {
        when (validIncidentPoints.size) {
            0 -> locationStatus = "There are no valid incident coordinates to center on yet."
            1 -> {
                deviceFix = null
                mapView.controller.setZoom(15.0)
                mapView.controller.animateTo(validIncidentPoints.first())
                locationStatus = "Centered on 1 incident pin."
            }
            else -> {
                deviceFix = null
                mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(validIncidentPoints), true, 64)
                locationStatus = "Showing ${validIncidentPoints.size} incident pins."
            }
        }
    }

    fun focusIncidentOnMap(incident: Incident) {
        selectedIncidentId = incident.id
        scope.launch { mapSheetState.bottomSheetState.expand() }
        val latitude = incident.latitude
        val longitude = incident.longitude
        if (!incident.hasValidMapCoordinates() || latitude == null || longitude == null) {
            locationStatus = "Incident #${incident.id} has no usable map coordinates."
            return
        }
        deviceFix = null
        mapView.controller.setZoom(15.0)
        mapView.controller.animateTo(GeoPoint(latitude, longitude))
        locationStatus = "Centered on incident #${incident.id}. Check the recorded location time before navigating."
    }

    fun showActiveUnits() {
        when (validResponderPoints.size) {
            0 -> locationStatus = "No valid field-unit location reports are available. Units must be en route and explicitly sharing foreground location."
            1 -> {
                deviceFix = null
                mapView.controller.setZoom(15.0)
                mapView.controller.animateTo(validResponderPoints.first())
                val freshness = validResponderLocations.first().mapFreshness(mapNow)
                locationStatus = if (freshness == MapLocationFreshness.STALE) {
                    "Centered on a stale unit pin · confirm with dispatch."
                } else {
                    "Centered on latest unit pin · ${freshness?.label?.lowercase() ?: "freshness unknown"}."
                }
            }
            else -> {
                deviceFix = null
                mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(validResponderPoints), true, 64)
                locationStatus = "Showing field-unit locations: $freshResponderCount fresh, $agingResponderCount aging, $staleResponderCount stale. Stale pins are not live."
            }
        }
    }

    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            retryLocationAfterSettings = true
        } else {
            locationStatus = "Location services remain off. Incident pins remain available; enable Location in Quick Settings or try again."
        }
    }

    fun openAppLocationSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                locationStatus = "Could not open TACResQ app settings. Open Android Settings, choose Apps, then TACResQ, and allow Location."
            }
    }

    fun locateOnce() {
        // Claim synchronously before launching: rapid taps must not start overlapping location requests.
        if (isLocationRequestInProgress) return
        isLocationRequestInProgress = true
        scope.launch {
            try {
                deviceFix = null
                showAppSettingsAction = false
                locationStatus = "Checking device location settings…"
                when (val settings = locationProvider.checkLocationSettings()) {
                    LocationSettingsCheck.Ready -> {
                        locationStatus = "Getting a fresh device location…"
                        when (val result = locationProvider.currentFix()) {
                            is LocationCaptureResult.Success -> {
                                showAppSettingsAction = false
                                val fix = result.fix
                                deviceFix = fix
                                // Recenter after the native MapView has processed the latest layout.
                                // Zoom in enough for the user to recognize their own position, and
                                // do this on every Locate me action (not only the first map render).
                                mapView.post {
                                    // Match zoom to the reported uncertainty; avoid implying
                                    // street-level precision when the fix is only approximate.
                                    mapView.controller.setZoom(gpsMapPreviewZoom(fix.accuracyM))
                                    mapView.controller.animateTo(GeoPoint(fix.latitude, fix.longitude))
                                    mapView.invalidate()
                                }
                                val precision = if (fix.hasPrecisePermission) "Precise" else "Approximate"
                                locationStatus = "One-time $precision location preview on this device only; not uploaded. ${gpsAccuracyStatus(fix.accuracyM)}"
                            }
                            LocationCaptureResult.PermissionDenied -> {
                                showAppSettingsAction = true
                                locationStatus = "Location permission is unavailable. Incident pins remain available; allow Location in TACResQ app settings to use Locate me"
                            }
                            LocationCaptureResult.ServicesDisabled -> {
                                locationStatus = "Opening Android Location settings. Turn Location on, return here, and tap Locate me again."
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                }.onFailure {
                                    locationStatus = "Device Location is off. Turn it on in Quick Settings, return here, and tap Locate me again."
                                }
                            }
                            LocationCaptureResult.TimedOut -> {
                                locationStatus = "No fresh location fix arrived in time. Try again."
                            }
                            LocationCaptureResult.InvalidFix -> {
                                locationStatus = "The device returned invalid or stale coordinates. Try again."
                            }
                            LocationCaptureResult.Unavailable -> {
                                locationStatus = "Current location is unavailable. Check signal and try again."
                            }
                        }
                    }
                    is LocationSettingsCheck.NeedsResolution -> {
                        locationStatus = "Android needs your approval to enable the device location settings required for a fresh fix."
                        runCatching {
                            locationSettingsLauncher.launch(
                                IntentSenderRequest.Builder(settings.exception.resolution).build(),
                            )
                        }.onFailure {
                            locationStatus = "Could not open the location settings prompt. Enable Location in Quick Settings and try again."
                        }
                    }
                    LocationSettingsCheck.Unavailable -> {
                        locationStatus = "Could not open the automatic location prompt. Opening Android Location settings instead."
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }.onFailure {
                            locationStatus = "Could not open Location settings. Enable Location in Quick Settings and try again."
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Respect lifecycle cancellation; do not turn it into a location error.
                throw cancelled
            } catch (failure: Exception) {
                // Location providers and OEM settings APIs can throw unexpectedly.
                // Keep the map/incident pins usable and give the user a recoverable status.
                deviceFix = null
                locationStatus = "Could not get your location right now. Incident pins remain available; try again."
            } finally {
                isLocationRequestInProgress = false
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(retryLocationAfterSettings) {
        if (retryLocationAfterSettings) {
            retryLocationAfterSettings = false
            locateOnce()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showAppSettingsAction = false
            locateOnce()
        } else {
            deviceFix = null
            showAppSettingsAction = true
            locationStatus = "Location permission denied. Incident pins remain available; allow Location in TACResQ app settings to use Locate me"
        }
    }
    val onLocateClick: () -> Unit = {
        if (locationProvider.hasPermission()) {
            locateOnce()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    DisposableEffect(mapView, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        var mapIsResumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (!mapIsResumed) {
                        mapView.onResume()
                        mapIsResumed = true
                    }
                    // A one-time preview must not remain visible after the user revokes
                    // location permission in Android Settings while TACResQ is paused.
                    if (!locationProvider.hasPermission() && latestDeviceFix != null) {
                        deviceFix = null
                        locationStatus = "Location permission is off. Previous preview cleared. Grant permission to locate again."
                    }
                    if (locationProvider.hasPermission()) showAppSettingsAction = false
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
        lifecycle.addObserver(observer)
        // Compose can enter this screen after the host Activity has already resumed.
        // Lifecycle observers catch up synchronously on registration, but keep this
        // guard so a resumed map is never left paused if the event was already passed.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !mapIsResumed) {
            mapView.onResume()
            mapIsResumed = true
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (mapIsResumed) {
                mapView.onPause()
                mapIsResumed = false
            }
            mapView.onDetach()
        }
    }

    androidx.compose.runtime.LaunchedEffect(mapView, visibleIncidents, validResponderLocations, deviceFix, freshnessTick) {
        mapView.overlays.removeAll { it is Marker }
        visibleIncidents.forEach { incident ->
            val latitude = incident.latitude
            val longitude = incident.longitude
            if (incident.hasValidMapCoordinates() && latitude != null && longitude != null) {
                val marker = Marker(mapView).apply {
                    position = GeoPoint(latitude, longitude)
                    icon = ContextCompat.getDrawable(context, com.tacresq.app.R.drawable.ic_map_incident_pin)
                    setTitle("Incident #${incident.id} · ${incident.type.label}")
                    val source = when (incident.locationSource) {
                        "gps" -> "GPS location"
                        "manual" -> "Manually selected map pin"
                        "none" -> "No location source"
                        else -> "Location source not reported"
                    }
                    val accuracy = if (incident.locationSource == "gps") {
                        incident.accuracyM.validAccuracyLabel("GPS accuracy about ")
                    } else null
                    val note = incident.locationNotes?.takeIf { it.isNotBlank() }
                    val locationAgeLabel = incident.locationAgeStatus(mapNow).first
                    snippet = listOfNotNull(source, accuracy, "Status: ${incident.status.label}", locationAgeLabel, note)
                        .joinToString(" · ")
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { clickedMarker, map ->
                        selectedIncidentId = incident.id
                        map.controller.animateTo(clickedMarker.position)
                        scope.launch { mapSheetState.bottomSheetState.expand() }
                        true
                    }
                }
                mapView.overlays.add(marker)
            }
        }
        validResponderLocations.forEach { location ->
            val ageSeconds = location.mapAgeSeconds(mapNow) ?: return@forEach
            val freshness = location.mapFreshness(mapNow) ?: return@forEach
            val marker = Marker(mapView).apply {
                position = GeoPoint(location.latitude, location.longitude)
                icon = ContextCompat.getDrawable(context, com.tacresq.app.R.drawable.ic_map_responder_pin)
                    ?.mutate()
                    ?.apply { if (freshness == MapLocationFreshness.STALE) alpha = 110 }
                setTitle(
                    if (freshness == MapLocationFreshness.STALE) {
                        "STALE field-unit location · Incident #${location.incidentId}"
                    } else {
                        "${freshness.label} field-unit location · Incident #${location.incidentId}"
                    },
                )
                val accuracy = location.accuracyM.validAccuracyLabel(" · reported accuracy about ") ?: ""
                val age = formatLocationAge(ageSeconds)
                snippet = if (freshness == MapLocationFreshness.STALE) {
                    "STALE — last known position, $age$accuracy. Do not treat this marker as live; confirm with dispatch."
                } else {
                    "${freshness.label} location · last reported $age$accuracy. Dispatcher-only, consented foreground location."
                }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
        }
        deviceFix?.let { fix ->
            val locationMarker = Marker(mapView).apply {
                position = GeoPoint(fix.latitude, fix.longitude)
                icon = ContextCompat.getDrawable(context, com.tacresq.app.R.drawable.ic_map_my_location_pin)
                setTitle("Your location · this device only")
                snippet = "One-time location preview. Not uploaded or continuously tracked."
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(locationMarker)
        }
        // Frame eligible units once while incident data is absent, without marking
        // incident framing complete. If incident coordinates arrive later, frame them once.
        val shouldFrameInitialUnits = !hasAutoFramedInitialMapPoints &&
            validIncidentPoints.isEmpty() && responderPointsForInitialFrame.isNotEmpty()
        val shouldFrameIncidentPoints = !hasAutoFramedIncidentPoints && validIncidentPoints.isNotEmpty()
        if (deviceFix == null && (shouldFrameInitialUnits || shouldFrameIncidentPoints)) {
            hasAutoFramedInitialMapPoints = true
            if (shouldFrameIncidentPoints) hasAutoFramedIncidentPoints = true
            val pointsToFrame = if (shouldFrameIncidentPoints) initialMapPoints else responderPointsForInitialFrame
            mapView.post {
                when (pointsToFrame.size) {
                    1 -> {
                        mapView.controller.setZoom(15.0)
                        mapView.controller.animateTo(pointsToFrame.first())
                        locationStatus = if (shouldFrameIncidentPoints) {
                            "Showing 1 incident location."
                        } else {
                            "Map centered on field units; incident pins are pending."
                        }
                    }
                    else -> {
                        mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(pointsToFrame), true, 64)
                        locationStatus = if (shouldFrameIncidentPoints) {
                            "Incident locations and eligible field units are in view. Stale units are last known, not live."
                        } else {
                            "Showing eligible field-unit locations while waiting for incident locations. Stale unit markers are labeled as last known, not live."
                        }
                    }
                }
            }
        }
        mapView.invalidate()
    }


    BottomSheetScaffold(
        modifier = Modifier.fillMaxSize().semantics { paneTitle = "GPS Map" },
        scaffoldState = mapSheetState,
        sheetPeekHeight = 120.dp,
        sheetMaxWidth = 680.dp,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetTonalElevation = 2.dp,
        sheetShadowElevation = 8.dp,
        sheetDragHandle = { BottomSheetDefaults.DragHandle() },
        sheetSwipeEnabled = true,
        sheetContent = {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val mapDetailsMaxHeight = (maxHeight * 0.55f).coerceAtMost(420.dp)
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                roleMapTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = collapsedMapSummary,
                                modifier = Modifier.semantics {
                                    if (locationStatus != null || showAppSettingsAction) liveRegion = LiveRegionMode.Polite
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (selectedIncident != null) {
                            androidx.compose.material3.TextButton(
                                onClick = { selectedIncidentId = null },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Clear") }
                        }
                    }

                    if (role == Role.DISPATCHER && validResponderLocations.isNotEmpty()) {
                        Text(
                            text = "Units: $freshResponderCount fresh · $agingResponderCount aging · $staleResponderCount stale",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }

                    if (selectedIncident != null) {
                        MapIncidentCard(
                            incident = selectedIncident,
                            context = context,
                            now = mapNow,
                            onFocusOnMap = ::focusIncidentOnMap,
                        )
                    }

                    if (showAppSettingsAction) {
                        androidx.compose.material3.Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.LocationOn,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        "Location access is off",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "Incident pins remain available. Enable location to center this map on your device.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                androidx.compose.material3.TextButton(
                                    onClick = ::openAppLocationSettings,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("Settings")
                                }
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = mapDetailsMaxHeight),
                        contentPadding = PaddingValues(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "map-key", contentType = "map-info") {
                            Text(
                                if (role == Role.DISPATCHER) {
                                    "Map key · Incident pin: active emergency · Field-unit pin: responder location"
                                } else {
                                    "Map key · Incident pin: active emergency"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (role == Role.DISPATCHER) {
                            item(key = "unit-freshness-help", contentType = "map-info") {
                                Text(
                                    "Fresh under 30s · Aging 30–120s · Stale over 120s. Stale markers are last-known positions, not live.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (deviceFix != null) {
                            item(key = "local-preview-disclosure", contentType = "map-info") {
                                Text(
                                    "One-time preview · stays on this device, not uploaded.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (visibleIncidents.isNotEmpty()) {
                            item(key = "incidents-heading", contentType = "section-heading") {
                                Text(
                                    if (selectedIncident == null) "Incidents" else "Other incidents",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.semantics { heading() },
                                )
                            }
                            itemsIndexed(
                                visibleIncidents.filterNot { it.id == selectedIncidentId },
                                key = { _, incident -> "incident-${incident.id}" },
                                contentType = { _, _ -> "incident" },
                            ) { _, incident ->
                                MapIncidentCard(
                                    incident = incident,
                                    context = context,
                                    now = mapNow,
                                    onFocusOnMap = ::focusIncidentOnMap,
                                )
                            }
                        } else {
                            item(key = "no-incidents", contentType = "map-info") {
                                Text(
                                    emptyMapMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (role == Role.DISPATCHER && validResponderLocations.isNotEmpty()) {
                            item(key = "field-units-heading", contentType = "section-heading") {
                                Text(
                                    "Field-unit locations",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.semantics { heading() },
                                )
                            }
                            itemsIndexed(
                                validResponderLocations,
                                key = { _, location -> "unit-${location.incidentId}-${location.userId}" },
                                contentType = { _, _ -> "field-unit" },
                            ) { _, location ->
                                val ageSeconds = location.mapAgeSeconds(mapNow)
                                val freshness = location.mapFreshness(mapNow)
                                Text(
                                    text = buildString {
                                        append("Responder #${location.userId} · Incident #${location.incidentId} · ")
                                        append(freshness?.label ?: "Freshness unavailable")
                                        if (ageSeconds != null) append(" · last reported ${formatLocationAge(ageSeconds)}")
                                        location.accuracyM.validAccuracyLabel(" · accuracy about ")?.let { append(it) }
                                        if (freshness == MapLocationFreshness.STALE) append(" · last known, not live")
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Text(
                        text = "© OpenStreetMap contributors",
                        modifier = Modifier
                            .align(Alignment.End)
                            .heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Open OpenStreetMap copyright and attribution") {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://www.openstreetmap.org/copyright"),
                                        )
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .semantics { paneTitle = "GPS Map" },
    ) {
        // Use this map window's real constraints (not the device's full screen height).
        // A lower clamp can exceed the available height in landscape or split-screen.
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { it.invalidate() },
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = "Back from GPS map"
                        },
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Text(
                        "GPS Map",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasValidatedInternet == false) {
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            Text(
                                "Offline",
                                modifier = Modifier
                                    .semantics {
                                        contentDescription = "Internet unavailable. Cached map tiles may still appear."
                                    }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                    if (isLocationRequestInProgress) {
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.size(48.dp),
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier
                                    .size(18.dp)
                                    .semantics {
                                        contentDescription = "Getting your location"
                                    },
                                strokeWidth = 2.dp,
                            )
                        }
                    } else if (locationProvider.hasPrecisePermission()) {
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = ::locateOnce,
                            modifier = Modifier.size(48.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.MyLocation,
                                contentDescription = if (deviceFix == null) "Locate me" else "Refresh location",
                            )
                        }
                    } else if (!locationProvider.hasApproximatePermission()) {
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = onLocateClick,
                            modifier = Modifier.size(48.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.MyLocation,
                                contentDescription = if (deviceFix == null) "Locate me" else "Refresh location",
                            )
                        }
                    } else {
                        LocationButton(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "Use precise location for a one-time map preview"
                                },
                            backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                            textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            textType = LocationButtonTextType.None,
                            cornerRadius = 24.dp,
                            onPermissionResult = { granted ->
                                if (granted) {
                                    locateOnce()
                                } else if (locationProvider.hasApproximatePermission()) {
                                    locateOnce()
                                } else {
                                    deviceFix = null
                                    showAppSettingsAction = true
                                    locationStatus = "Location permission not granted. Incident pins remain available; choose a map location manually or allow Location in app settings."
                                }
                            },
                            onError = {
                                locationStatus = "The secure location button could not start. Incident pins remain available; try again."
                            },
                        )
                    }
                }
            }

            GpsMapFilterButtons(
                role = role,
                hasIncidents = validIncidentPoints.isNotEmpty(),
                hasActiveUnits = validResponderLocations.isNotEmpty(),
                onShowIncidents = ::showIncidentPins,
                onShowActiveUnits = ::showActiveUnits,
            )
        }

        // Keep OSM attribution visible on the map even while the sheet is collapsed.
        Text(
            text = "© OpenStreetMap contributors",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 12.dp, bottom = 136.dp)
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = "Open OpenStreetMap copyright and attribution") {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.openstreetmap.org/copyright"),
                            )
                        )
                    }
                }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
      }
    }
}

@Composable
private fun IncidentMapActions(
    incidentId: Long,
    needsConfirmation: Boolean,
    stacked: Boolean,
    onFocus: () -> Unit,
    onNavigate: () -> Unit,
) {
    val focusPadding = PaddingValues(horizontal = if (stacked) 10.dp else 8.dp, vertical = 6.dp)
    val navigatePadding = focusPadding
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IncidentMapFocusButton(incidentId, onFocus, Modifier.fillMaxWidth(), focusPadding)
            IncidentMapNavigateButton(incidentId, needsConfirmation, onNavigate, Modifier.fillMaxWidth(), navigatePadding)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IncidentMapFocusButton(incidentId, onFocus, Modifier.weight(1f), focusPadding)
            IncidentMapNavigateButton(incidentId, needsConfirmation, onNavigate, Modifier.weight(1f), navigatePadding)
        }
    }
}

@Composable
private fun IncidentMapFocusButton(
    incidentId: Long,
    onClick: () -> Unit,
    modifier: Modifier,
    padding: PaddingValues,
) {
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "Show incident #$incidentId on map"
        },
        contentPadding = padding,
    ) {
        Text("Show on map")
    }
}

@Composable
private fun IncidentMapNavigateButton(
    incidentId: Long,
    needsConfirmation: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    padding: PaddingValues,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = if (needsConfirmation) {
                "Review location before navigating to incident #$incidentId"
            } else {
                "Navigate to incident #$incidentId"
            }
        },
        contentPadding = padding,
    ) {
        Text(if (needsConfirmation) "Review location" else "Navigate")
    }
}

@Composable
private fun MapIncidentCard(
    incident: Incident,
    context: Context,
    now: Instant,
    onFocusOnMap: (Incident) -> Unit,
) {
    val lat = incident.latitude
    val lng = incident.longitude
    val hasUsableCoordinates = lat != null && lng != null &&
        lat.isFinite() && lng.isFinite() &&
        lat in -90.0..90.0 && lng in -180.0..180.0
    val (ageLabel, needsConfirmation) = incident.locationAgeStatus(now)
    var showNavigationConfirmation by remember(incident.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Keep the incident title readable beside its status on compact cards.
            // Wider layouts retain the single-line information hierarchy.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 360.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Incident #${incident.id} · ${incident.type.label}",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusPill(incident.status.label, incident.status.pillColor())
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Incident #${incident.id} · ${incident.type.label}",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(incident.status.label, incident.status.pillColor())
                    }
                }
            }
            if (hasUsableCoordinates) {
                Text(
                    ageLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (needsConfirmation) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val locationSourceLabel = when (incident.locationSource) {
                    "gps" -> "GPS"
                    "manual" -> "Manual pin"
                    else -> "Source unavailable"
                }
                val accuracyLabel = if (incident.locationSource == "gps") {
                    incident.accuracyM.validAccuracyLabel(" · accuracy about ").orEmpty()
                } else ""
                Text(
                    locationSourceLabel + accuracyLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val navigateToIncident = {
                    if (needsConfirmation) {
                        showNavigationConfirmation = true
                    } else {
                        openExternalMaps(context, lat!!, lng!!)
                    }
                }
                // Keep the two actions identical across compact and regular widths.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    IncidentMapActions(
                        incidentId = incident.id,
                        needsConfirmation = needsConfirmation,
                        stacked = maxWidth < 320.dp,
                        onFocus = { onFocusOnMap(incident) },
                        onNavigate = navigateToIncident,
                    )
                }
            } else {
                // A landmark-only request is still actionable. Reuse the shared
                // provenance formatter so missing, partial, and invalid pins are distinct.
                val hasLandmarkFallback = lat == null && lng == null &&
                    (incident.locationSource == null || incident.locationSource == "none") &&
                    !incident.locationNotes.isNullOrBlank()
                Text(
                    incidentLocationText(incident),
                    color = if (hasLandmarkFallback) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (showNavigationConfirmation && hasUsableCoordinates) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNavigationConfirmation = false },
            title = { Text("Confirm incident location") },
            text = {
                Text(
                    "This incident location may be outdated or its timestamp cannot be verified. " +
                        ageLabel + " Confirm the location with the requester or dispatcher before proceeding."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showNavigationConfirmation = false
                        openExternalMaps(context, lat!!, lng!!)
                    },
                ) {
                    Text("Navigate anyway")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showNavigationConfirmation = false },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun openExternalMaps(context: Context, latitude: Double, longitude: Double) {
    val navigation = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$latitude,$longitude&mode=d"))
        .setPackage("com.google.android.apps.maps")
    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude"))

    // Some devices block an explicit package launch or have a broken maps handler.
    // Try the generic geo intent after either a missing handler or a security denial.
    val opened = try {
        context.startActivity(navigation)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
    if (opened) return

    val fallbackOpened = try {
        context.startActivity(fallback)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
    if (!fallbackOpened) {
        Toast.makeText(context, "Unable to open a maps app on this phone.", Toast.LENGTH_SHORT).show()
    }
}
