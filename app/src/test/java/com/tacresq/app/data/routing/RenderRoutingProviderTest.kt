package com.tacresq.app.data.routing

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class RenderRoutingProviderTest {
    @Test
    fun sendsOnlyIncidentAndFreshOriginWithBearerAndConvertsRoute() = runBlocking {
        var capturedRequest: RenderRouteRequest? = null
        var capturedAuthorization: String? = null
        val service = object : TestRenderRoutingService() {
            override suspend fun routePreview(
                request: RenderRouteRequest,
                authorization: String,
            ): Response<RenderRouteResponse> {
                capturedRequest = request
                capturedAuthorization = authorization
                return Response.success(
                    RenderRouteResponse(
                        geometry = listOf(
                            RenderRoutePoint(11.2443, 125.0030),
                            RenderRoutePoint(11.25, 125.01),
                        ),
                        distanceMeters = 1_250.0,
                        durationSeconds = 240.0,
                        instructions = listOf(RenderRouteInstruction("Turn right", 125.0, 20.0)),
                    ),
                )
            }
        }
        val now = 1_800_000_000_000L
        val provider = RenderRoutingProvider(service, { "supabase-access-token" }) { now }

        val result = provider.route(
            RouteCoordinate(11.2443, 125.0030),
            RouteCoordinate(11.25, 125.01),
            RouteMode.DRIVING,
            incidentId = 42L,
        )

        assertTrue(result.isSuccess)
        val route = result.getOrThrow()
        assertEquals(2, route.geometry.size)
        assertEquals(1_250.0, route.distanceMeters, 0.001)
        assertEquals(240.0, route.durationSeconds, 0.001)
        assertEquals("Turn right", route.instructions.single().text)
        assertEquals(now, route.generatedAtEpochMillis)
        assertEquals(42L, capturedRequest?.incidentId)
        assertEquals(11.2443, capturedRequest?.latitude ?: Double.NaN, 0.000001)
        assertEquals(125.0030, capturedRequest?.longitude ?: Double.NaN, 0.000001)
        assertEquals("Bearer supabase-access-token", capturedAuthorization)
    }

    @Test
    fun doesNotCallServiceWithoutSessionToken() = runBlocking {
        var called = false
        val service = object : TestRenderRoutingService() {
            override suspend fun routePreview(
                request: RenderRouteRequest,
                authorization: String,
            ): Response<RenderRouteResponse> {
                called = true
                return Response.success(emptyRoute())
            }
        }

        val result = RenderRoutingProvider(service, { null }).route(
            RouteCoordinate(11.2443, 125.0030),
            RouteCoordinate(11.25, 125.01),
            RouteMode.DRIVING,
            incidentId = 42L,
        )

        assertFalse(result.isSuccess)
        assertFalse(called)
        assertTrue(result.exceptionOrNull()?.message?.contains("Sign in") == true)
    }

    @Test
    fun rejectsInvalidCoordinatesAndIncidentBeforeCallingService() = runBlocking {
        var called = false
        val service = object : TestRenderRoutingService() {
            override suspend fun routePreview(
                request: RenderRouteRequest,
                authorization: String,
            ): Response<RenderRouteResponse> {
                called = true
                return Response.success(emptyRoute())
            }
        }
        val provider = RenderRoutingProvider(service, { "token" })

        val badId = provider.route(
            RouteCoordinate(11.2443, 125.0030),
            RouteCoordinate(11.25, 125.01),
            RouteMode.DRIVING,
            incidentId = 0L,
        )
        val badOrigin = provider.route(
            RouteCoordinate(Double.NaN, 125.0030),
            RouteCoordinate(11.25, 125.01),
            RouteMode.DRIVING,
            incidentId = 42L,
        )

        assertFalse(badId.isSuccess)
        assertFalse(badOrigin.isSuccess)
        assertFalse(called)
    }

    @Test
    fun rejectsUnsupportedOrMalformedProviderResponse() = runBlocking {
        val noGeometry = object : TestRenderRoutingService() {
            override suspend fun routePreview(
                request: RenderRouteRequest,
                authorization: String,
            ): Response<RenderRouteResponse> = Response.success(
                RenderRouteResponse(
                    geometry = listOf(RenderRoutePoint(11.0, 125.0)),
                    distanceMeters = 100.0,
                    durationSeconds = 10.0,
                    instructions = emptyList(),
                ),
            )
        }
        val provider = RenderRoutingProvider(noGeometry, { "token" })

        val response = provider.route(
            RouteCoordinate(11.2443, 125.0030),
            RouteCoordinate(11.25, 125.01),
            RouteMode.DRIVING,
            incidentId = 42L,
        )
        var walkingCalled = false
        val walkingService = object : TestRenderRoutingService() {
            override suspend fun routePreview(
                request: RenderRouteRequest,
                authorization: String,
            ): Response<RenderRouteResponse> {
                walkingCalled = true
                return Response.success(emptyRoute())
            }
        }
        val walking = RenderRoutingProvider(walkingService, { "token" }).route(
            RouteCoordinate(11.2443, 125.0030),
            RouteCoordinate(11.25, 125.01),
            RouteMode.WALKING,
            incidentId = 42L,
        )

        assertFalse(response.isSuccess)
        assertFalse(walking.isSuccess)
        assertFalse(walkingCalled)
        assertTrue(walking.exceptionOrNull()?.message?.contains("Only driving") == true)
    }

    @Test
    fun returnsSafeServerErrorMessage() = runBlocking {
        val service = object : TestRenderRoutingService() {
            override suspend fun routePreview(
                request: RenderRouteRequest,
                authorization: String,
            ): Response<RenderRouteResponse> = Response.error(
                503,
                "{\"message\":\"In-app routing is not configured.\"}".toResponseBody(),
            )
        }

        val result = RenderRoutingProvider(service, { "token" }).route(
            RouteCoordinate(11.2443, 125.0030),
            RouteCoordinate(11.25, 125.01),
            RouteMode.DRIVING,
            incidentId = 42L,
        )

        assertFalse(result.isSuccess)
        assertEquals("In-app routing is not configured.", result.exceptionOrNull()?.message)
    }

    @Test
    fun providerDisclosureNamesRecipientWithoutSendingCoordinates() = runBlocking {
        var capturedAuthorization: String? = null
        var routeCalled = false
        var capturedRouteRequest: RenderRouteRequest? = null
        val service = object : TestRenderRoutingService() {
            override suspend fun routeProviderDisclosure(
                authorization: String,
            ): Response<RenderRouteProviderDisclosureResponse> {
                capturedAuthorization = authorization
                return Response.success(RenderRouteProviderDisclosureResponse("private_osrm"))
            }

            override suspend fun routePreview(
                request: RenderRouteRequest,
                authorization: String,
            ): Response<RenderRouteResponse> {
                routeCalled = true
                capturedRouteRequest = request
                return Response.success(emptyRoute())
            }
        }

        val provider = RenderRoutingProvider(service, { "session-token" })
        val disclosure = provider.providerDisclosure()

        assertTrue(disclosure.isSuccess)
        assertEquals("private_osrm", disclosure.getOrThrow().providerId)
        assertEquals("Bearer session-token", capturedAuthorization)
        assertFalse(routeCalled)

        val route = provider.route(
            RouteCoordinate(11.0, 125.0),
            RouteCoordinate(11.1, 125.1),
            RouteMode.DRIVING,
            incidentId = 42L,
        )
        assertTrue(route.isSuccess)
        assertEquals("private_osrm", capturedRouteRequest?.expectedProviderId)
    }

    private abstract class TestRenderRoutingService : RenderRoutingService {
        override suspend fun routeProviderDisclosure(
            authorization: String,
        ): Response<RenderRouteProviderDisclosureResponse> =
            Response.success(RenderRouteProviderDisclosureResponse("geoapify"))
    }

    private fun emptyRoute() = RenderRouteResponse(
        geometry = listOf(RenderRoutePoint(11.0, 125.0), RenderRoutePoint(11.1, 125.1)),
        distanceMeters = 100.0,
        durationSeconds = 10.0,
        instructions = emptyList(),
    )
}
