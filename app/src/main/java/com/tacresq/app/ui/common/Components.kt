package com.tacresq.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tacresq.app.AppConfig
import com.tacresq.app.R
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil

@Composable
fun ScreenHeader(title: String, subtitle: String = "", onLogout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.tacresq_app_icon),
                contentDescription = "TACResQ",
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "TACResQ",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Emergency assistance, coordinated.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onLogout) { Text("Sign out") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun Banner(text: String, isError: Boolean, onDismiss: (() -> Unit)? = null) {
    val container =
        if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content =
        if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

fun IncidentStatus.pillColor(): Color = when (this) {
    IncidentStatus.PENDING -> Color(0xFFB26A00)
    IncidentStatus.ASSIGNED -> Color(0xFF1565C0)
    IncidentStatus.EN_ROUTE -> Color(0xFF3949AB)
    IncidentStatus.ON_SCENE -> Color(0xFF6A1B9A)
    IncidentStatus.TRANSPORTING -> Color(0xFF00796B)
    IncidentStatus.RESOLVED -> Color(0xFF2E7D32)
    IncidentStatus.CANCELLED -> Color(0xFF616161)
}

/** Button text for moving an incident to this status. */
fun IncidentStatus.actionLabel(): String = when (this) {
    IncidentStatus.EN_ROUTE -> "I'm en route"
    IncidentStatus.ON_SCENE -> "I'm on scene"
    IncidentStatus.TRANSPORTING -> "Transporting"
    IncidentStatus.RESOLVED -> "Mark resolved"
    else -> label
}

@Composable
fun IncidentCard(
    incident: Incident,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    incident.type.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(incident.status.label, incident.status.pillColor())
            }
            val time = formatTime(incident.createdAt)
            if (time.isNotEmpty()) Text(time, style = MaterialTheme.typography.bodySmall)
            val note = incident.description
            if (!note.isNullOrBlank()) {
                Text("Report details", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(note)
            }
            Text(locationText(incident), style = MaterialTheme.typography.bodySmall)
            actions()
        }
    }
}

private fun locationText(incident: Incident): String {
    val latitude = incident.latitude
    val longitude = incident.longitude
    val hasCoordinates = latitude != null && longitude != null
    val coords = when {
        latitude != null && longitude != null -> {
            val label = when (incident.locationSource) {
                "gps" -> "GPS pin"
                "manual" -> "Manual map pin"
                else -> "Location pin (source unverified)"
            }
            String.format(Locale.US, "$label: %.5f, %.5f", latitude, longitude)
        }
        latitude == null && longitude == null && incident.locationSource in listOf(null, "none") ->
            "No location pin was shared"
        else -> "Incomplete location data — do not rely on a map pin"
    }
    val accuracy = if (hasCoordinates && incident.locationSource == "gps") {
        incident.accuracyM?.let { " · reported accuracy ±${ceil(it.toDouble()).toInt()} m" } ?: ""
    } else ""
    val captured = if (hasCoordinates) {
        incident.locationRecordedAt?.let { formatTime(it) }?.takeIf { it.isNotBlank() }
            ?.let { " · captured $it" } ?: " · capture time unavailable; verify with requester"
    } else ""
    val notes = incident.locationNotes?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { "\nLocation note: $it" } ?: ""
    return "$coords$accuracy$captured$notes"
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())

/** Formats an ISO 8601 timestamp (as sent by Laravel) in the phone's time zone. */
fun formatTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching { timeFormatter.format(Instant.parse(iso)) }.getOrDefault(iso)
}

/**
 * Shows when incident data last synchronized successfully and whether the latest refresh failed.
 * The timestamp only advances after successful refreshes.
 */
@Composable
fun SyncStatus(lastSyncedAtEpochMs: Long?, error: String?) {
    val syncedTime = lastSyncedAtEpochMs?.let {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
    }
    val message = when {
        error != null && syncedTime != null -> "Updates unavailable. Showing data last synced $syncedTime. $error"
        error != null -> "Updates unavailable. No successful sync yet. $error"
        syncedTime != null -> "Last synced $syncedTime"
        else -> "Waiting for the first successful sync…"
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Runs [action] now and then every [intervalMs] while the screen is visible.
 * Stand-in for push updates until FCM is added.
 */
@Composable
fun PollWhileStarted(intervalMs: Long = AppConfig.POLL_INTERVAL_MS, action: () -> Unit) {
    val currentAction by rememberUpdatedState(action)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, intervalMs) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                currentAction()
                delay(intervalMs)
            }
        }
    }
}
