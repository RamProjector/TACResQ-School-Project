package com.tacresq.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Scaffold
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
    ALERTS("Alerts"),
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
    // The map is a focused destination, not a panel inside the role shell.
    // Let it use the complete app window and provide its own explicit Back action.
    if (selected == AppDestination.MAP) {
        Box(modifier = Modifier.fillMaxSize()) {
            mapContent()
        }
    } else {
        Scaffold(
            bottomBar = {
                TacruBottomNavigation(
                    role = role,
                    selected = selected,
                    onDestinationSelected = onDestinationSelected,
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                content()
            }
        }
    }
}

@Composable
private fun TacruBottomNavigation(
    role: Role,
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    val labels = when (role) {
        Role.REQUESTER -> listOf("Home", "Requests", "Alerts", "Profile")
        Role.RESPONDER, Role.DRIVER -> listOf("Call", "History", "Alerts", "Profile")
        Role.DISPATCHER -> listOf("Queue", "Units", "Alerts", "Profile")
        Role.ADMIN -> listOf("Admin", "Audit", "Alerts", "Profile")
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
        tonalElevation = 1.dp,
    ) {
        NavigationItem(destinations[0].first, destinations[0].second, selected, onDestinationSelected)
        NavigationItem(destinations[1].first, destinations[1].second, selected, onDestinationSelected)
        GpsMapNavigationItem(selected, onDestinationSelected)
        NavigationItem(destinations[2].first, destinations[2].second, selected, onDestinationSelected)
        NavigationItem(destinations[3].first, destinations[3].second, selected, onDestinationSelected)
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
    NavigationDestinationItem(
        destination = AppDestination.MAP,
        label = "GPS Map",
        icon = Icons.Filled.LocationOn,
        active = active,
        emphasized = true,
        onClick = { onDestinationSelected(AppDestination.MAP) },
    )
}

@Composable
private fun RowScope.NavigationDestinationItem(
    destination: AppDestination,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    emphasized: Boolean = false,
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
            color = when {
                emphasized && active -> MaterialTheme.colorScheme.primaryContainer
                emphasized -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                active -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
            contentColor = when {
                emphasized && active -> MaterialTheme.colorScheme.onPrimaryContainer
                emphasized -> MaterialTheme.colorScheme.primary
                active -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
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
            color = if (emphasized || active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}
