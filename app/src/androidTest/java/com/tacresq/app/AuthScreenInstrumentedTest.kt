package com.tacresq.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
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

    private fun signInAsDemoRequester() {
        // The Activity and demo session may survive between test methods. Normalize whichever
        // auth/home state is present instead of assuming every test starts at the role chooser.
        val homeVisible = composeRule.onAllNodesWithText(
            "Request emergency help",
            substring = true,
        ).fetchSemanticsNodes().isNotEmpty()
        if (homeVisible) return

        if (composeRule.onAllNodesWithText("Create a requester account")
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            composeRule.onNodeWithText("I already have an account").performClick()
        }
        if (composeRule.onAllNodesWithText("Requester").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Requester").performClick()
        }
        if (composeRule.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Sign in").performClick()
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(
                "Request emergency help",
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

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
        signInAsDemoRequester()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Request emergency help", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Request emergency help", substring = true).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("GPS Map").performClick()
        composeRule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.onNodeWithText("Request emergency help", substring = true).assertIsDisplayed()
    }

    @Test
    fun emergencyRequestFormOpensOnlyOnDemandAndCanBeClosedWithoutSending() {
        signInAsDemoRequester()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Request emergency help", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("Emergency request").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithText("Request emergency help", substring = true).performClick()
        composeRule.onNodeWithText("Emergency request").assertIsDisplayed()
        composeRule.onNodeWithText("Step 1 of 3 · Details").assertIsDisplayed()
        composeRule.onNodeWithText("Emergency type (required)").assertIsDisplayed()

        composeRule.onNodeWithText("Medical").performClick()
        composeRule.onNodeWithText("Medical").performClick()
        composeRule.onNodeWithText("Continue").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Confirm your location").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Confirm your location").assertIsDisplayed()
        composeRule.onNodeWithText("Landmark or access detail (required without a pin)").performTextInput("Near Tacloban City Hall")

        // Avoid coupling this smoke test to explanatory copy. The important contract is that
        // the location step renders, offers a safe route forward, and can return to details.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Review request").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Review request").assertIsDisplayed()
        composeRule.onNodeWithText("Back").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Emergency type (required)").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Emergency type (required)").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("Discard this request draft?").assertIsDisplayed()
        composeRule.onNodeWithText("Discard draft").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Emergency request").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("Request emergency help", substring = true).assertIsDisplayed()
    }

    @Test
    fun demoRequesterCanSubmitOneDemoSosAndSeesActiveRequestInsteadOfAnotherForm() {
        signInAsDemoRequester()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Request emergency help", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Request emergency help", substring = true).performClick()
        composeRule.onNodeWithText("Continue").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Confirm your location").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Landmark or access detail (required without a pin)").performTextInput("Near Tacloban City Hall")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Review request").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Review request").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Review before sending").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Review before sending").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm SOS").performClick()
        composeRule.onNodeWithText("Send emergency request?").assertIsDisplayed()
        composeRule.onNodeWithText("No GPS pin. Dispatch may call to confirm the location.").assertIsDisplayed()
        composeRule.onNodeWithText("Send SOS").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Active emergency").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Active emergency").assertIsDisplayed()
        composeRule.onNodeWithText("Request #", substring = true).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Request emergency help", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
