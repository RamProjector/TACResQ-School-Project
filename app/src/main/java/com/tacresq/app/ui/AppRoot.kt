package com.tacresq.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tacresq.app.AppContainer
import com.tacresq.app.location.LocationProvider
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.ResponderLocation
import com.tacresq.app.data.model.SessionState
import com.tacresq.app.data.model.User
import com.tacresq.app.ui.auth.AuthScreen
import com.tacresq.app.ui.common.ScreenHeader
import com.tacresq.app.ui.dispatcher.DispatcherScreen
import com.tacresq.app.ui.dispatcher.DispatcherViewModel
import com.tacresq.app.ui.requester.RequesterScreen
import com.tacresq.app.ui.requester.RequesterViewModel
import com.tacresq.app.ui.responder.ResponderScreen
import com.tacresq.app.ui.responder.ResponderViewModel
import kotlinx.coroutines.launch

@Composable
fun AppRoot(container: AppContainer) {
    val factory = remember(container) { VmFactory(container) }
    val sessionState by container.repository.sessionState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val logout: () -> Unit = { scope.launch { container.repository.logout() } }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = sessionState) {
            SessionState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            SessionState.LoggedOut -> AuthScreen(vm = viewModel(factory = factory))

            is SessionState.LoggedIn -> {
                val user = state.session.user
                when (user.role) {
                    Role.REQUESTER -> {
                        val vm: RequesterViewModel = viewModel(key = "requester-${user.id}", factory = factory)
                        val vmState by vm.state.collectAsStateWithLifecycle()
                        RoleApp(user.role, vmState.incidents, user, container.locationProvider, logout) { destination ->
                            if (destination == AppDestination.PRIMARY) RequesterScreen(vm, user, logout)
                            else RoleSectionScreen(destination, user, logout, vmState.incidents)
                        }
                    }
                    Role.RESPONDER, Role.DRIVER -> {
                        val vm: ResponderViewModel = viewModel(key = "unit-${user.id}", factory = factory)
                        val vmState by vm.state.collectAsStateWithLifecycle()
                        RoleApp(user.role, vmState.incidents, user, container.locationProvider, logout) { destination ->
                            if (destination == AppDestination.PRIMARY) ResponderScreen(vm, user, logout)
                            else RoleSectionScreen(destination, user, logout, vmState.incidents)
                        }
                    }
                    Role.DISPATCHER -> {
                        val vm: DispatcherViewModel = viewModel(key = "dispatcher-${user.id}", factory = factory)
                        val vmState by vm.state.collectAsStateWithLifecycle()
                        RoleApp(
                            role = user.role,
                            incidents = vmState.incidents,
                            user = user,
                            locationProvider = container.locationProvider,
                            logout = logout,
                            responderLocations = vmState.responderLocations,
                            onMapRefresh = vm::refresh,
                        ) { destination ->
                            if (destination == AppDestination.PRIMARY) DispatcherScreen(vm, user, logout)
                            else RoleSectionScreen(destination, user, logout, vmState.incidents, vmState.units)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleApp(
    role: Role,
    incidents: List<Incident>,
    user: User,
    locationProvider: LocationProvider,
    logout: () -> Unit,
    responderLocations: List<ResponderLocation> = emptyList(),
    onMapRefresh: () -> Unit = {},
    contentFor: @Composable (AppDestination) -> Unit,
) {
    var destination by rememberSaveable(role, user.id) { mutableStateOf(AppDestination.PRIMARY) }
    RoleShell(
        role = role,
        selected = destination,
        onDestinationSelected = { destination = it },
        content = { contentFor(destination) },
        mapContent = {
            GpsMapScreen(
                role = role,
                user = user,
                incidents = incidents,
                locationProvider = locationProvider,
                responderLocations = responderLocations,
                onRefreshResponderLocations = onMapRefresh,
                onBack = { destination = AppDestination.PRIMARY },
            )
        },
    )
}

@Composable
private fun RoleSectionScreen(destination: AppDestination, user: User, logout: () -> Unit, incidents: List<Incident>, units: List<User> = emptyList()) {
    val role = user.role
    val title = when (destination) {
        AppDestination.SECONDARY -> when (role) {
            Role.REQUESTER -> "My requests"
            Role.RESPONDER, Role.DRIVER -> "Response history"
            Role.DISPATCHER -> "Field units"
        }
        AppDestination.ALERTS -> "Safety and alerts"
        AppDestination.PROFILE -> when (role) {
            Role.DISPATCHER -> "Activity log"
            else -> "Profile"
        }
        AppDestination.PRIMARY, AppDestination.MAP -> "TACResQ"
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader(
            title = title,
            subtitle = "",
            onLogout = logout,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (destination) {
                    AppDestination.SECONDARY -> {
                        if (role == Role.DISPATCHER) {
                            Text(
                                "${units.size} field units",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    AppDestination.ALERTS -> {
                        Text("Safety information", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Share your location only when needed and verify the incident details before responding.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "For a real emergency, contact local emergency services.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppDestination.PROFILE -> {
                        Text("Account details", style = MaterialTheme.typography.titleLarge)
                        Text(user.name, style = MaterialTheme.typography.titleMedium)
                        Text(user.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Role: ${role.label}")
                        user.phone?.let { Text("Mobile: $it") }
                    }
                    AppDestination.PRIMARY, AppDestination.MAP -> Unit
                }
            }
        }

        if (destination == AppDestination.SECONDARY && role == Role.DISPATCHER) {
            if (units.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("No field units returned", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "No field units available.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                units.forEach { unit ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(unit.name, style = MaterialTheme.typography.titleMedium)
                            Text(unit.role.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "Duty status: ${unit.dutyStatus?.label ?: "Not applicable"}",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            unit.phone?.let { Text("Phone: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }

        if (destination == AppDestination.SECONDARY && role != Role.DISPATCHER) {
            if (incidents.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("No requests yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                        )
                    }
                }
            } else {
                incidents.forEach { incident ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Incident #${incident.id} • ${incident.type.label}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                incident.status.label,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            incident.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            incident.locationNotes?.takeIf { it.isNotBlank() }?.let {
                                Text("Location note: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            val hasCoordinates = incident.latitude != null &&
                                incident.longitude != null &&
                                incident.latitude in -90.0..90.0 &&
                                incident.longitude in -180.0..180.0
                            Text(
                                if (hasCoordinates) "GPS coordinates recorded" else "No valid GPS pin recorded",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            incident.createdAt?.takeIf { it.isNotBlank() }?.let {
                                Text("Submitted: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
