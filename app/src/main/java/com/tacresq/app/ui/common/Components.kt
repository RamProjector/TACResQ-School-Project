package com.tacresq.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
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
fun ScreenHeader(title: String, subtitle: String = "", onOpenNotifications: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                subtitle.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            if (onOpenNotifications != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    IconButton(onClick = onOpenNotifications) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = "Open notifications",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeading(title: String, supportingText: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        supportingText?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                modifier = Modifier.padding(start = 13.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun Banner(text: String, isError: Boolean, onDismiss: (() -> Unit)? = null, isSuccess: Boolean = false) {
    val container = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isSuccess -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isSuccess -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    val icon = when {
        isError -> Icons.Filled.Warning
        isSuccess -> Icons.Filled.CheckCircle
        else -> Icons.Filled.Info
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = content,
            )
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = content),
                ) { Text("Dismiss") }
            }
        }
    }
}

@Composable
fun MapLoadStatusOverlay(isLoading: Boolean, modifier: Modifier = Modifier) {
    if (!isLoading) return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Loading map data…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun StatusPill(status: IncidentStatus) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        IncidentStatus.PENDING -> colors.tertiaryContainer to colors.onTertiaryContainer
        IncidentStatus.ASSIGNED, IncidentStatus.EN_ROUTE -> colors.primaryContainer to colors.onPrimaryContainer
        IncidentStatus.ON_SCENE, IncidentStatus.TRANSPORTING, IncidentStatus.RESOLVED ->
            colors.secondaryContainer to colors.onSecondaryContainer
        IncidentStatus.CANCELLED -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    val animatedContainer by animateColorAsState(
        targetValue = container,
        animationSpec = tween(durationMillis = 300),
        label = "statusPillContainer",
    )
    val animatedContent by animateColorAsState(
        targetValue = content,
        animationSpec = tween(durationMillis = 300),
        label = "statusPillContent",
    )
    Surface(color = animatedContainer, contentColor = animatedContent, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(animatedContent),
            )
            Text(
                status.label,
                color = animatedContent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
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
    selected: Boolean = false,
    statusLabel: String? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 0.dp),
    ) {
        Column {
            // Coloured severity strip at the top of the card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        if (incident.status.isActive) incident.status.pillColor().copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    ),
            )
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Emergency type icon
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = incident.status.pillColor().copy(alpha = 0.12f),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                when (incident.type) {
                                    com.tacresq.app.data.model.IncidentType.MEDICAL -> "🏥"
                                    com.tacresq.app.data.model.IncidentType.FIRE -> "🔥"
                                    com.tacresq.app.data.model.IncidentType.VEHICULAR -> "🚗"
                                    com.tacresq.app.data.model.IncidentType.DISASTER -> "🌊"
                                    com.tacresq.app.data.model.IncidentType.OTHER -> "⚠️"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            incident.type.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        val time = formatTime(incident.createdAt)
                        if (time.isNotEmpty()) {
                            Text(
                                time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (statusLabel == null) StatusPill(incident.status)
                    else StatusPill(statusLabel, incident.status.pillColor())
                }
                // Emergency descriptions may contain critical context. Keep the full text
                // available in the incident card instead of silently truncating it.
                incident.description?.trim()?.takeIf { it.isNotEmpty() }?.let { note ->
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                }
            // Landmark/access details are operational information, not decorative
            // metadata. Keep the complete note visible instead of silently truncating it.
            Text(
                incidentLocationText(incident),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            actions()
            }
        }
    }
}

/** Compact, selectable row used in adaptive incident-history collections. */
@Composable
fun IncidentHistoryListItem(
    incident: Incident,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val statusColor = incident.status.pillColor()
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).selectable(selected = selected, role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.width(4.dp).heightIn(min = 52.dp),
                shape = RoundedCornerShape(4.dp),
                color = statusColor,
            ) {}
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        incident.type.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    StatusPill(incident.status)
                }
                Text(
                    "Incident #${incident.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val submitted = formatTime(incident.createdAt)
                if (submitted.isNotBlank()) {
                    Text(
                        submitted,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    incidentLocationText(incident),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Full incident reading surface; intentionally distinct from the compact list row. */
@Composable
fun IncidentHistoryDetail(
    incident: Incident,
    onOpenConversation: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        incident.type.label,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Incident #${incident.id}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(incident.status.label, incident.status.pillColor())
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = incident.status.pillColor().copy(alpha = 0.10f),
                contentColor = incident.status.pillColor(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("CURRENT STATUS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(incident.status.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            HistoryDetailField("REPORTED", formatTime(incident.createdAt).ifBlank { "Time unavailable" })
            HistoryDetailField("LOCATION", incidentLocationText(incident))
            incident.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
                HistoryDetailField("EMERGENCY DETAILS", it)
            }
            TextButton(onClick = onOpenConversation) {
                Text("Open conversation")
            }
        }
    }
}

@Composable
private fun HistoryDetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

internal fun incidentLocationText(incident: Incident): String {
    val latitude = incident.latitude
    val longitude = incident.longitude
    val note = incident.locationNotes?.trim()?.takeIf { it.isNotEmpty() }?.let { " · " + it }.orEmpty()

    if (latitude == null && longitude == null) {
        return if (incident.locationSource == null || incident.locationSource == "none") {
            "No location pin$note"
        } else {
            "Location needs verification$note"
        }
    }
    if (latitude == null || longitude == null) {
        return "Location coordinates incomplete — verify pin$note"
    }
    if (!latitude.isFinite() || !longitude.isFinite() ||
        latitude !in -90.0..90.0 || longitude !in -180.0..180.0
    ) {
        return "Location coordinates invalid — verify pin$note"
    }

    val source = when (incident.locationSource) {
        "gps" -> "GPS"
        "manual" -> "Map pin"
        else -> "Unverified pin"
    }
    val accuracy = if (incident.locationSource == "gps") {
        incident.accuracyM
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.let { " · ±" + ceil(it.toDouble()).toInt() + " m" }
            ?: " · accuracy unknown"
    } else ""
    val captured = if (incident.locationSource == "gps") {
        incident.locationRecordedAt?.let { formatTime(it) }?.takeIf { it.isNotBlank() }
            ?.let { " · $it" } ?: " · time unverified"
    } else ""
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
fun SyncStatus(
    lastSyncedAtEpochMs: Long?,
    error: String?,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    val syncedTime = lastSyncedAtEpochMs?.let {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
    }
    val statusTint = when {
        error != null -> MaterialTheme.colorScheme.error
        lastSyncedAtEpochMs != null -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    val message = when {
        error != null && syncedTime != null -> "Updates unavailable. Showing data last synced $syncedTime. $error"
        error != null -> "Updates unavailable. No successful sync yet. $error"
        syncedTime != null -> "Last synced $syncedTime"
        else -> "Waiting for the first successful sync\u2026"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (error != null) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (error != null) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusTint),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = if (onAction != null) 2 else 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (onAction != null && actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    enabled = actionEnabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(actionLabel)
                }
            }
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
