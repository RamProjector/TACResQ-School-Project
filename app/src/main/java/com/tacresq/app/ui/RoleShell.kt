package com.tacresq.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
            // Material 3 supplies a consistent selected indicator, label semantics, and touch target.
            TacruBottomNavigation(
                role = role,
                selected = selected,
                onDestinationSelected = onDestinationSelected,
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
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
        // Shorten only labels that would crowd a narrow phone; never hide inactive labels.
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
            GpsMapNavigationItem(selected, onDestinationSelected, compact)
            NavigationItem(destinations[2].first, destinations[2].second, selected, onDestinationSelected)
            NavigationItem(destinations[3].first, destinations[3].second, selected, onDestinationSelected)
        }
    }
}

@Composable
private fun NavigationItem(
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
    NavigationBarItem(
        selected = selected == destination,
        onClick = { onDestinationSelected(destination) },
        icon = { androidx.compose.material3.Icon(imageVector = icon, contentDescription = null) },
        label = { androidx.compose.material3.Text(text = label, maxLines = 1, softWrap = false) },
        alwaysShowLabel = true,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun GpsMapNavigationItem(
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    compact: Boolean,
) {
    NavigationBarItem(
        selected = selected == AppDestination.MAP,
        onClick = { onDestinationSelected(AppDestination.MAP) },
        icon = {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
            )
        },
        label = {
            androidx.compose.material3.Text(
                text = if (compact) "Map" else "GPS Map",
                maxLines = 1,
                softWrap = false,
            )
        },
        alwaysShowLabel = true,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
