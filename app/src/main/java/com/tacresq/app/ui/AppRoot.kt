package com.tacresq.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tacresq.app.AppContainer
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.SessionState
import com.tacresq.app.ui.auth.AuthScreen
import com.tacresq.app.ui.dispatcher.DispatcherScreen
import com.tacresq.app.ui.requester.RequesterScreen
import com.tacresq.app.ui.responder.ResponderScreen
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
            ) {
                CircularProgressIndicator()
            }

            SessionState.LoggedOut -> AuthScreen(vm = viewModel(factory = factory))

            is SessionState.LoggedIn -> {
                val user = state.session.user
                // The key includes the user id so a different account never sees the previous one's data.
                when (user.role) {
                    Role.REQUESTER -> RequesterScreen(
                        vm = viewModel(key = "requester-${user.id}", factory = factory),
                        user = user,
                        onLogout = logout,
                    )

                    Role.RESPONDER, Role.DRIVER -> ResponderScreen(
                        vm = viewModel(key = "unit-${user.id}", factory = factory),
                        user = user,
                        onLogout = logout,
                    )

                    Role.DISPATCHER -> DispatcherScreen(
                        vm = viewModel(key = "dispatcher-${user.id}", factory = factory),
                        user = user,
                        onLogout = logout,
                    )
                }
            }
        }
    }
}
