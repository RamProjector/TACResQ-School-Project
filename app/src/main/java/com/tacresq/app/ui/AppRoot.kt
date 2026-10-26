package com.tacresq.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.tacresq.app.ui.notifications.NotificationsViewModel
import com.tacresq.app.ui.notifications.NotificationsAndSafetyScreen
import kotlinx.coroutines.launch

@Composable
fun AppRoot(
    container: AppContainer,
    authReturnMessage: String? = null,
    onAuthReturnMessageDismissed: () -> Unit = {},
) {
    val factory = remember(container) { VmFactory(container) }
    val sessionState by container.repository.sessionState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val logout: () -> Unit = { scope.launch { container.repository.logout() } }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = sessionState) {
            SessionState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "TACResQ",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    CircularProgressIndicator()
                    Text(
                        "Restoring your session…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SessionState.LoggedOut -> AuthScreen(
                vm = viewModel(factory = factory),
                infoMessage = authReturnMessage,
                onInfoMessageDismissed = onAuthReturnMessageDismissed,
            )

            is SessionState.LoggedIn -> {
                val user = state.session.user
                val notificationsVm: NotificationsViewModel = viewModel(
                    key = "notifications-${user.id}",
                    factory = factory,
                )
                when (user.role) {
                    Role.REQUESTER -> {
                        val vm: RequesterViewModel = viewModel(key = "requester-${user.id}", factory = factory)
                        val vmState by vm.state.collectAsStateWithLifecycle()
                        RoleApp(user.role, vmState.incidents, user, container.locationProvider, logout) { destination, openIncident, selectedIncidentId ->
                            if (destination == AppDestination.PRIMARY) RequesterScreen(vm, user, logout)
                            else RoleSectionScreen(
                                destination, user, logout, vmState.incidents,
                                notificationsVm = notificationsVm,
                                onOpenIncident = openIncident,
                                selectedIncidentId = selectedIncidentId,
                            )
                        }
                    }
                    Role.RESPONDER, Role.DRIVER -> {
                        val vm: ResponderViewModel = viewModel(key = "unit-${user.id}", factory = factory)
                        val vmState by vm.state.collectAsStateWithLifecycle()
                        RoleApp(user.role, vmState.incidents, user, container.locationProvider, logout) { destination, openIncident, selectedIncidentId ->
                            if (destination == AppDestination.PRIMARY) ResponderScreen(vm, user, logout)
                            else RoleSectionScreen(
                                destination, user, logout, vmState.incidents,
                                notificationsVm = notificationsVm,
                                onOpenIncident = openIncident,
                                selectedIncidentId = selectedIncidentId,
                            )
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
                        ) { destination, openIncident, selectedIncidentId ->
                            if (destination == AppDestination.PRIMARY) DispatcherScreen(vm, user, logout, focusedIncidentId = selectedIncidentId)
                            else RoleSectionScreen(
                                destination, user, logout, vmState.incidents, vmState.units, notificationsVm,
                                onOpenIncident = openIncident,
                                selectedIncidentId = selectedIncidentId,
                            )
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
    contentFor: @Composable (AppDestination, (Long) -> Unit, Long?) -> Unit,
) {
    var destination by rememberSaveable(role, user.id) { mutableStateOf(AppDestination.PRIMARY) }
    var selectedIncidentId by rememberSaveable(role, user.id) { mutableStateOf<Long?>(null) }
    val openIncident: (Long) -> Unit = { incidentId ->
        selectedIncidentId = incidentId
        destination = if (role == Role.DISPATCHER) AppDestination.PRIMARY else AppDestination.SECONDARY
    }
    RoleShell(
        role = role,
        selected = destination,
        onDestinationSelected = { destination = it },
        content = { contentFor(destination, openIncident, selectedIncidentId) },
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
private fun RoleSectionScreen(
    destination: AppDestination,
    user: User,
    logout: () -> Unit,
    incidents: List<Incident>,
    units: List<User> = emptyList(),
    notificationsVm: NotificationsViewModel,
    onOpenIncident: (Long) -> Unit = {},
    selectedIncidentId: Long? = null,
) {
    if (destination == AppDestination.ALERTS) {
        NotificationsAndSafetyScreen(notificationsVm, user, logout, onOpenIncident)
        return
    }
    val role = user.role
    val isHistory = destination == AppDestination.SECONDARY && role != Role.DISPATCHER
    val title = when (destination) {
        AppDestination.SECONDARY -> when (role) {
            Role.REQUESTER -> "My requests"
            Role.RESPONDER, Role.DRIVER -> "Response history"
            Role.DISPATCHER -> "Field units"
        }
        AppDestination.ALERTS -> "Alerts & safety"
        AppDestination.PROFILE -> "Profile"
        AppDestination.PRIMARY, AppDestination.MAP -> "TACResQ"
    }
    val activeCount = incidents.count { it.status.isActive }
    val completedCount = incidents.count { !it.status.isActive }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(title = title, subtitle = "", onLogout = logout)

        when {
            isHistory -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            if (role == Role.REQUESTER) "Your emergency requests" else "Your response activity",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "$activeCount active · $completedCount completed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (incidents.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (role == Role.REQUESTER)
                                    "When you submit an emergency request, its current status and location details will appear here."
                                else
                                    "Assignments and completed responses will appear here when they are returned by dispatch.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    incidents.sortedByDescending { it.createdAt.orEmpty() }.forEach { incident ->
                        com.tacresq.app.ui.common.IncidentCard(incident)
                    }
                }
            }

            destination == AppDestination.SECONDARY && role == Role.DISPATCHER -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Field units", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            "${units.size} unit profiles returned",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (units.isEmpty()) {
                    EmptySectionCard("No field units returned", "Check the connection and refresh the queue before making staffing decisions.")
                } else {
                    units.forEach { unit ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        unit.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    )
                                    Text(
                                        unit.dutyStatus?.label ?: "Status unavailable",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                Text(unit.role.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                unit.phone?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }

            destination == AppDestination.ALERTS -> Unit

            destination == AppDestination.PROFILE -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Your account", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("Signed in to TACResQ", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProfileDetail("Full name", user.name)
                        ProfileDetail("Email", user.email)
                        ProfileDetail("Role", role.label)
                        user.phone?.takeIf { it.isNotBlank() }?.let { ProfileDetail("Mobile", it) }
                        androidx.compose.material3.HorizontalDivider()
                        androidx.compose.material3.TextButton(
                            onClick = logout,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sign out") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySectionCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProfileDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
