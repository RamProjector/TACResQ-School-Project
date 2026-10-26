package com.tacresq.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Emulator smoke tests for the real Activity + demo backend entry path.
 * These tests do not connect to Supabase or create live incident records.
 */
@RunWith(AndroidJUnit4::class)
class AuthScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun signInScreenExplainsDemoModeAndShowsPrimaryAction() {
        composeRule.onNodeWithText("Welcome back").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
        composeRule.onNodeWithText("DEMO ACCOUNTS").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Debug build only · demo data resets when the app process restarts.",
        ).assertIsDisplayed()
    }

    @Test
    fun registrationModeClearlyStatesRequesterOnlyIntent() {
        composeRule.onNodeWithText("New requester? Create an account").performClick()
        composeRule.onNodeWithText("Create a requester account").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Enter your details to request emergency assistance.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Create account").assertIsDisplayed()
    }

    @Test
    fun emptySignInShowsInlineRequiredFieldErrorsWithoutCallingBackend() {
        composeRule.onNodeWithText("Sign in").performClick()
        composeRule.onNodeWithText("Enter your email address.").assertIsDisplayed()
        composeRule.onNodeWithText("Enter your password.").assertIsDisplayed()
    }

    @Test
    fun demoRequesterCanReachEmergencyHomeAndOpenGpsMap() {
        composeRule.onNodeWithText("Requester").performClick()
        composeRule.onNodeWithText("Sign in").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Start emergency request", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start emergency request", substring = true).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("GPS Map").performClick()
        composeRule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.onNodeWithText("Start emergency request", substring = true).assertIsDisplayed()
    }
}

    @Test
    fun emergencyRequestFormOpensOnlyOnDemandAndCanBeClosedWithoutSending() {
        composeRule.onNodeWithText("Requester").performClick()
        composeRule.onNodeWithText("Sign in").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Start emergency request", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Emergency request").assertDoesNotExist()

        composeRule.onNodeWithText("Start emergency request", substring = true).performClick()
        composeRule.onNodeWithText("Emergency request").assertIsDisplayed()
        composeRule.onNodeWithText("Step 1 of 3 · Details").assertIsDisplayed()
        composeRule.onNodeWithText("What kind of help do you need?").assertIsDisplayed()

        composeRule.onNodeWithText("Continue to location").performClick()
        composeRule.onNodeWithText("Confirm your location").assertIsDisplayed()
        composeRule.onNodeWithText("Add a GPS pin or continue without one.").assertIsDisplayed()

        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("What kind of help do you need?").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()

        composeRule.onNodeWithText("Emergency request").assertDoesNotExist()
        composeRule.onNodeWithText("Start emergency request", substring = true).assertIsDisplayed()
    }
    @Test
    fun demoRequesterCanSubmitOneDemoSosAndSeesActiveRequestInsteadOfAnotherForm() {
        composeRule.onNodeWithText("Requester").performClick()
        composeRule.onNodeWithText("Sign in").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Start emergency request", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start emergency request", substring = true).performClick()
        composeRule.onNodeWithText("Continue to location").performClick()
        composeRule.onNodeWithText("Review request").performClick()
        composeRule.onNodeWithText("Review before sending").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm SOS").performClick()
        composeRule.onNodeWithText("Send emergency request?").assertIsDisplayed()
        composeRule.onNodeWithText("No GPS pin. Dispatch may call to confirm your location.").assertIsDisplayed()
        composeRule.onNodeWithText("Send SOS").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Active emergency").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Active emergency").assertIsDisplayed()
        composeRule.onNodeWithText("Request #", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Start emergency request", substring = true).assertDoesNotExist()
    }
}
