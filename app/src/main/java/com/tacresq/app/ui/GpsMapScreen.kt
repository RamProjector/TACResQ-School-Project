package com.tacresq.app.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import com.tacresq.app.data.model.GeoFix
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.ResponderLocation
import com.tacresq.app.data.model.User
import com.tacresq.app.ui.common.StatusPill
import com.tacresq.app.ui.common.formatTime
import com.tacresq.app.ui.common.pillColor
import com.tacresq.app.location.LocationCaptureResult
import com.tacresq.app.location.LocationSettingsCheck
import com.tacresq.app.location.LocationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import java.util.Locale

private const val TACLOBAN_LATITUDE = 11.2443
private const val TACLOBAN_LONGITUDE = 125.0030

private fun Incident.hasValidMapCoordinates(): Boolean {
    val lat = latitude ?: return false
    val lng = longitude ?: return false
    return lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
}

private fun ResponderLocation.isFreshMapLocation(now: Instant = Instant.now()): Boolean {
    if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
    val recorded = runCatching { Instant.parse(recordedAt) }.getOrNull() ?: return false
    val age = runCatching { Duration.between(recorded, now).seconds }.getOrNull() ?: return false
    return age in 0L..120L
}

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
    var deviceFix by remember { mutableStateOf<GeoFix?>(null) }
    var hasAutoFramedIncidents by remember { mutableStateOf(false) }
    var freshnessTick by remember { mutableStateOf(0) }
    var locationStatus by remember {
        mutableStateOf("Your location is hidden until you tap Locate me.")
    }
    var retryLocationAfterSettings by remember { mutableStateOf(false) }
    val visibleIncidents = when (role) {
        Role.REQUESTER, Role.RESPONDER, Role.DRIVER -> incidents.filter { it.status.isActive }
        Role.DISPATCHER -> incidents.filter { it.status.isActive || it.status.name == "PENDING" }
    }
    val title = "GPS Map"
    val description = "Confirmed incident pins. Tap Locate me to preview your current location."
    val validIncidentPoints = visibleIncidents.mapNotNull { incident ->
        val lat = incident.latitude
        val lng = incident.longitude
        if (incident.hasValidMapCoordinates() && lat != null && lng != null) {
            GeoPoint(lat, lng)
        } else {
            null
        }
    }
    val freshResponderLocations = if (role == Role.DISPATCHER) responderLocations.filter { it.isFreshMapLocation() } else emptyList()
    val hasConfirmedIncidentCoordinates = visibleIncidents.any { it.hasValidMapCoordinates() }
    val validResponderPoints = freshResponderLocations.map { GeoPoint(it.latitude, it.longitude) }
    val initialMapPoints = if (role == Role.DISPATCHER) validIncidentPoints + validResponderPoints else validIncidentPoints

    androidx.compose.runtime.LaunchedEffect(role, user.id) {
        if (role == Role.DISPATCHER) {
            while (true) {
                onRefreshResponderLocations()
                delay(15_000)
                freshnessTick += 1
            }
        }
    }

    val mapView = remember(context) {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
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
                locationStatus = "Centered on the one confirmed incident pin. Tap Locate me to return to your device location."
            }
            else -> {
                deviceFix = null
                mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(validIncidentPoints), true, 64)
                locationStatus = "Showing all ${validIncidentPoints.size} confirmed incident pins. Tap Locate me to return to your device location."
            }
        }
    }

    fun showActiveUnits() {
        when (validResponderPoints.size) {
            0 -> locationStatus = "No fresh field-unit location reports are available. Units must be en route and explicitly sharing foreground location."
            1 -> {
                deviceFix = null
                mapView.controller.setZoom(15.0)
                mapView.controller.animateTo(validResponderPoints.first())
                locationStatus = "Centered on the latest reported field-unit location."
            }
            else -> {
                deviceFix = null
                mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(validResponderPoints), true, 64)
                locationStatus = "Showing all ${validResponderPoints.size} fresh field-unit location reports."
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

    fun locateOnce() {
        scope.launch {
            deviceFix = null
            locationStatus = "Checking device location settings…"
            when (val settings = locationProvider.checkLocationSettings()) {
                LocationSettingsCheck.Ready -> {
                    locationStatus = "Getting a fresh device location…"
                    when (val result = locationProvider.currentFix()) {
                        is LocationCaptureResult.Success -> {
                            val fix = result.fix
                            deviceFix = fix
                            mapView.controller.animateTo(GeoPoint(fix.latitude, fix.longitude))
                            val precision = if (fix.hasPrecisePermission) "Precise" else "Approximate"
                            val accuracy = fix.accuracyM?.let { " · accuracy about ${it.roundToInt()} m" } ?: ""
                            locationStatus = "One-time $precision location shown on this device only; not uploaded$accuracy."
                        }
                        LocationCaptureResult.PermissionDenied -> {
                            locationStatus = "Location permission denied. Incident pins remain available."
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
            locateOnce()
        } else {
            deviceFix = null
            locationStatus = "Location permission denied. Incident pins remain available."
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

    androidx.compose.runtime.LaunchedEffect(mapView, visibleIncidents, freshResponderLocations, deviceFix, freshnessTick) {
        mapView.overlays.removeAll { it is Marker }
        visibleIncidents.forEach { incident ->
            val latitude = incident.latitude
            val longitude = incident.longitude
            if (incident.hasValidMapCoordinates() && latitude != null && longitude != null) {
                val marker = Marker(mapView).apply {
                    position = GeoPoint(latitude, longitude)
                    icon = context.getDrawable(com.tacresq.app.R.drawable.ic_map_incident_pin)
                    setTitle("Incident #${incident.id} · ${incident.type.label}")
                    snippet = incident.locationNotes?.takeIf { it.isNotBlank() }
                        ?: "Status: ${incident.status.label}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mapView.overlays.add(marker)
            }
        }
        freshResponderLocations.forEach { location ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(location.latitude, location.longitude)
                icon = context.getDrawable(com.tacresq.app.R.drawable.ic_map_responder_pin)
                setTitle("Field unit · Incident #${location.incidentId}")
                val accuracy = location.accuracyM?.let { " · accuracy about ${it.roundToInt()} m" } ?: ""
                snippet = "Last reported ${location.recordedAt}$accuracy. Dispatcher-only, consented foreground location."
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
        }
        deviceFix?.let { fix ->
            val locationMarker = Marker(mapView).apply {
                position = GeoPoint(fix.latitude, fix.longitude)
                icon = context.getDrawable(com.tacresq.app.R.drawable.ic_map_my_location_pin)
                setTitle("Your location · this device only")
                snippet = "One-time location preview. Not uploaded or continuously tracked."
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(locationMarker)
        }
        if (!hasAutoFramedIncidents && deviceFix == null && initialMapPoints.isNotEmpty()) {
            // Include fresh dispatcher unit points so markers are discoverable even when far from incidents.
            hasAutoFramedIncidents = true
            mapView.post {
                when (initialMapPoints.size) {
                    1 -> {
                        mapView.controller.setZoom(15.0)
                        mapView.controller.animateTo(initialMapPoints.first())
                        locationStatus = "Centered on the available incident or field-unit pin."
                    }
                    else -> {
                        mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(initialMapPoints), true, 64)
                        locationStatus = "Showing available incident pins and fresh field-unit locations."
                    }
                }
            }
        }
        mapView.invalidate()
    }

    var detailsExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { it.invalidate() },
        )

        // Compact floating title: keep the map visible beneath it.
        androidx.compose.material3.Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 10.dp, end = 12.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("GPS MAP", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            deviceFix != null -> "Location preview active"
                            validIncidentPoints.isNotEmpty() -> "${validIncidentPoints.size} confirmed pin(s)"
                            else -> "No confirmed pins"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "Close map",
                    )
                }
            }
        }

        // Map actions float independently instead of consuming a full-width toolbar.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .padding(bottom = 74.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            androidx.compose.material3.FloatingActionButton(
                onClick = onLocateClick,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.MyLocation,
                    contentDescription = if (deviceFix == null) "Locate me" else "Refresh location",
                )
            }
            androidx.compose.material3.FloatingActionButton(
                onClick = ::showIncidentPins,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.LocationOn,
                    contentDescription = "Show incident pins",
                )
            }
            if (role == Role.DISPATCHER) {
                androidx.compose.material3.FloatingActionButton(
                    onClick = ::showActiveUnits,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Groups,
                        contentDescription = "Show active units",
                    )
                }
            }
        }

        // A collapsed status capsule leaves the map as the main visual surface.
        androidx.compose.material3.Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            tonalElevation = 5.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when {
                                deviceFix != null -> "Location preview ready"
                                role == Role.DISPATCHER && freshResponderLocations.isNotEmpty() ->
                                    "${freshResponderLocations.size} fresh field unit(s)"
                                validIncidentPoints.isNotEmpty() -> "${validIncidentPoints.size} confirmed incident(s)"
                                else -> "Map ready"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            locationStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (detailsExpanded) 4 else 1,
                        )
                    }
                    androidx.compose.material3.IconButton(onClick = { detailsExpanded = !detailsExpanded }) {
                        androidx.compose.material3.Icon(
                            imageVector = if (detailsExpanded)
                                androidx.compose.material.icons.Icons.Default.ExpandMore
                            else androidx.compose.material.icons.Icons.Default.ExpandLess,
                            contentDescription = if (detailsExpanded) "Collapse map details" else "Expand map details",
                        )
                    }
                }

                if (detailsExpanded) {
                    Text(description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Map tiles need internet. Your one-time location preview stays on this device; continuous tracking is not started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (visibleIncidents.isEmpty()) {
                        Text("No active incidents are available. Pins appear only when the service returns valid coordinates.",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(
                            modifier = Modifier.height(132.dp),
                            contentPadding = PaddingValues(vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(visibleIncidents, key = { it.id }) { incident ->
                                MapIncidentCard(incident = incident, context = context)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapIncidentCard(incident: Incident, context: Context) {
    val lat = incident.latitude
    val lng = incident.longitude
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Incident #${incident.id}", fontWeight = FontWeight.Bold)
                StatusPill(incident.status.label, incident.status.pillColor())
            }
            Text(incident.type.label, style = MaterialTheme.typography.titleMedium)
            if (lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) {
                Text(String.format(Locale.US, "%.5f, %.5f", lat, lng), style = MaterialTheme.typography.bodySmall)
                incident.locationRecordedAt?.let { Text("GPS recorded ${formatTime(it)}", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { openExternalMaps(context, lat, lng) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open turn-by-turn maps")
                }
            } else {
                Text("No valid GPS pin was shared for this incident.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun openExternalMaps(context: Context, latitude: Double, longitude: Double) {
    val navigation = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$latitude,$longitude&mode=d"))
        .setPackage("com.google.android.apps.maps")
    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude"))
    try {
        context.startActivity(navigation)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(fallback)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No maps app found on this phone.", Toast.LENGTH_SHORT).show()
        }
    }
}
