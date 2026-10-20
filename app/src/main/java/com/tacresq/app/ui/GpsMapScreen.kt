package com.tacresq.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.User
import com.tacresq.app.ui.common.StatusPill
import com.tacresq.app.ui.common.formatTime
import com.tacresq.app.ui.common.pillColor
import java.util.Locale

@Composable
fun GpsMapScreen(
    role: Role,
    user: User,
    incidents: List<Incident>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
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
        Role.RESPONDER, Role.DRIVER -> "Assigned incident coordinates only. Live responder tracking is not enabled until a fresh unit fix is available."
        Role.DISPATCHER -> "Confirmed incident coordinates only. Unit markers appear only when the backend provides a fresh GPS timestamp."
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("TACRU · GPS MAP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onBack) { Text("Close") }
        }
        Text(description, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp)) {
            MapSurface(incidents = visibleIncidents)
            Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("GPS STATUS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Confirmed coordinates only", fontWeight = FontWeight.SemiBold)
                    Text("No fabricated live positions are shown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (visibleIncidents.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("No confirmed coordinates", fontWeight = FontWeight.SemiBold)
                            Text("When an active incident includes a verified GPS pin, it will appear here.", style = MaterialTheme.typography.bodySmall)
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
private fun MapSurface(incidents: List<Incident>) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFE4EEF0))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val road = Color(0xFFC5D7D8)
            for (x in 0..6) drawLine(road, Offset(x * size.width / 6f, 0f), Offset((x + 2) * size.width / 6f, size.height), strokeWidth = 5f)
            for (y in 1..5) drawLine(road, Offset(0f, y * size.height / 6f), Offset(size.width, (y - 1) * size.height / 6f), strokeWidth = 5f)
            incidents.mapNotNull { incident ->
                val lat = incident.latitude ?: return@mapNotNull null
                val lng = incident.longitude ?: return@mapNotNull null
                val normalizedX = ((lng + 180.0) / 360.0).toFloat().coerceIn(0.12f, 0.88f)
                val normalizedY = ((90.0 - lat) / 180.0).toFloat().coerceIn(0.12f, 0.88f)
                Offset(normalizedX * size.width, normalizedY * size.height)
            }.forEach { point ->
                drawCircle(Color(0xFFB42318), radius = 17f, center = point.copy(x = point.x + 2f, y = point.y + 3f), alpha = .18f)
                drawCircle(Color(0xFFB42318), radius = 9f, center = point)
                drawCircle(Color.White, radius = 3f, center = point)
            }
        }
        Text("MAP VIEW", modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(12.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF53636D), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MapIncidentCard(incident: Incident, context: Context) {
    val lat = incident.latitude
    val lng = incident.longitude
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Incident #${incident.id}", fontWeight = FontWeight.Bold)
                StatusPill(incident.status.label, incident.status.pillColor())
            }
            Text(incident.type.label, style = MaterialTheme.typography.titleMedium)
            if (lat != null && lng != null) {
                Text(String.format(Locale.US, "%.5f, %.5f", lat, lng), style = MaterialTheme.typography.bodySmall)
                incident.locationRecordedAt?.let { Text("GPS recorded ${formatTime(it)}", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { openExternalMaps(context, lat, lng) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open turn-by-turn maps")
                }
            } else {
                Text("No GPS pin was shared for this incident.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
