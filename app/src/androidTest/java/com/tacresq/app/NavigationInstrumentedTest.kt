package com.tacresq.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tacresq.app.data.model.Role
import com.tacresq.app.ui.AppDestination
import com.tacresq.app.ui.RoleShell
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Navigation regression tests for the five-destination role shell.
 * The map body is represented by a marker text, so this test does not need GPS,
 * network tiles, or a live incident backend.
 */
@RunWith(AndroidJUnit4::class)
class NavigationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun centeredGpsMapDestinationOpensMapAndReturnsToHome() {
        composeRule.activityRule.scenario.onActivity {
            composeRule.setContent {
                var selected by mutableStateOf(AppDestination.PRIMARY)
                MaterialTheme {
                    RoleShell(
                        role = Role.REQUESTER,
                        selected = selected,
                        onDestinationSelected = { selected = it },
                        content = { Text("Home destination content") },
                        mapContent = { Text("GPS Map destination content") },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Home destination content").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("GPS Map").performClick()
        composeRule.onNodeWithText("GPS Map destination content").assertIsDisplayed()
        composeRule.onNodeWithText("Home destination content").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.onNodeWithText("Home destination content").assertIsDisplayed()
        composeRule.onNodeWithText("GPS Map destination content").assertDoesNotExist()
    }

    @Test
    fun allFiveTopLevelDestinationsRemainVisible() {
        composeRule.activityRule.scenario.onActivity {
            composeRule.setContent {
                MaterialTheme {
                    RoleShell(
                        role = Role.REQUESTER,
                        selected = AppDestination.PRIMARY,
                        onDestinationSelected = {},
                        content = { Text("Home destination content") },
                        mapContent = { Text("GPS Map destination content") },
                    )
                }
            }
        }

        listOf("Home", "Requests", "GPS Map", "Safety", "Profile").forEach { label ->
            composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
        }
    }
}
