package com.tacresq.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
}
