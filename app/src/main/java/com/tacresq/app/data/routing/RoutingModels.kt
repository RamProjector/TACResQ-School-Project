package com.tacresq.app.data.routing

import com.google.gson.annotations.SerializedName

/**
 * Routing-provider-neutral models for in-app road routing.
 *
 * Route geometry is expressed as latitude/longitude points for direct rendering
 * by MapLibre. Providers must validate coordinates and timestamps before returning
 * a RouteResult. A route result is guidance, not proof that a road is currently
 * passable or safe for emergency vehicles.
 */
data class RouteCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    fun isValid(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

data class RouteInstruction(
    val text: String,
    val distanceMeters: Double?,
    val durationSeconds: Double?,
)

data class RouteResult(
    val geometry: List<RouteCoordinate>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val instructions: List<RouteInstruction> = emptyList(),
    val generatedAtEpochMillis: Long,
) {
    fun isUsable(nowEpochMillis: Long, maxAgeMillis: Long = 120_000L): Boolean {
        if (
            geometry.size < 2 ||
            !geometry.all(RouteCoordinate::isValid) ||
            !distanceMeters.isFinite() || distanceMeters <= 0.0 ||
            !durationSeconds.isFinite() || durationSeconds <= 0.0 ||
            maxAgeMillis < 0L ||
            generatedAtEpochMillis > nowEpochMillis
        ) return false

        // Subtraction can overflow for a corrupt timestamp such as Long.MIN_VALUE.
        // Treat arithmetic overflow as invalid rather than accidentally fresh.
        val ageMillis = runCatching {
            Math.subtractExact(nowEpochMillis, generatedAtEpochMillis)
        }.getOrNull() ?: return false
        return ageMillis <= maxAgeMillis
    }
}

data class RouteProviderDisclosure(
    @SerializedName("provider_id") val providerId: String,
) {
    fun consentRecipient(): String? = when (providerId) {
        "geoapify" -> "Geoapify, an external routing provider"
        "private_osrm" -> "TACResQ's privately operated OSRM routing service"
        else -> null
    }
}

/**
 * Implementations must call a configured, maintained routing service.
 * Do not hard-code a community demo endpoint into the production app.
 */
interface RoutingProvider {
    suspend fun route(
        origin: RouteCoordinate,
        destination: RouteCoordinate,
        mode: RouteMode,
        incidentId: Long,
    ): Result<RouteResult>

    /** Identifies the exact route recipient without sending any location coordinates. */
    suspend fun providerDisclosure(): Result<RouteProviderDisclosure> =
        Result.failure(IllegalStateException("The active routing provider could not be verified."))
}

enum class RouteMode {
    DRIVING,
    WALKING,
}

/** Valhalla uses "auto" and "pedestrian" costing names for these modes. */
internal fun RouteMode.valhallaCosting(): String = when (this) {
    RouteMode.DRIVING -> "auto"
    RouteMode.WALKING -> "pedestrian"
}

/** Minimal Valhalla response shapes; the network adapter owns decoding geometry. */
internal data class ValhallaRouteResponse(
    @SerializedName("trip") val trip: ValhallaTrip?,
)

internal data class ValhallaTrip(
    @SerializedName("summary") val summary: ValhallaSummary?,
    @SerializedName("legs") val legs: List<ValhallaLeg>?,
)

internal data class ValhallaSummary(
    @SerializedName("length") val lengthKilometers: Double?,
    @SerializedName("time") val timeSeconds: Double?,
)

internal data class ValhallaLeg(
    @SerializedName("shape") val encodedShape: String?,
    @SerializedName("maneuvers") val maneuvers: List<ValhallaManeuver>?,
)

internal data class ValhallaManeuver(
    @SerializedName("instruction") val instruction: String?,
    @SerializedName("length") val lengthKilometers: Double?,
    @SerializedName("time") val timeSeconds: Double?,
)
