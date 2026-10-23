package com.tacresq.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tacresq.app.ui.AppRoot
import com.tacresq.app.ui.theme.TACResQTheme

class MainActivity : ComponentActivity() {
    private var authReturnMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptAuthReturn(intent)
        val container = (application as TacResqApp).container
        setContent {
            TACResQTheme {
                AppRoot(
                    container = container,
                    authReturnMessage = authReturnMessage,
                    onAuthReturnMessageDismissed = { authReturnMessage = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptAuthReturn(intent)
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
