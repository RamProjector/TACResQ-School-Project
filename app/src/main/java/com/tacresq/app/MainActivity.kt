package com.tacresq.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tacresq.app.notifications.PushNotificationTap
import com.tacresq.app.ui.AppRoot
import com.tacresq.app.ui.theme.TACResQTheme

class MainActivity : ComponentActivity() {
    private var authReturnMessage by mutableStateOf<String?>(null)
    private var notificationTap by mutableStateOf<PushNotificationTap?>(null)

    private companion object {
        const val STATE_NOTIFICATION_ID = "tacresq.state.notification_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptAuthReturn(intent)
        notificationTap = savedInstanceState?.getString(STATE_NOTIFICATION_ID)
            ?.takeIf { it.isNotBlank() && it.length <= 200 }
            ?.let(::PushNotificationTap)
            ?: PushNotificationTap.from(intent)
        val container = (application as TacResqApp).container
        setContent {
            TACResQTheme {
                AppRoot(
                    container = container,
                    authReturnMessage = authReturnMessage,
                    onAuthReturnMessageDismissed = { authReturnMessage = null },
                    notificationTap = notificationTap,
                    onNotificationTapConsumed = { consumeNotificationTap() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptAuthReturn(intent)
        PushNotificationTap.from(intent)?.let { notificationTap = it }
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
