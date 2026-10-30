package com.tacresq.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tacresq.app.AppConfig
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil

@Composable
fun ScreenHeader(title: String, subtitle: String = "") {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        subtitle.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionHeading(title: String, supportingText: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        supportingText?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Banner(text: String, isError: Boolean, onDismiss: (() -> Unit)? = null) {
    val container =
        if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content =
        if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
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
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
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
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
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
            if (time.isNotEmpty()) {
                Text("Submitted $time", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            incident.description?.trim()?.takeIf { it.isNotEmpty() }?.let { note ->
                Text(note, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text(
                locationText(incident),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            actions()
        }
    }
}

private fun locationText(incident: Incident): String {
    val latitude = incident.latitude
    val longitude = incident.longitude
    if (latitude == null || longitude == null) {
        return if (incident.locationSource in listOf(null, "none")) "No location pin"
        else "Location needs verification"
    }
    val source = when (incident.locationSource) {
        "gps" -> "GPS"
        "manual" -> "Map pin"
        else -> "Unverified pin"
    }
    val accuracy = if (incident.locationSource == "gps") {
        incident.accuracyM?.let { " · ±" + ceil(it.toDouble()).toInt() + " m" } ?: " · accuracy unknown"
    } else ""
    val captured = if (incident.locationSource == "gps") {
        incident.locationRecordedAt?.let { formatTime(it) }?.takeIf { it.isNotBlank() }
            ?.let { " · $it" } ?: " · time unverified"
    } else ""
    val note = incident.locationNotes?.trim()?.takeIf { it.isNotEmpty() }?.let { " · " + it }.orEmpty()
    return "$source$accuracy$captured$note"
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (error != null) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (error != null) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
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
