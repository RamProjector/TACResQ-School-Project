package com.tacresq.app

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tacresq.app.data.model.Role
import com.tacresq.app.ui.AppDestination
import com.tacresq.app.ui.RoleShell
import org.junit.Assert.assertTrue
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
    val composeRule = createComposeRule()

    @Test
    fun centeredGpsMapDestinationOpensMapAndReturnsToHome() {
        composeRule.setContent {
            var selected by remember { mutableStateOf(AppDestination.PRIMARY) }
            MaterialTheme {
                RoleShell(
                    role = Role.REQUESTER,
                    selected = selected,
                    onDestinationSelected = { selected = it },
                    content = { Text("Home destination content") },
                    mapContent = {
                        ColumnForMapBack(onBack = { selected = AppDestination.PRIMARY })
                    },
                )
            }
        }

        composeRule.onNodeWithText("Home destination content").assertIsDisplayed()
        composeRule.onNodeWithText("Home").assertIsSelected()
        composeRule.onNodeWithText("GPS Map").performClick()
        composeRule.onNodeWithText("GPS Map destination content").assertIsDisplayed()
        composeRule.onNodeWithText("Back to app").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("GPS Map").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Home").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Home destination content").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithText("Back to app").performClick()
        composeRule.onNodeWithText("Home").assertIsSelected()
        composeRule.onNodeWithText("Home destination content").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("GPS Map destination content").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun allFiveTopLevelDestinationsRemainVisible() {
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

        // The current requester shell calls this destination "Alerts"; "Safety" was
        // an obsolete label and made the emulator suite fail despite a valid navigation item.
        listOf("Home", "Requests", "GPS Map", "Alerts", "Profile").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }
}

@androidx.compose.runtime.Composable
private fun ColumnForMapBack(onBack: () -> Unit) {
    Column {
        Text("GPS Map destination content")
        Button(onClick = onBack) { Text("Back to app") }
    }
}
