package com.tacresq.app.data.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingModelsTest {
    @Test
    fun coordinateRejectsNonFiniteAndOutOfRangeValues() {
        assertTrue(RouteCoordinate(11.2443, 125.0030).isValid())
        assertFalse(RouteCoordinate(Double.NaN, 125.0).isValid())
        assertFalse(RouteCoordinate(91.0, 125.0).isValid())
        assertFalse(RouteCoordinate(11.0, 181.0).isValid())
    }

    @Test
    fun routeRequiresValidGeometryAndPositiveFiniteMetrics() {
        val now = 1_800_000_000_000L
        val valid = RouteResult(
            geometry = listOf(RouteCoordinate(11.24, 125.0), RouteCoordinate(11.25, 125.01)),
            distanceMeters = 1_200.0,
            durationSeconds = 240.0,
            generatedAtEpochMillis = now,
        )
        assertTrue(valid.isUsable(now))
        assertFalse(valid.copy(geometry = emptyList()).isUsable(now))
        assertFalse(valid.copy(distanceMeters = Double.NaN).isUsable(now))
        assertFalse(valid.copy(durationSeconds = 0.0).isUsable(now))
    }

    @Test
    fun routeRejectsFutureOrStaleResults() {
        val now = 1_800_000_000_000L
        val route = RouteResult(
            geometry = listOf(RouteCoordinate(11.24, 125.0), RouteCoordinate(11.25, 125.01)),
            distanceMeters = 1_200.0,
            durationSeconds = 240.0,
            generatedAtEpochMillis = now,
        )
        assertFalse(route.isUsable(now, maxAgeMillis = -1L))
        assertFalse(route.copy(generatedAtEpochMillis = now + 1).isUsable(now))
        assertFalse(route.copy(generatedAtEpochMillis = now - 120_001).isUsable(now))
        assertTrue(route.copy(generatedAtEpochMillis = now - 120_000).isUsable(now))
    }

    @Test
    fun routeRejectsExtremeOldTimestampInsteadOfOverflowingFreshnessCheck() {
        val now = 1_800_000_000_000L
        val route = RouteResult(
            geometry = listOf(RouteCoordinate(11.24, 125.0), RouteCoordinate(11.25, 125.01)),
            distanceMeters = 1_200.0,
            durationSeconds = 240.0,
            generatedAtEpochMillis = Long.MIN_VALUE,
        )

        assertFalse(route.isUsable(now))
    }

    @Test
    fun polyline6DecoderReturnsCoordinatesAtSixDecimalPrecision() {
        val points = ValhallaPolylineDecoder.decode("AAAA")
        assertEquals(2, points.size)
        // Each 'A' encodes a +1 delta at polyline6 precision.
        assertEquals(0.000001, points[0].latitude, 0.0000000001)
        assertEquals(0.000001, points[0].longitude, 0.0000000001)
        assertEquals(0.000002, points[1].latitude, 0.0000000001)
        assertEquals(0.000002, points[1].longitude, 0.0000000001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun polyline6DecoderRejectsTruncatedGeometry() {
        ValhallaPolylineDecoder.decode("A")
    }

    @Test(expected = IllegalArgumentException::class)
    fun polyline6DecoderRejectsSinglePointGeometry() {
        ValhallaPolylineDecoder.decode("AA")
    }

    @Test
    fun modesMapToExplicitValhallaCostingNames() {
        assertEquals("auto", RouteMode.DRIVING.valhallaCosting())
        assertEquals("pedestrian", RouteMode.WALKING.valhallaCosting())
    }
}
