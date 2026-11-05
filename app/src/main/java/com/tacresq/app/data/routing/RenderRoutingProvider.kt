package com.tacresq.app.data.routing

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.GET
import retrofit2.http.POST

internal interface RenderRoutingService {
    @POST("route-preview")
    suspend fun routePreview(
        @Body request: RenderRouteRequest,
        @Header("Authorization") authorization: String,
    ): Response<RenderRouteResponse>

    @GET("route-preview/provider")
    suspend fun routeProviderDisclosure(
        @Header("Authorization") authorization: String,
    ): Response<RenderRouteProviderDisclosureResponse>
}

internal data class RenderRouteRequest(
    @SerializedName("incident_id") val incidentId: Long,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("expected_provider_id") val expectedProviderId: String? = null,
)

internal data class RenderRoutePoint(
    val latitude: Double,
    val longitude: Double,
)

internal data class RenderRouteResponse(
    val geometry: List<RenderRoutePoint>?,
    @SerializedName("distance_meters") val distanceMeters: Double?,
    @SerializedName("duration_seconds") val durationSeconds: Double?,
    val instructions: List<RenderRouteInstruction>?,
)

internal data class RenderRouteInstruction(
    val text: String?,
    @SerializedName("distance_meters") val distanceMeters: Double?,
    @SerializedName("duration_seconds") val durationSeconds: Double?,
)

internal data class RenderRouteError(
    val message: String?,
)

internal data class RenderRouteProviderDisclosureResponse(
    @SerializedName("provider_id") val providerId: String?,
)

/** Calls TACResQ's authenticated API; private OSRM routing is never called directly by Android. */
internal class RenderRoutingProvider(
    private val service: RenderRoutingService,
    private val tokenProvider: () -> String?,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : RoutingProvider {
    @Volatile
    private var disclosedProviderId: String? = null

    override suspend fun route(
        origin: RouteCoordinate,
        destination: RouteCoordinate,
        mode: RouteMode,
        incidentId: Long,
    ): Result<RouteResult> {
        if (incidentId <= 0L || !origin.isValid() || !destination.isValid()) {
            return Result.failure(IllegalArgumentException("A valid assigned incident and route coordinates are required."))
        }
        if (mode != RouteMode.DRIVING) {
            return Result.failure(IllegalArgumentException("Only driving route previews are currently configured."))
        }
        val token = tokenProvider()?.trim()?.takeIf(String::isNotEmpty)
        if (token == null) {
            return Result.failure(IllegalStateException("Sign in to request a route."))
        }

        return try {
            val response = service.routePreview(
                RenderRouteRequest(
                    incidentId = incidentId,
                    latitude = origin.latitude,
                    longitude = origin.longitude,
                    expectedProviderId = disclosedProviderId,
                ),
                authorization = "Bearer $token",
            )
            if (!response.isSuccessful) {
                return Result.failure(IllegalStateException(errorMessage(response)))
            }
            val body = response.body()
                ?: return Result.failure(IllegalStateException("The routing service returned no route."))
            val geometry = body.geometry.orEmpty().map { RouteCoordinate(it.latitude, it.longitude) }
            val instructions = body.instructions.orEmpty().mapNotNull { instruction ->
                val text = instruction.text?.trim().orEmpty()
                if (text.isEmpty()) null else RouteInstruction(
                    text = text,
                    distanceMeters = instruction.distanceMeters,
                    durationSeconds = instruction.durationSeconds,
                )
            }
            if (geometry.size !in 2..5_000 || instructions.size > 200) {
                return Result.failure(IllegalStateException("The routing service returned an invalid route."))
            }
            val route = RouteResult(
                geometry = geometry,
                distanceMeters = body.distanceMeters ?: Double.NaN,
                durationSeconds = body.durationSeconds ?: Double.NaN,
                instructions = instructions,
                generatedAtEpochMillis = clockMillis(),
            )
            if (!route.isUsable(clockMillis()) || instructions.any { instruction ->
                    instruction.text.length > 300 ||
                        (instruction.distanceMeters != null &&
                            (!instruction.distanceMeters.isFinite() || instruction.distanceMeters < 0.0)) ||
                        (instruction.durationSeconds != null &&
                            (!instruction.durationSeconds.isFinite() || instruction.durationSeconds < 0.0))
                }
            ) {
                Result.failure(IllegalStateException("The routing service returned an invalid route."))
            } else {
                Result.success(route)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    override suspend fun providerDisclosure(): Result<RouteProviderDisclosure> {
        disclosedProviderId = null
        val token = tokenProvider()?.trim()?.takeIf(String::isNotEmpty)
            ?: return Result.failure(IllegalStateException("Sign in to verify the routing provider."))
        return try {
            val response = service.routeProviderDisclosure("Bearer $token")
            if (!response.isSuccessful) {
                val message = when (response.code()) {
                    401 -> "Sign in again before starting live navigation."
                    403 -> "This account cannot request live navigation."
                    503 -> "The routing provider is not configured or could not be identified."
                    else -> "The active routing provider could not be verified."
                }
                return Result.failure(IllegalStateException(message))
            }
            val body = response.body()
                ?: return Result.failure(IllegalStateException("The active routing provider could not be verified."))
            val providerId = body.providerId
            if (providerId == null || providerId !in setOf("geoapify", "private_osrm")) {
                Result.failure(IllegalStateException("The active routing provider could not be verified."))
            } else {
                disclosedProviderId = providerId
                Result.success(RouteProviderDisclosure(providerId))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("The active routing provider could not be verified."))
        }
    }

    private fun errorMessage(response: Response<RenderRouteResponse>): String {
        val statusCode = response.code()
        val serverMessage = runCatching {
            response.errorBody()?.string()
                ?.let { com.google.gson.Gson().fromJson(it, RenderRouteError::class.java)?.message }
                ?.trim()
                ?.takeIf { it.length in 1..200 }
        }.getOrNull()
        return serverMessage ?: when (statusCode) {
            401 -> "Sign in again before requesting a route."
            403 -> "Only responders assigned to this incident can request its route."
            404 -> "This incident is no longer available for routing."
            429 -> "The routing request limit has been reached. Try later or use external Maps."
            503 -> "In-app routing is not configured or is temporarily unavailable."
            else -> "The routing service returned HTTP $statusCode."
        }
    }
}
