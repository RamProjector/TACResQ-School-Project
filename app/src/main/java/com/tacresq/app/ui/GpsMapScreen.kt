package com.tacresq.app.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.User
import com.tacresq.app.ui.common.StatusPill
import com.tacresq.app.ui.common.formatTime
import com.tacresq.app.ui.common.pillColor
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
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
        Role.REQUESTER -> "Confirmed incident coordinates and response status. Your location is not continuously tracked."
        Role.RESPONDER, Role.DRIVER -> "Assigned incident coordinates only. Live responder tracking appears only when the backend provides a fresh unit fix."
        Role.DISPATCHER -> "Confirmed incident coordinates only. Unit markers appear only when the backend provides a fresh GPS timestamp."
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

    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    androidx.compose.runtime.LaunchedEffect(mapView, visibleIncidents) {
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
                    if (visibleIncidents.any { it.hasValidMapCoordinates() })
                        "Real map · confirmed incident pins only"
                    else "Real map · no confirmed incident pins yet",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Map tiles require an internet connection. No guessed or live-tracked positions are displayed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
