package com.tacresq.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsMapPresentationTest {
    @Test
    fun previewZoomUsesConservativeBandsAtAccuracyBoundaries() {
        assertEquals(16.0, gpsMapPreviewZoom(50f), 0.0)
        assertEquals(15.0, gpsMapPreviewZoom(50.01f), 0.0)
        assertEquals(15.0, gpsMapPreviewZoom(150f), 0.0)
        assertEquals(14.0, gpsMapPreviewZoom(150.01f), 0.0)
        assertEquals(14.0, gpsMapPreviewZoom(500f), 0.0)
        assertEquals(12.0, gpsMapPreviewZoom(500.01f), 0.0)
    }

    @Test
    fun unavailableOrInvalidAccuracyNeverGetsStreetLevelZoom() {
        listOf<Float?>(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            .forEach { accuracy ->
                assertEquals("accuracy=$accuracy", 12.0, gpsMapPreviewZoom(accuracy), 0.0)
                assertTrue(gpsAccuracyStatus(accuracy).contains("Accuracy unavailable"))
            }
    }

    @Test
    fun statusRequiresConfirmationOnlyWhenAccuracyIsWiderThanOneHundredMetres() {
        assertEquals("Estimated accuracy about 100 m.", gpsAccuracyStatus(100f))
        assertTrue(gpsAccuracyStatus(100.1f).contains("Low location accuracy"))
        assertTrue(gpsAccuracyStatus(100.1f).contains("confirm the map position"))
    }

    @Test
    fun veryLargeFiniteAccuracyRemainsConservative() {
        assertEquals(12.0, gpsMapPreviewZoom(Float.MAX_VALUE), 0.0)
        assertTrue(gpsAccuracyStatus(Float.MAX_VALUE).contains("Low location accuracy"))
    }
}
