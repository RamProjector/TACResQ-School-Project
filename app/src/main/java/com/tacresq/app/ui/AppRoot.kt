package com.tacresq.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
                            else RoleSectionScreen(destination, user.role)
                        }
                    }
                    Role.RESPONDER, Role.DRIVER -> {
                        val vm: ResponderViewModel = viewModel(key = "unit-${user.id}", factory = factory)
                        val vmState by vm.state.collectAsStateWithLifecycle()
                        RoleApp(user.role, vmState.incidents, user, logout) { destination ->
                            if (destination == AppDestination.PRIMARY) ResponderScreen(vm, user, logout)
                            else RoleSectionScreen(destination, user.role)
                        }
                    }
                    Role.DISPATCHER -> {
                        val vm: DispatcherViewModel = viewModel(key = "dispatcher-${user.id}", factory = factory)
                        val vmState by vm.state.collectAsStateWithLifecycle()
                        RoleApp(user.role, vmState.incidents, user, logout) { destination ->
                            if (destination == AppDestination.PRIMARY) DispatcherScreen(vm, user, logout)
                            else RoleSectionScreen(destination, user.role)
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
private fun RoleSectionScreen(destination: AppDestination, role: Role) {
    val title = when (destination) {
        AppDestination.SECONDARY -> when (role) {
            Role.REQUESTER -> "My requests"
            Role.RESPONDER, Role.DRIVER -> "Response history"
            Role.DISPATCHER -> "Field units"
        }
        AppDestination.PROFILE -> when (role) {
            Role.DISPATCHER -> "Activity log"
            else -> "Profile and safety"
        }
        AppDestination.PRIMARY, AppDestination.MAP -> "TACRU"
    }
    val body = when (destination) {
        AppDestination.SECONDARY -> "Review role-specific records while the GPS Map remains available at the center of the navigation bar."
        AppDestination.PROFILE -> "Review account details, location permissions, privacy controls, and sign-out options here."
        AppDestination.PRIMARY, AppDestination.MAP -> ""
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("TACRU", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
