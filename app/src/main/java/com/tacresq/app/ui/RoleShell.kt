package com.tacresq.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tacresq.app.data.model.Role

enum class AppDestination(val label: String) {
    PRIMARY("Home"),
    SECONDARY("Requests"),
    MAP("GPS Map"),
    ALERTS("Safety"),
    PROFILE("Profile"),
}

@Composable
fun RoleShell(
    role: Role,
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    content: @Composable () -> Unit,
    mapContent: @Composable () -> Unit,
) {
    androidx.compose.material3.Scaffold(
        bottomBar = {
            // Keep all five top-level destinations available, including while viewing the map.
            TacruBottomNavigation(
                role = role,
                selected = selected,
                onDestinationSelected = onDestinationSelected,
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (selected == AppDestination.MAP) mapContent() else content()
        }
    }
}

@Composable
private fun TacruBottomNavigation(
    role: Role,
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 400.dp
        // Keep all role-specific labels visible; GPS Map remains a stable, prominent center destination.
        val labels = when (role) {
            Role.REQUESTER -> listOf("Home", "Requests", "Safety", "Profile")
            Role.RESPONDER, Role.DRIVER -> listOf(if (compact) "Call" else "Current call", "History", "Safety", "Profile")
            Role.DISPATCHER -> listOf("Queue", "Units", "Safety", "Profile")
        }
        val destinations = listOf(
            AppDestination.PRIMARY to labels[0],
            AppDestination.SECONDARY to labels[1],
            AppDestination.ALERTS to labels[2],
            AppDestination.PROFILE to labels[3],
        )

        NavigationBar(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 2.dp,
        ) {
            NavigationItem(destinations[0].first, destinations[0].second, selected, onDestinationSelected)
            NavigationItem(destinations[1].first, destinations[1].second, selected, onDestinationSelected)
            GpsMapNavigationItem(selected, onDestinationSelected)
            NavigationItem(destinations[2].first, destinations[2].second, selected, onDestinationSelected)
            NavigationItem(destinations[3].first, destinations[3].second, selected, onDestinationSelected)
        }
    }
}

@Composable
private fun RowScope.NavigationItem(
    destination: AppDestination,
    label: String,
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    val icon = when (destination) {
        AppDestination.PRIMARY -> Icons.Filled.Home
        AppDestination.SECONDARY -> Icons.Filled.List
        AppDestination.ALERTS -> Icons.Filled.Warning
        AppDestination.PROFILE -> Icons.Filled.Person
        AppDestination.MAP -> Icons.Filled.LocationOn
    }
    NavigationDestinationItem(
        destination = destination,
        label = label,
        icon = icon,
        active = selected == destination,
        onClick = { onDestinationSelected(destination) },
    )
}

@Composable
private fun RowScope.GpsMapNavigationItem(
    currentDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    val active = currentDestination == AppDestination.MAP
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable { onDestinationSelected(AppDestination.MAP) }
            .semantics {
                contentDescription = "GPS Map"
                selected = active
                role = SemanticsRole.Tab
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 0.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Surface(
            color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = if (active) 2.dp else 0.dp,
        ) {
            Box(
                modifier = Modifier.size(width = 54.dp, height = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            text = "GPS Map",
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun RowScope.NavigationDestinationItem(
    destination: AppDestination,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                selected = active
                role = SemanticsRole.Tab
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 0.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Surface(
            color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(16.dp),
        ) {
            Box(
                modifier = Modifier.size(width = 48.dp, height = 30.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            text = label,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}
