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

/**
 * Insets the visible map viewport around persistent controls and the live bottom sheet.
 * Values are pixels because MapLibre's asymmetric bounds API accepts pixel padding.
 */
internal data class GpsMapCameraInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun gpsMapCameraInsets(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    sheetTopPx: Float?,
): GpsMapCameraInsets {
    if (widthPx <= 0 || heightPx <= 0 || !density.isFinite() || density <= 0f) {
        return GpsMapCameraInsets(0, 0, 0, 0)
    }
    val horizontal = (24f * density).roundToInt().coerceAtMost(widthPx / 8)
    val top = (176f * density).roundToInt().coerceAtMost(heightPx / 3)
    val fallbackSheetTop = heightPx - (120f * density)
    val sheetTop = (sheetTopPx ?: fallbackSheetTop).coerceIn(0f, heightPx.toFloat())
    val bottom = (heightPx - sheetTop).roundToInt() + (24f * density).roundToInt()
    val maxVerticalPadding = (heightPx * 0.72f).roundToInt()
    val requestedVerticalPadding = top + bottom
    val scale = if (requestedVerticalPadding > maxVerticalPadding && requestedVerticalPadding > 0) {
        maxVerticalPadding.toFloat() / requestedVerticalPadding.toFloat()
    } else 1f
    return GpsMapCameraInsets(
        left = horizontal,
        top = (top * scale).roundToInt(),
        right = horizontal,
        bottom = (bottom * scale).roundToInt(),
    )
}
