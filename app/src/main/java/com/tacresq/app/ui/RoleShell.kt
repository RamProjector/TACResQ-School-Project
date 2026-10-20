package com.tacresq.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
            TacruBottomNavigation(
                role = role,
                selected = selected,
                onDestinationSelected = onDestinationSelected,
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(padding),
        ) {
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
    val secondaryLabel = when (role) {
        Role.REQUESTER -> "Requests"
        Role.RESPONDER, Role.DRIVER -> "History"
        Role.DISPATCHER -> "Units"
    }
    val destinations = listOf(
        AppDestination.PRIMARY to when (role) {
            Role.REQUESTER -> "Home"
            Role.RESPONDER, Role.DRIVER -> "Current call"
            Role.DISPATCHER -> "Queue"
        },
        AppDestination.SECONDARY to secondaryLabel,
        AppDestination.PROFILE to when (role) {
            Role.DISPATCHER -> "Activity"
            else -> "Profile"
        },
    )

    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavigationItem(destinations[0].first, destinations[0].second, selected, onDestinationSelected)
            NavigationItem(destinations[1].first, destinations[1].second, selected, onDestinationSelected)
            GpsMapNavigationItem(selected, onDestinationSelected)
            NavigationItem(destinations[2].first, destinations[2].second, selected, onDestinationSelected)
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
    val active = destination == selected
    Text(
        text = label,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .clickable { onDestinationSelected(destination) }
            .padding(horizontal = 8.dp, vertical = 18.dp),
    )
}

@Composable
private fun GpsMapNavigationItem(
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    val active = selected == AppDestination.MAP
    Surface(
        color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
        contentColor = if (active) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape,
        shadowElevation = 8.dp,
        modifier = Modifier
            .semantics { contentDescription = "Open full-screen GPS map" }
            .clickable { onDestinationSelected(AppDestination.MAP) },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("GPS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
            Text("Map", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}
