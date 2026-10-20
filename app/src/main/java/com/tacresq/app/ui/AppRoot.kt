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
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.Role
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
                        RoleApp(user.role, vmState.incidents, user, logout) { destination ->
                            if (destination == AppDestination.PRIMARY) RequesterScreen(vm, user, logout)
                            else RoleSectionScreen(destination, user, logout, vmState.incidents)
                        }
                    }
                    Role.RESPONDER, Role.DRIVER -> {
                        val vm: ResponderViewModel = viewModel(key = "unit-${user.id}", factory = factory)
                        val vmState by vm.state.collectAsStateWithLifecycle()
                        RoleApp(user.role, vmState.incidents, user, logout) { destination ->
                            if (destination == AppDestination.PRIMARY) ResponderScreen(vm, user, logout)
                            else RoleSectionScreen(destination, user, logout, vmState.incidents)
                        }
                    }
                    Role.DISPATCHER -> {
                        val vm: DispatcherViewModel = viewModel(key = "dispatcher-${user.id}", factory = factory)
                        val vmState by vm.state.collectAsStateWithLifecycle()
                        RoleApp(user.role, vmState.incidents, user, logout) { destination ->
                            if (destination == AppDestination.PRIMARY) DispatcherScreen(vm, user, logout)
                            else RoleSectionScreen(destination, user, logout, vmState.incidents)
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
    logout: () -> Unit,
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
                onBack = { destination = AppDestination.PRIMARY },
            )
        },
    )
}

@Composable
private fun RoleSectionScreen(destination: AppDestination, user: User, logout: () -> Unit, incidents: List<Incident>) {
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = title,
            subtitle = "${user.name} • ${role.label}",
            onLogout = logout,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (destination) {
                    AppDestination.SECONDARY -> {
                        Text("Records", style = MaterialTheme.typography.titleLarge)
                        Text(
                            when (role) {
                                Role.REQUESTER -> "Your submitted emergency requests and their latest statuses will appear here."
                                Role.RESPONDER, Role.DRIVER -> "Incidents assigned to you and completed responses will appear here."
                                Role.DISPATCHER -> "Registered responder and driver units will appear here."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (role == Role.DISPATCHER) "Unit availability is not connected to this view yet." else if (incidents.isEmpty()) "No incident records were returned for this account yet. Create or receive a test incident from Home to verify this list." else "Showing ${incidents.size} incident record(s) from the latest successful refresh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppDestination.ALERTS -> {
                        Text("Safety information", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Share your location only when needed and verify the incident details before responding.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "No live alerts are being simulated on this screen. For a real emergency, contact the appropriate local emergency service directly.",
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
                        Text(
                            "Use Sign out above to end this session on the device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppDestination.PRIMARY, AppDestination.MAP -> Unit
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
                            "When the service returns incidents for this account, they will appear here with their current status. This screen does not create sample records.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
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
