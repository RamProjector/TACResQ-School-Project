package com.tacresq.app.ui

import kotlin.math.roundToInt

/**
 * Select a map zoom that does not imply more precision than Android reported.
 * Accuracy is an estimated horizontal radius, not a guarantee of correctness.
 */
internal fun gpsMapPreviewZoom(accuracyM: Float?): Double {
    if (accuracyM == null || !accuracyM.isFinite() || accuracyM <= 0f) return 12.0
    return when {
        accuracyM <= 50f -> 16.0
        accuracyM <= 150f -> 15.0
        accuracyM <= 500f -> 14.0
        else -> 12.0
    }
}

internal fun gpsAccuracyStatus(accuracyM: Float?): String {
    if (accuracyM == null || !accuracyM.isFinite() || accuracyM <= 0f) {
        return "Accuracy unavailable — confirm the map position before relying on it."
    }
    return if (accuracyM > 100f) {
        "Low location accuracy (about ${accuracyM.roundToInt()} m) — confirm the map position before relying on it."
    } else {
        "Estimated accuracy about ${accuracyM.roundToInt()} m."
    }
}
