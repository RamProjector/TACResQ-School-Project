package com.tacresq.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import java.util.Locale

@Composable
fun ScreenHeader(title: String, subtitle: String, onLogout: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onLogout) { Text("Log out") }
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
            if (!note.isNullOrBlank()) Text(note)
            Text(locationText(incident), style = MaterialTheme.typography.bodySmall)
            actions()
        }
    }
}

private fun locationText(incident: Incident): String {
    val coords = String.format(Locale.US, "%.5f, %.5f", incident.latitude, incident.longitude)
    val accuracy = incident.accuracyM?.let { " (within ${it.toInt()} m)" } ?: ""
    return "Location: $coords$accuracy"
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())

/** Formats an ISO 8601 timestamp (as sent by Laravel) in the phone's time zone. */
fun formatTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching { timeFormatter.format(Instant.parse(iso)) }.getOrDefault(iso)
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
