package com.tacresq.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNavigationPolicyTest {
    @Test
    fun compactWindowsKeepBottomNavigation() {
        assertFalse(shouldUseNavigationRail(minWidthDp = 599, minHeightDp = 800))
        assertFalse(shouldUseNavigationRail(minWidthDp = 840, minHeightDp = 479))
    }

    @Test
    fun mediumAndExpandedWindowsUseNavigationRailWhenHeightAllows() {
        assertTrue(shouldUseNavigationRail(minWidthDp = 600, minHeightDp = 480))
        assertTrue(shouldUseNavigationRail(minWidthDp = 839, minHeightDp = 800))
        assertTrue(shouldUseNavigationRail(minWidthDp = 840, minHeightDp = 480))
    }

    @Test
    fun shortWideWindowsKeepBottomNavigation() {
        assertFalse(shouldUseNavigationRail(minWidthDp = 900, minHeightDp = 400))
    }
}
