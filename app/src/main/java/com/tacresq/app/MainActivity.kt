package com.tacresq.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tacresq.app.ui.AppRoot
import com.tacresq.app.ui.theme.TACResQTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TacResqApp).container
        setContent {
            TACResQTheme {
                AppRoot(container)
            }
        }
    }
}
