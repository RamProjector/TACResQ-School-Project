package com.tacresq.app.data.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteReroutePolicyTest {
    private val route = RouteResult(
        geometry = listOf(RouteCoordinate(11.0, 125.0), RouteCoordinate(11.0, 125.01)),
        distanceMeters = 1_000.0,
        durationSeconds = 120.0,
        generatedAtEpochMillis = 1_000L,
    )

    @Test
    fun requestsInitialRouteImmediatelyThenHonorsCooldown() {
        val policy = RouteReroutePolicy()
        val origin = RouteCoordinate(11.0, 125.0)

        assertEquals(RouteRequestReason.INITIAL, policy.consider(origin, 10f, null, 1_000L))
        assertNull(policy.consider(origin, 10f, null, 2_000L))
        assertEquals(
            RouteRequestReason.INITIAL,
            policy.consider(origin, 10f, null, 1_000L + RouteReroutePolicy.MIN_REQUEST_INTERVAL_MILLIS),
        )
    }

    @Test
    fun waitsForAnAccurateFixBeforeRequestingTheInitialRoute() {
        val policy = RouteReroutePolicy()
        val origin = RouteCoordinate(11.0, 125.0)

        assertNull(policy.consider(origin, null, null, 1_000L))
        assertNull(policy.consider(origin, 100f, null, 2_000L))
        assertEquals(RouteRequestReason.INITIAL, policy.consider(origin, 50f, null, 3_000L))
    }

    @Test
    fun onRouteAndInaccurateFixesDoNotTriggerReroutes() {
        val policy = RouteReroutePolicy(minRequestIntervalMillis = 0L)
        val origin = RouteCoordinate(11.0, 125.005)
        assertEquals(RouteRequestReason.INITIAL, policy.consider(origin, 5f, null, 1_000L))

        assertNull(policy.consider(origin, 5f, route, 2_000L))
        assertNull(policy.consider(RouteCoordinate(11.002, 125.005), 100f, route, 3_000L))
        assertNull(policy.consider(RouteCoordinate(11.002, 125.005), null, route, 4_000L))
    }

    @Test
    fun requiresTwoAccurateOffRouteFixesBeforeRecalculation() {
        val policy = RouteReroutePolicy(minRequestIntervalMillis = 0L)
        val origin = RouteCoordinate(11.0, 125.0)
        assertEquals(RouteRequestReason.INITIAL, policy.consider(origin, 5f, null, 1_000L))
        val offRoute = RouteCoordinate(11.002, 125.005)

        assertNull(policy.consider(offRoute, 10f, route, 2_000L))
        assertEquals(RouteRequestReason.OFF_ROUTE, policy.consider(offRoute, 10f, route, 3_000L))
        assertNull(policy.consider(offRoute, 10f, route, 4_000L))
    }

    @Test
    fun cooldownLimitsOffRouteReroutesToAtMostOnePerInterval() {
        val policy = RouteReroutePolicy()
        val origin = RouteCoordinate(11.0, 125.0)
        assertEquals(RouteRequestReason.INITIAL, policy.consider(origin, 5f, null, 1_000L))
        val offRoute = RouteCoordinate(11.002, 125.005)

        assertNull(policy.consider(offRoute, 10f, route, 20_000L))
        assertNull(policy.consider(offRoute, 10f, route, 30_000L))
        assertNull(policy.consider(offRoute, 10f, route, 30_000L))
        assertEquals(RouteRequestReason.OFF_ROUTE, policy.consider(offRoute, 10f, route, 31_000L))
        assertNull(policy.consider(offRoute, 10f, route, 32_000L))
    }

    @Test
    fun destinationChangeDoesNotResetTheProviderRequestCooldown() {
        val policy = RouteReroutePolicy()
        val origin = RouteCoordinate(11.0, 125.0)
        assertEquals(RouteRequestReason.INITIAL, policy.consider(origin, 10f, null, 1_000L))

        policy.resetForNewDestination()
        assertNull(policy.consider(origin, 10f, null, 30_000L))
        assertEquals(
            RouteRequestReason.INITIAL,
            policy.consider(origin, 10f, null, 31_000L),
        )
    }

    @Test
    fun repeatedProviderFailuresUseExponentialBackoff() {
        val policy = RouteReroutePolicy()
        val origin = RouteCoordinate(11.0, 125.0)
        assertEquals(RouteRequestReason.INITIAL, policy.consider(origin, 10f, null, 1_000L))
        policy.recordRequestResult(succeeded = false, nowMillis = 1_000L)

        assertNull(policy.consider(origin, 10f, null, 30_999L))
        assertEquals(RouteRequestReason.INITIAL, policy.consider(origin, 10f, null, 31_000L))
        policy.recordRequestResult(succeeded = false, nowMillis = 31_000L)

        assertNull(policy.consider(origin, 10f, null, 90_999L))
        assertEquals(RouteRequestReason.INITIAL, policy.consider(origin, 10f, null, 91_000L))
        policy.recordRequestResult(succeeded = true, nowMillis = 91_000L)
        assertEquals(
            RouteRequestReason.INITIAL,
            policy.consider(origin, 10f, null, 121_000L),
        )
    }

    @Test
    fun distanceUsesRouteSegmentsRatherThanOnlyVertices() {
        val point = RouteCoordinate(11.0, 125.005)
        val distance = RouteReroutePolicy.distanceToRouteMeters(point, route.geometry)
        assertTrue("point lies on segment; distance=$distance", distance < 1.0)

        val offset = RouteReroutePolicy.distanceToRouteMeters(RouteCoordinate(11.001, 125.005), route.geometry)
        assertTrue("one latitude millidegree is about 111 m; distance=$offset", offset in 105.0..120.0)
    }
}
