package com.tacresq.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.tacresq.app.ui.admin.AdminAccessScreen
import com.tacresq.app.ui.common.ScreenHeader
import com.tacresq.app.ui.common.SectionHeading
import com.tacresq.app.ui.dispatcher.DispatcherScreen
import com.tacresq.app.ui.dispatcher.DispatcherViewModel
import com.tacresq.app.ui.requester.RequesterScreen
import com.tacresq.app.ui.requester.RequesterViewModel
import com.tacresq.app.ui.responder.ResponderScreen
import com.tacresq.app.ui.responder.ResponderViewModel
import com.tacresq.app.ui.notifications.NotificationsViewModel
import com.tacresq.app.ui.notifications.NotificationsAndSafetyScreen
import com.tacresq.app.ui.chat.IncidentChatViewModel
import com.tacresq.app.ui.chat.IncidentChatScreen
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

            SessionState.RestoreFailed -> Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Card {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Couldn't restore your saved session",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Your saved sign-in data has not been cleared. Check available device storage, then try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        androidx.compose.material3.Button(
                            onClick = { scope.launch { container.repository.retrySessionRestore() } },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text("Retry")
                        }
                    }
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
                val chatVm: IncidentChatViewModel = viewModel(
                    key = "chat-${user.id}",
                    factory = factory,
                )
                when (user.role) {
                    Role.ADMIN -> AdminAccessScreen(user = user, repository = container.repository, onSignOut = logout)
                    Role.REQUESTER -> {
                        val vm: RequesterViewModel = viewModel(key = "requester-${user.id}", factory = factory)
                        val vmState by vm.state.collectAsStateWithLifecycle()
                        RoleApp(user.role, vmState.incidents, user, container.locationProvider, logout) { destination, openIncident, selectedIncidentId, openChat, selectedChatIncidentId, closeChat, openNotifications ->
                            if (selectedChatIncidentId != null) {
                                IncidentChatScreen(chatVm, selectedChatIncidentId, user, onBack = { chatVm.closeConversation(); closeChat() }, onLogout = { chatVm.closeConversation(); logout() }, onOpenNotifications = { chatVm.closeConversation(); openNotifications() })
                            } else if (destination == AppDestination.PRIMARY) RequesterScreen(vm, user, logout, onOpenChat = openChat, onOpenNotifications = openNotifications)
                            else RoleSectionScreen(
                                destination, user, logout, vmState.incidents,
                                notificationsVm = notificationsVm,
                                onOpenIncident = { incidentId -> vm.refresh(); openIncident(incidentId) },
                                onOpenChat = openChat,
                                onOpenNotifications = openNotifications,
                                onRefreshIncidents = vm::refresh,
                                selectedIncidentId = selectedIncidentId,
                            )
                        }
                    }
                    Role.RESPONDER, Role.DRIVER -> {
                        val vm: ResponderViewModel = viewModel(key = "unit-${user.id}", factory = factory)
                        val vmState by vm.state.collectAsStateWithLifecycle()
                        // Stop the responder's foreground location flow before ending the account session.
                        val responderLogout: () -> Unit = {
                            vm.stopTracking("Location sharing stopped because you signed out.")
                            logout()
                        }
                        RoleApp(user.role, vmState.incidents, user, container.locationProvider, responderLogout) { destination, openIncident, selectedIncidentId, openChat, selectedChatIncidentId, closeChat, openNotifications ->
                            if (selectedChatIncidentId != null) {
                                IncidentChatScreen(chatVm, selectedChatIncidentId, user, onBack = { chatVm.closeConversation(); closeChat() }, onLogout = { chatVm.closeConversation(); responderLogout() }, onOpenNotifications = { chatVm.closeConversation(); openNotifications() })
                            } else if (destination == AppDestination.PRIMARY) ResponderScreen(vm, user, responderLogout, onOpenNotifications = openNotifications)
                            else RoleSectionScreen(
                                destination, user, responderLogout, vmState.incidents,
                                notificationsVm = notificationsVm,
                                onOpenIncident = { incidentId -> vm.refresh(); openIncident(incidentId) },
                                onOpenChat = openChat,
                                onOpenNotifications = openNotifications,
                                onRefreshIncidents = vm::refresh,
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
                        ) { destination, openIncident, selectedIncidentId, openChat, selectedChatIncidentId, closeChat, openNotifications ->
                            if (selectedChatIncidentId != null) {
                                IncidentChatScreen(chatVm, selectedChatIncidentId, user, onBack = { chatVm.closeConversation(); closeChat() }, onLogout = { chatVm.closeConversation(); logout() }, onOpenNotifications = { chatVm.closeConversation(); openNotifications() })
                            } else if (destination == AppDestination.PRIMARY) {
                                DispatcherScreen(vm, user, logout, focusedIncidentId = selectedIncidentId, onOpenChat = openChat, onOpenNotifications = openNotifications)
                            } else RoleSectionScreen(
                                destination, user, logout, vmState.incidents, vmState.units, notificationsVm,
                                onOpenIncident = { incidentId -> vm.refresh(); openIncident(incidentId) },
                                onOpenChat = openChat,
                                onOpenNotifications = openNotifications,
                                onRefreshIncidents = vm::refresh,
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
    contentFor: @Composable (AppDestination, (Long) -> Unit, Long?, (Long) -> Unit, Long?, () -> Unit, () -> Unit) -> Unit,
) {
    var destination by rememberSaveable(role, user.id) { mutableStateOf(AppDestination.PRIMARY) }
    var destinationBeforeMap by rememberSaveable(role, user.id) { mutableStateOf(AppDestination.PRIMARY) }
    var selectedIncidentId by rememberSaveable(role, user.id) { mutableStateOf<Long?>(null) }
    var selectedChatIncidentId by rememberSaveable(role, user.id) { mutableStateOf<Long?>(null) }
    val openIncident: (Long) -> Unit = { incidentId ->
        selectedIncidentId = incidentId
        destination = if (role == Role.DISPATCHER) AppDestination.PRIMARY else AppDestination.SECONDARY
    }
    val openChat: (Long) -> Unit = { incidentId ->
        selectedChatIncidentId = incidentId
        destination = if (role == Role.DISPATCHER) AppDestination.PRIMARY else AppDestination.SECONDARY
    }
    val closeChat: () -> Unit = { selectedChatIncidentId = null }
    val openNotifications: () -> Unit = {
        // Leave the chat route before showing Alerts; contentFor prioritizes an open chat.
        selectedChatIncidentId = null
        destination = AppDestination.ALERTS
    }
    RoleShell(
        role = role,
        selected = destination,
        onDestinationSelected = {
            if (it == AppDestination.MAP && destination != AppDestination.MAP) {
                destinationBeforeMap = destination
            }
            destination = it
        },
        content = { contentFor(destination, openIncident, selectedIncidentId, openChat, selectedChatIncidentId, closeChat, openNotifications) },
        mapContent = {
            GpsMapScreen(
                role = role,
                user = user,
                incidents = incidents,
                locationProvider = locationProvider,
                responderLocations = responderLocations,
                onRefreshResponderLocations = onMapRefresh,
                onBack = { destination = destinationBeforeMap },
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
    onRefreshIncidents: () -> Unit = {},
    onOpenChat: (Long) -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    selectedIncidentId: Long? = null,
) {
    if (destination == AppDestination.ALERTS) {
        NotificationsAndSafetyScreen(notificationsVm, user, logout, onOpenIncident, onOpenChat)
        return
    }
    var showSignOutConfirmation by remember(user.id) { mutableStateOf(false) }
    val role = user.role
    val isHistory = destination == AppDestination.SECONDARY && role != Role.DISPATCHER && role != Role.ADMIN
    val title = when (destination) {
        AppDestination.SECONDARY -> when (role) {
            Role.REQUESTER -> "My requests"
            Role.RESPONDER, Role.DRIVER -> "Response history"
            Role.DISPATCHER -> "Field units"
            Role.ADMIN -> "Administrator access"
        }
        AppDestination.ALERTS -> "Alerts & safety"
        AppDestination.PROFILE -> "Profile"
        AppDestination.PRIMARY, AppDestination.MAP -> "TACResQ"
    }
    val activeCount = incidents.count { it.status.isActive }
    val completedCount = incidents.count { !it.status.isActive }

    if (isHistory) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(title = title, subtitle = "", onOpenNotifications = onOpenNotifications)
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
            IncidentHistoryAdaptiveScaffold(
                modifier = Modifier.weight(1f),
                incidents = incidents,
                selectedIncidentId = selectedIncidentId,
                role = role,
                onRefresh = onRefreshIncidents,
                onOpenChat = onOpenChat,
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(title = title, subtitle = "", onOpenNotifications = onOpenNotifications)

        when {
            role == Role.ADMIN -> EmptySectionCard("Administrator access", "Administrative tools are not available in this Android build yet.")
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
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ProfileDetail("Full name", user.name)
                        ProfileDetail("Email", user.email)
                        ProfileDetail("Role", role.label)
                        user.phone?.takeIf { it.isNotBlank() }?.let { ProfileDetail("Mobile", it) }
                        androidx.compose.material3.HorizontalDivider()
                        androidx.compose.material3.OutlinedButton(
                            onClick = { showSignOutConfirmation = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Text("Sign out")
                        }
                    }
                }
                if (showSignOutConfirmation) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showSignOutConfirmation = false },
                        title = { Text("Sign out of TACResQ?") },
                        text = { Text("Your emergency requests will remain active. Signing out only ends this account session.") },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    showSignOutConfirmation = false
                                    logout()
                                },
                            ) { Text("Sign out") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showSignOutConfirmation = false },
                            ) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Keeps the incident collection and selected incident together on large windows,
 * while preserving a navigable single-pane flow on phones and narrow split-screen windows.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun IncidentHistoryAdaptiveScaffold(
    modifier: Modifier = Modifier,
    incidents: List<Incident>,
    selectedIncidentId: Long?,
    role: Role,
    onRefresh: () -> Unit,
    onOpenChat: (Long) -> Unit,
) {
    val adaptiveInfo = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
    val windowSizeClass = adaptiveInfo.windowSizeClass
    val compactHeight = windowSizeClass.minHeightDp < 480
    val baseDirective = calculatePaneScaffoldDirective(adaptiveInfo)
    // A short landscape/split-screen window cannot comfortably host two vertically
    // constrained panes, even when its width would otherwise qualify as expanded.
    val paneDirective = if (compactHeight) {
        baseDirective.copy(maxHorizontalPartitions = 1)
    } else {
        baseDirective
    }
    val navigator = rememberListDetailPaneScaffoldNavigator<Long>(
        scaffoldDirective = paneDirective,
    )
    val horizontalGutter = when {
        windowSizeClass.minWidthDp >= 1600 -> 32.dp
        windowSizeClass.minWidthDp >= 1200 -> 28.dp
        windowSizeClass.minWidthDp >= 840 -> 22.dp
        windowSizeClass.minWidthDp >= 600 -> 18.dp
        else -> 14.dp
    }
    val verticalGutter = if (compactHeight) 8.dp else 12.dp
    val scope = rememberCoroutineScope()
    val detailId = navigator.currentDestination?.contentKey
    val detailIncident = detailId?.let { id -> incidents.firstOrNull { it.id == id } }
    val sortedIncidents = remember(incidents) { incidents.sortedByDescending { it.createdAt.orEmpty() } }

    LaunchedEffect(selectedIncidentId) {
        if (selectedIncidentId != null) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedIncidentId)
        }
    }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier = modifier,
        defaultBackBehavior = BackNavigationBehavior.PopUntilContentChange,
        listPane = {
            AnimatedPane {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = horizontalGutter, vertical = verticalGutter),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
                ) {
                    SectionHeading(
                        if (role == Role.REQUESTER) "Your requests" else "Your responses",
                        "Select an incident to view its details",
                    )
                    if (sortedIncidents.isEmpty()) {
                        EmptySectionCard(
                            "Nothing here yet",
                            if (role == Role.REQUESTER)
                                "When you submit an emergency request, its status and location details will appear here."
                            else
                                "Assignments and completed responses will appear here when they are returned by dispatch.",
                        )
                    } else {
                        sortedIncidents.forEach { incident ->
                            com.tacresq.app.ui.common.IncidentCard(
                                incident,
                                modifier = if (detailId == incident.id) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)
                                } else Modifier,
                            ) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, incident.id)
                                        }
                                    },
                                ) {
                                    Text("View details")
                                }
                            }
                        }
                    }
                }
                }
            }
        },
        detailPane = {
            AnimatedPane {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = horizontalGutter, vertical = verticalGutter),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                navigator.navigateTo(ListDetailPaneScaffoldRole.List, null)
                            }
                        },
                    ) {
                        Text("Back to incident list")
                    }
                    when {
                        detailId == null -> EmptySectionCard(
                            "Select an incident",
                            "Choose an incident from the list to review its current status and details.",
                        )
                        detailIncident != null -> {
                            Text(
                                "Incident #${detailIncident.id}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            )
                            com.tacresq.app.ui.common.IncidentCard(detailIncident) {
                                TextButton(onClick = { onOpenChat(detailIncident.id) }) {
                                    Text("Open conversation")
                                }
                            }
                        }
                        else -> {
                            EmptySectionCard(
                                "Incident not in the current list",
                                "Refresh your role-scoped list before acting on this alert. If the incident still does not appear, it may no longer be available to this account.",
                            )
                            androidx.compose.material3.OutlinedButton(onClick = onRefresh) {
                                Text("Refresh list")
                            }
                        }
                    }
                }
                }
            }
        },
    )
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
