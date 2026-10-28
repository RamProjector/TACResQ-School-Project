package com.tacresq.app.data.routing

/**
 * Decodes Valhalla's default encoded polyline6 geometry into latitude/longitude
 * coordinates. Rejects malformed input and unreasonable point counts instead of
 * returning a partial route that could mislead a responder.
 */
internal object ValhallaPolylineDecoder {
    private const val PRECISION = 1_000_000.0
    private const val MAX_POINTS = 100_000

    fun decode(encoded: String): List<RouteCoordinate> {
        require(encoded.isNotBlank()) { "Route geometry is empty." }
        val points = ArrayList<RouteCoordinate>()
        var index = 0
        var latitude = 0L
        var longitude = 0L

        fun readDelta(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(index < encoded.length) { "Route geometry ended unexpectedly." }
                val value = encoded[index++].code - 63
                require(value in 0..63) { "Route geometry contains an invalid character." }
                require(shift <= 55) { "Route geometry delta is too large." }
                result = result or ((value and 0x1f).toLong() shl shift)
                if (value < 0x20) break
                shift += 5
            }
            return if ((result and 1L) != 0L) (result shr 1).inv() else result shr 1
        }

        while (index < encoded.length) {
            latitude += readDelta()
            longitude += readDelta()
            val coordinate = RouteCoordinate(latitude / PRECISION, longitude / PRECISION)
            require(coordinate.isValid()) { "Route geometry contains out-of-range coordinates." }
            points.add(coordinate)
            require(points.size <= MAX_POINTS) { "Route geometry exceeds the point limit." }
        }
        require(points.size >= 2) { "Route geometry must contain at least two points." }
        return points
    }
}
