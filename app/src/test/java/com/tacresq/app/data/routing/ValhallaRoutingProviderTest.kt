package com.tacresq.app.data.routing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class ValhallaRoutingProviderTest {
    @Test
    fun convertsValhallaResponseAndRequestsExplicitCosting() = runBlocking {
        var capturedRequest: Map<String, Any>? = null
        val service = object : ValhallaRoutingService {
            override suspend fun route(request: Map<String, Any>): Response<ValhallaRouteResponse> {
                capturedRequest = request
                return Response.success(
                    ValhallaRouteResponse(
                        trip = ValhallaTrip(
                            summary = ValhallaSummary(lengthKilometers = 1.25, timeSeconds = 240.0),
                            legs = listOf(
                                ValhallaLeg(
                                    encodedShape = "AAAA",
                                    maneuvers = listOf(
                                        ValhallaManeuver("Continue straight", 0.25, 60.0),
                                        ValhallaManeuver(" ", 0.0, 0.0),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
        val now = 1_800_000_000_000L
        val provider = ValhallaRoutingProvider(service) { now }

        val result = provider.route(
            RouteCoordinate(11.2443, 125.0030),
            RouteCoordinate(11.25, 125.01),
            RouteMode.DRIVING,
        )

        assertTrue(result.isSuccess)
        val route = result.getOrThrow()
        assertEquals(2, route.geometry.size)
        assertEquals(1_250.0, route.distanceMeters, 0.001)
        assertEquals(240.0, route.durationSeconds, 0.001)
        assertEquals(1, route.instructions.size)
        assertEquals("Continue straight", route.instructions.single().text)
        assertEquals("auto", capturedRequest?.get("costing"))
        assertEquals("kilometers", capturedRequest?.get("units"))
        assertEquals(2, (capturedRequest?.get("locations") as List<*>).size)
    }

    @Test
    fun rejectsInvalidCoordinatesWithoutCallingService() = runBlocking {
        var called = false
        val service = object : ValhallaRoutingService {
            override suspend fun route(request: Map<String, Any>): Response<ValhallaRouteResponse> {
                called = true
                return Response.success(ValhallaRouteResponse(null))
            }
        }
        val provider = ValhallaRoutingProvider(service)

        val result = provider.route(
            RouteCoordinate(Double.NaN, 125.0),
            RouteCoordinate(11.25, 125.01),
            RouteMode.DRIVING,
        )

        assertFalse(result.isSuccess)
        assertFalse(called)
    }

    @Test
    fun rejectsUnsuccessfulHttpResponse() = runBlocking {
        val service = object : ValhallaRoutingService {
            override suspend fun route(request: Map<String, Any>): Response<ValhallaRouteResponse> =
                Response.error(503, okhttp3.ResponseBody.create(null, "unavailable"))
        }
        val result = ValhallaRoutingProvider(service).route(
            RouteCoordinate(11.2443, 125.0030),
            RouteCoordinate(11.25, 125.01),
            RouteMode.WALKING,
        )

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("503") == true)
    }
}
