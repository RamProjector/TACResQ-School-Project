package com.tacresq.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
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
    Scaffold(
        bottomBar = {
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
    val labels = when (role) {
        Role.REQUESTER -> listOf("Home", "Requests", "Safety", "Profile")
        Role.RESPONDER, Role.DRIVER -> listOf("Current call", "History", "Safety", "Profile")
        Role.DISPATCHER -> listOf("Queue", "Units", "Safety", "Activity")
    }
    val destinations = listOf(
        AppDestination.PRIMARY to labels[0],
        AppDestination.SECONDARY to labels[1],
        AppDestination.ALERTS to labels[2],
        AppDestination.PROFILE to labels[3],
    )

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
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
    val active = destination == selected
    val icon = when (destination) {
        AppDestination.PRIMARY -> Icons.Filled.Home
        AppDestination.SECONDARY -> Icons.Filled.List
        AppDestination.ALERTS -> Icons.Filled.Warning
        AppDestination.PROFILE -> Icons.Filled.Person
        AppDestination.MAP -> Icons.Filled.Map
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable { onDestinationSelected(destination) }
            .semantics { contentDescription = label }
            .padding(horizontal = 1.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.GpsMapNavigationItem(
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    val active = selected == AppDestination.MAP
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable { onDestinationSelected(AppDestination.MAP) }
            .semantics { contentDescription = "Open full-screen GPS map" }
            .padding(horizontal = 2.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            contentColor = if (active) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Map,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                )
                Text("GPS Map", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}
