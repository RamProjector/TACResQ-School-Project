package com.tacresq.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
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
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding)) {
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
        tonalElevation = 0.dp,
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
        AppDestination.SECONDARY -> Icons.AutoMirrored.Filled.List
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
    NavigationBarItem(
        selected = active,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                softWrap = true,
                textAlign = TextAlign.Center,
            )
        },
        alwaysShowLabel = true,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = if (emphasized) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
