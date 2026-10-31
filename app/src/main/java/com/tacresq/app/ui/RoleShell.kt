package com.tacresq.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
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
    CHAT("Messages"),
    ALERTS("Notifications"),
    PROFILE("Profile"),
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun RoleShell(
    role: Role,
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    content: @Composable () -> Unit,
    mapContent: @Composable () -> Unit,
) {
    // The map remains a dedicated, full-window destination on every form factor.
    // Other destinations switch to a rail only when the window has enough width
    // and height; short landscape and split-screen windows keep the bottom bar.
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val useNavigationRail = windowSizeClass.minWidthDp >= 840 && windowSizeClass.minHeightDp >= 480

    if (selected == AppDestination.MAP) {
        Box(modifier = Modifier.fillMaxSize()) {
            mapContent()
        }
    } else {
        // Keep Scaffold in both modes so system-bar insets and content padding
        // remain consistent when the window crosses the rail breakpoint.
        Scaffold(
            bottomBar = {
                if (!useNavigationRail) {
                    TacruBottomNavigation(
                        role = role,
                        selected = selected,
                        onDestinationSelected = onDestinationSelected,
                    )
                }
            },
        ) { padding ->
            if (useNavigationRail) {
                Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                    TacruNavigationRail(
                        role = role,
                        selected = selected,
                        onDestinationSelected = onDestinationSelected,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        content()
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(padding),
                ) {
                    content()
                }
            }
        }
    }
}

private fun roleNavigationDestinations(role: Role): List<Pair<AppDestination, String>> {
    val labels = when (role) {
        Role.REQUESTER -> listOf("Home", "Requests", "Messages", "Profile")
        Role.RESPONDER, Role.DRIVER -> listOf("Call", "History", "Messages", "Profile")
        Role.DISPATCHER -> listOf("Queue", "Units", "Messages", "Profile")
        Role.ADMIN -> listOf("Admin", "Audit", "Messages", "Profile")
    }
    return listOf(
        AppDestination.PRIMARY to labels[0],
        AppDestination.SECONDARY to labels[1],
        AppDestination.CHAT to labels[2],
        AppDestination.PROFILE to labels[3],
    )
}

@Composable
private fun TacruNavigationRail(
    role: Role,
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        roleNavigationDestinations(role).take(2).forEach { (destination, label) ->
            NavigationRailDestinationItem(destination, label, selected, onDestinationSelected)
        }
        NavigationRailDestinationItem(
            destination = AppDestination.MAP,
            label = "GPS Map",
            selected = selected,
            onDestinationSelected = onDestinationSelected,
            emphasized = true,
        )
        roleNavigationDestinations(role).drop(2).forEach { (destination, label) ->
            NavigationRailDestinationItem(destination, label, selected, onDestinationSelected)
        }
    }
}

@Composable
private fun ColumnScope.NavigationRailDestinationItem(
    destination: AppDestination,
    label: String,
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    emphasized: Boolean = false,
) {
    val icon = when (destination) {
        AppDestination.PRIMARY -> Icons.Filled.Home
        AppDestination.SECONDARY -> Icons.AutoMirrored.Filled.List
        AppDestination.CHAT -> Icons.Filled.Email
        AppDestination.ALERTS -> Icons.Filled.Warning
        AppDestination.PROFILE -> Icons.Filled.Person
        AppDestination.MAP -> Icons.Filled.LocationOn
    }
    NavigationRailItem(
        selected = selected == destination,
        onClick = { onDestinationSelected(destination) },
        icon = {
            if (emphasized) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
                    }
                }
            } else {
                Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
            }
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center) },
        alwaysShowLabel = true,
    )
}

@Composable
private fun TacruBottomNavigation(
    role: Role,
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    val destinations = roleNavigationDestinations(role)
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
        AppDestination.CHAT -> Icons.Filled.Email
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
