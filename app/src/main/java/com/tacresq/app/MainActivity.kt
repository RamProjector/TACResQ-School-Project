package com.tacresq.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.tacresq.app.notifications.PushNotificationTap
import com.tacresq.app.notifications.PushNotificationIdentity
import com.tacresq.app.ui.AppRoot
import com.tacresq.app.ui.theme.TACResQTheme
import com.tacresq.app.ui.theme.ThemeMode
import com.tacresq.app.ui.theme.observeThemeMode
import com.tacresq.app.ui.theme.persistThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var authReturnMessage by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var notificationTap by androidx.compose.runtime.mutableStateOf<PushNotificationTap?>(null)

    private companion object {
        const val STATE_NOTIFICATION_ID = "tacresq.state.notification_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptAuthReturn(intent)
        // A new notification launch intent takes precedence over an older saved tap.
        notificationTap = PushNotificationTap.from(intent)
            ?: savedInstanceState?.getString(STATE_NOTIFICATION_ID)
                ?.takeIf(PushNotificationIdentity::isValidNotificationId)
                ?.let(::PushNotificationTap)
        val container = (application as TacResqApp).container
        val themeModeFlow = observeThemeMode()
        setContent {
            val themeMode by themeModeFlow.collectAsState(initial = ThemeMode.SYSTEM)
            TACResQTheme(themeMode = themeMode) {
                AppRoot(
                    container = container,
                    authReturnMessage = authReturnMessage,
                    onAuthReturnMessageDismissed = { authReturnMessage = null },
                    notificationTap = notificationTap,
                    onNotificationTapConsumed = { consumeNotificationTap() },
                    themeMode = themeMode,
                    onThemeModeChanged = { mode ->
                        lifecycleScope.launch { persistThemeMode(mode) }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptAuthReturn(intent)
        notificationTap = PushNotificationTap.from(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        notificationTap?.let { outState.putString(STATE_NOTIFICATION_ID, it.notificationId) }
        super.onSaveInstanceState(outState)
    }

    private fun consumeNotificationTap() {
        notificationTap = null
        intent?.removeExtra(com.tacresq.app.notifications.TacResqFirebaseMessagingService.EXTRA_NOTIFICATION_ID)
        if (intent?.data?.scheme == "tacresq" && intent?.data?.host == "notification") {
            intent?.data = null
        }
    }

    /**
     * A deep link is untrusted input. We deliberately do not read tokens from its
     * query or fragment and do not create a session from a link alone. The user
     * signs in normally after Supabase has handled the confirmation link.
     */
    private fun acceptAuthReturn(intent: Intent?) {
        authReturnMessage = AuthCallbackMessage.from(intent?.dataString)
    }
}
