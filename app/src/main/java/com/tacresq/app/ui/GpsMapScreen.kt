package com.tacresq.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.tacresq.app.data.model.User
import com.tacresq.app.ui.common.StatusPill
import com.tacresq.app.ui.common.formatTime
import com.tacresq.app.ui.common.pillColor
import com.tacresq.app.location.LocationCaptureResult
import com.tacresq.app.location.LocationProvider
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale

private const val TACLOBAN_LATITUDE = 11.2443
private const val TACLOBAN_LONGITUDE = 125.0030

private fun Incident.hasValidMapCoordinates(): Boolean {
    val lat = latitude ?: return false
    val lng = longitude ?: return false
    return lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
}

@Composable
fun GpsMapScreen(
    role: Role,
    user: User,
    incidents: List<Incident>,
    locationProvider: LocationProvider,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var deviceFix by remember { mutableStateOf<GeoFix?>(null) }
    var hasAutoFramedIncidents by remember { mutableStateOf(false) }
    var locationStatus by remember {
        mutableStateOf("Your location is hidden until you tap Locate me.")
    }
    val visibleIncidents = when (role) {
        Role.REQUESTER, Role.RESPONDER, Role.DRIVER -> incidents.filter { it.status.isActive }
        Role.DISPATCHER -> incidents.filter { it.status.isActive || it.status.name == "PENDING" }
    }
    val title = when (role) {
        Role.REQUESTER -> "Your response map"
        Role.RESPONDER, Role.DRIVER -> "Call location map"
        Role.DISPATCHER -> "Operations map"
    }
    val description = when (role) {
        Role.REQUESTER -> "Confirmed incident pins and your optional one-time location preview. The preview is not uploaded."
        Role.RESPONDER, Role.DRIVER -> "Assigned incident pins and your optional one-time location preview. This map does not start live tracking."
        Role.DISPATCHER -> "Confirmed incident pins and your optional one-time location preview. Live field-unit markers are not available here yet."
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

    fun locateOnce() {
        scope.launch {
            deviceFix = null
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
                    locationStatus = "Device location is turned off. Turn it on and try again."
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

    androidx.compose.runtime.LaunchedEffect(mapView, visibleIncidents, deviceFix) {
        mapView.overlays.removeAll { it is Marker }
        visibleIncidents.forEach { incident ->
            val latitude = incident.latitude
            val longitude = incident.longitude
            if (incident.hasValidMapCoordinates() && latitude != null && longitude != null) {
                val marker = Marker(mapView).apply {
                    position = GeoPoint(latitude, longitude)
                    setTitle("Incident #${incident.id} · ${incident.type.label}")
                    snippet = incident.locationNotes?.takeIf { it.isNotBlank() }
                        ?: "Status: ${incident.status.label}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mapView.overlays.add(marker)
            }
        }
        deviceFix?.let { fix ->
            val locationMarker = Marker(mapView).apply {
                position = GeoPoint(fix.latitude, fix.longitude)
                setTitle("Your location · this device only")
                snippet = "One-time location preview. Not uploaded or continuously tracked."
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(locationMarker)
        }
        mapView.invalidate()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("TACRESQ · GPS MAP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onBack) { Text("Close") }
        }
        Text(
            description,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.invalidate()
                },
            )
        }

        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("MAP STATUS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        visibleIncidents.any { it.hasValidMapCoordinates() } && deviceFix != null ->
                            "Real map · incident pins and your device-only location"
                        visibleIncidents.any { it.hasValidMapCoordinates() } ->
                            "Real map · confirmed incident pins only"
                        deviceFix != null -> "Real map · your one-time location only"
                        else -> "Real map · no confirmed incident pins yet"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Map tiles require internet. Your one-time location preview stays on this device; no continuous tracking is started.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    locationStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = ::showIncidentPins,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Show incident pins")
                    }
                    OutlinedButton(
                        onClick = onLocateClick,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (deviceFix == null) "Locate me" else "Refresh location")
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.height(190.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (visibleIncidents.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("No active incidents with coordinates", fontWeight = FontWeight.SemiBold)
                            Text("An incident appears as a pin only after the service returns valid coordinates.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                items(visibleIncidents, key = { it.id }) { incident ->
                    MapIncidentCard(incident = incident, context = context)
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
