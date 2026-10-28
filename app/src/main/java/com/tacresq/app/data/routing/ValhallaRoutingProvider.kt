package com.tacresq.app.data.routing

import kotlinx.coroutines.CancellationException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Minimal Valhalla HTTP contract. Construct Retrofit with an explicitly configured
 * base URL supplied by the application environment; this class intentionally has
 * no default host and never embeds credentials.
 */
internal interface ValhallaRoutingService {
    @POST("route")
    suspend fun route(@Body request: Map<String, Any>): Response<ValhallaRouteResponse>
}

/**
 * Converts a Valhalla route response to the provider-neutral app model.
 *
 * The service URL and network timeouts belong to the caller's Retrofit/OkHttp
 * configuration. Only coordinates and routing mode are sent to the provider.
 */
internal class ValhallaRoutingProvider(
    private val service: ValhallaRoutingService,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : RoutingProvider {

    override suspend fun route(
        origin: RouteCoordinate,
        destination: RouteCoordinate,
        mode: RouteMode,
    ): Result<RouteResult> {
        if (!origin.isValid() || !destination.isValid()) {
            return Result.failure(IllegalArgumentException("Origin and destination must be valid coordinates."))
        }

        return try {
            val request = mapOf(
                "locations" to listOf(
                    mapOf("lat" to origin.latitude, "lon" to origin.longitude),
                    mapOf("lat" to destination.latitude, "lon" to destination.longitude),
                ),
                "costing" to mode.valhallaCosting(),
                "units" to "kilometers",
                "directions_options" to mapOf("units" to "kilometers"),
            )
            val response = service.route(request)
            if (!response.isSuccessful) {
                return Result.failure(IllegalStateException("Routing service returned HTTP ${response.code()}."))
            }
            val trip = response.body()?.trip
                ?: return Result.failure(IllegalStateException("Routing service returned no route."))

            val summary = trip.summary
                ?: return Result.failure(IllegalStateException("Routing service returned no route summary."))
            val legs = trip.legs.orEmpty()
            if (legs.isEmpty()) {
                return Result.failure(IllegalStateException("Routing service returned no route geometry."))
            }

            val geometry = ArrayList<RouteCoordinate>()
            val instructions = ArrayList<RouteInstruction>()
            for (leg in legs) {
                val encodedShape = leg.encodedShape
                    ?: return Result.failure(IllegalStateException("Routing service returned incomplete route geometry."))
                val points = ValhallaPolylineDecoder.decode(encodedShape)
                // Adjacent Valhalla legs may share their boundary coordinate.
                geometry.addAll(if (geometry.lastOrNull() == points.first()) points.drop(1) else points)
                leg.maneuvers.orEmpty().forEach { maneuver ->
                    val text = maneuver.instruction?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        val distance = maneuver.lengthKilometers?.times(1_000.0)
                        val duration = maneuver.timeSeconds
                        if ((distance != null && (!distance.isFinite() || distance < 0.0)) ||
                            (duration != null && (!duration.isFinite() || duration < 0.0))
                        ) {
                            return Result.failure(IllegalStateException("Routing service returned invalid maneuver data."))
                        }
                        instructions.add(RouteInstruction(text, distance, duration))
                    }
                }
            }

            val distanceMeters = (summary.lengthKilometers ?: Double.NaN) * 1_000.0
            val result = RouteResult(
                geometry = geometry,
                distanceMeters = distanceMeters,
                durationSeconds = summary.timeSeconds ?: Double.NaN,
                instructions = instructions,
                generatedAtEpochMillis = clockMillis(),
            )
            if (!result.isUsable(clockMillis())) {
                Result.failure(IllegalStateException("Routing service returned an invalid or unusable route."))
            } else {
                Result.success(result)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
}
