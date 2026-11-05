package com.tacresq.app.data.routing

/** Why a navigation session is asking the server for a route. */
enum class RouteRequestReason {
    INITIAL,
    OFF_ROUTE,
}

/**
 * Client-side cost and GPS-noise guard for live rerouting. This does not replace
 * server authorization or rate limits. Calls are admitted at most once every 30 seconds.
 */
class RouteReroutePolicy(
    private val minRequestIntervalMillis: Long = MIN_REQUEST_INTERVAL_MILLIS,
    private val baseOffRouteMeters: Double = BASE_OFF_ROUTE_METERS,
    private val maxAccuracyMeters: Double = MAX_ACCURACY_METERS,
    private val requiredOffRouteFixes: Int = REQUIRED_OFF_ROUTE_FIXES,
) {
    private var lastRequestAtMillis: Long? = null
    private var consecutiveOffRouteFixes = 0
    private var failureStreak = 0
    private var retryNotBeforeMillis: Long? = null

    init {
        require(minRequestIntervalMillis >= 0L)
        require(baseOffRouteMeters > 0.0 && baseOffRouteMeters.isFinite())
        require(maxAccuracyMeters > 0.0 && maxAccuracyMeters.isFinite())
        require(requiredOffRouteFixes > 0)
    }

    @Synchronized
    fun consider(
        origin: RouteCoordinate,
        accuracyMeters: Float?,
        route: RouteResult?,
        nowMillis: Long,
    ): RouteRequestReason? {
        if (!origin.isValid()) return null
        val lastRequest = lastRequestAtMillis
        if (lastRequest != null && nowMillis < lastRequest) return null

        val accuracy = accuracyMeters?.toDouble()
        if (accuracy == null || !accuracy.isFinite() || accuracy < 0.0 || accuracy > maxAccuracyMeters) {
            consecutiveOffRouteFixes = 0
            return null
        }
        if (route == null) {
            consecutiveOffRouteFixes = 0
            if (!isRequestAllowed(nowMillis, lastRequest)) return null
            lastRequestAtMillis = nowMillis
            return RouteRequestReason.INITIAL
        }
        val distance = distanceToRouteMeters(origin, route.geometry)
        val threshold = maxOf(baseOffRouteMeters, accuracy * 2.0)
        if (!distance.isFinite() || distance <= threshold) {
            consecutiveOffRouteFixes = 0
            return null
        }

        consecutiveOffRouteFixes = (consecutiveOffRouteFixes + 1).coerceAtMost(requiredOffRouteFixes)
        if (consecutiveOffRouteFixes < requiredOffRouteFixes) return null
        if (!isRequestAllowed(nowMillis, lastRequest)) return null
        consecutiveOffRouteFixes = 0
        lastRequestAtMillis = nowMillis
        return RouteRequestReason.OFF_ROUTE
    }

    private fun isRequestAllowed(nowMillis: Long, lastRequestAtMillis: Long?): Boolean {
        val retryNotBefore = retryNotBeforeMillis
        if (retryNotBefore != null && nowMillis < retryNotBefore) return false
        return lastRequestAtMillis == null || nowMillis - lastRequestAtMillis >= minRequestIntervalMillis
    }

    @Synchronized
    fun reset() {
        lastRequestAtMillis = null
        consecutiveOffRouteFixes = 0
        failureStreak = 0
        retryNotBeforeMillis = null
    }

    @Synchronized
    fun resetForNewDestination() {
        consecutiveOffRouteFixes = 0
        failureStreak = 0
        retryNotBeforeMillis = null
    }

    @Synchronized
    fun recordRequestResult(succeeded: Boolean, nowMillis: Long) {
        if (succeeded) {
            failureStreak = 0
            retryNotBeforeMillis = null
            return
        }
        failureStreak = (failureStreak + 1).coerceAtMost(MAX_BACKOFF_STEPS)
        val multiplier = 1L shl (failureStreak - 1).coerceAtMost(4)
        val backoffMillis = (minRequestIntervalMillis * multiplier).coerceAtMost(MAX_FAILURE_BACKOFF_MILLIS)
        retryNotBeforeMillis = runCatching { Math.addExact(nowMillis, backoffMillis) }
            .getOrDefault(Long.MAX_VALUE)
    }

    companion object {
        const val MIN_REQUEST_INTERVAL_MILLIS = 30_000L
        const val BASE_OFF_ROUTE_METERS = 75.0
        const val MAX_ACCURACY_METERS = 75.0
        const val REQUIRED_OFF_ROUTE_FIXES = 2
        const val MAX_FAILURE_BACKOFF_MILLIS = 300_000L
        private const val MAX_BACKOFF_STEPS = 8
        private const val METERS_PER_DEGREE_LATITUDE = 111_195.0

        /** Local equirectangular projection is sufficient for the short route segment comparison. */
        fun distanceToRouteMeters(point: RouteCoordinate, geometry: List<RouteCoordinate>): Double {
            if (!point.isValid() || geometry.size < 2 || !geometry.all(RouteCoordinate::isValid)) {
                return Double.POSITIVE_INFINITY
            }
            val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE *
                kotlin.math.cos(Math.toRadians(point.latitude)).coerceAtLeast(0.000001)
            var nearest = Double.POSITIVE_INFINITY
            geometry.zipWithNext().forEach { (start, end) ->
                val ax = (start.longitude - point.longitude) * metersPerDegreeLongitude
                val ay = (start.latitude - point.latitude) * METERS_PER_DEGREE_LATITUDE
                val bx = (end.longitude - point.longitude) * metersPerDegreeLongitude
                val by = (end.latitude - point.latitude) * METERS_PER_DEGREE_LATITUDE
                val dx = bx - ax
                val dy = by - ay
                val lengthSquared = dx * dx + dy * dy
                val fraction = if (lengthSquared == 0.0) 0.0
                else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
                val x = ax + fraction * dx
                val y = ay + fraction * dy
                nearest = minOf(nearest, kotlin.math.sqrt(x * x + y * y))
            }
            return nearest
        }
    }
}
