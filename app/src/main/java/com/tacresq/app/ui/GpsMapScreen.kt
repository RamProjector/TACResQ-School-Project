package com.tacresq.app.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.view.Gravity
import android.net.Network
import android.net.NetworkCapabilities
import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import com.google.gson.JsonObject
import android.os.Handler
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.locationbutton.compose.LocationButton
import androidx.core.locationbutton.compose.LocationButtonTextType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.tacresq.app.ui.common.MapLoadStatusOverlay
import com.tacresq.app.ui.common.incidentLocationText
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import com.tacresq.app.data.model.GeoFix
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.ResponderLocation
import com.tacresq.app.data.model.User
import com.tacresq.app.data.model.statusFor
import com.tacresq.app.data.routing.RouteCoordinate
import com.tacresq.app.data.routing.RouteMode
import com.tacresq.app.data.routing.RouteResult
import com.tacresq.app.data.routing.RoutingProvider
import com.tacresq.app.location.ResponderLocationTrackingController
import com.tacresq.app.location.ResponderTrackingStatus
import com.tacresq.app.ui.common.StatusPill
import com.tacresq.app.ui.common.pillColor
import com.tacresq.app.location.LocationCaptureResult
import com.tacresq.app.location.LocationSettingsCheck
import com.tacresq.app.location.LocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.math.hypot

private const val TACLOBAN_LATITUDE = 11.2443
private const val TACLOBAN_LONGITUDE = 125.0030
private const val OPENFREEMAP_LIBERTY_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val MAP_DATA_UNAVAILABLE_STATUS = "Map data is temporarily unavailable. Incident details remain available."

private const val INCIDENT_CLUSTER_SOURCE_ID = "tacresq-incident-clusters-source"
private const val RESPONDER_CLUSTER_SOURCE_ID = "tacresq-responder-clusters-source"
private const val STALE_RESPONDER_CLUSTER_SOURCE_ID = "tacresq-stale-responder-clusters-source"
private const val INCIDENT_CLUSTER_LAYER_ID = "tacresq-incident-clusters"
private const val INCIDENT_POINT_LAYER_ID = "tacresq-incident-points"
private const val INCIDENT_SELECTED_POINT_LAYER_ID = "tacresq-incident-selected-points"
private const val INCIDENT_COUNT_LAYER_ID = "tacresq-incident-cluster-count"
private const val RESPONDER_CLUSTER_LAYER_ID = "tacresq-responder-clusters"
private const val RESPONDER_POINT_LAYER_ID = "tacresq-responder-points"
private const val RESPONDER_STALE_POINT_LAYER_ID = "tacresq-responder-stale-points"
private const val RESPONDER_COUNT_LAYER_ID = "tacresq-responder-cluster-count"
private const val STALE_RESPONDER_CLUSTER_LAYER_ID = "tacresq-stale-responder-clusters"
private const val STALE_RESPONDER_COUNT_LAYER_ID = "tacresq-stale-responder-cluster-count"

private data class GpsMapClusterSources(
    val incidents: GeoJsonSource,
    val responders: GeoJsonSource,
    val staleResponders: GeoJsonSource,
)

private fun Style.installGpsMapClusterLayers(context: Context): GpsMapClusterSources {
    addImage("tacresq-incident-pin", context.mapLibreStylePinBitmap(com.tacresq.app.R.drawable.ic_map_incident_pin))
    addImage("tacresq-selected-incident-pin", context.mapLibreStylePinBitmap(com.tacresq.app.R.drawable.ic_map_incident_pin, scale = 0.98f))
    addImage("tacresq-responder-pin", context.mapLibreStylePinBitmap(com.tacresq.app.R.drawable.ic_map_responder_pin))
    addImage("tacresq-stale-responder-pin", context.mapLibreStylePinBitmap(com.tacresq.app.R.drawable.ic_map_responder_pin, alpha = 110))
    fun newClusterSource(id: String) = GeoJsonSource(
        id,
        FeatureCollection.fromFeatures(emptyArray<Feature>()),
        GeoJsonOptions().withCluster(true).withClusterMaxZoom(14).withClusterRadius(50),
    )
    val incidentSource = newClusterSource(INCIDENT_CLUSTER_SOURCE_ID)
    val responderSource = newClusterSource(RESPONDER_CLUSTER_SOURCE_ID)
    val staleResponderSource = newClusterSource(STALE_RESPONDER_CLUSTER_SOURCE_ID)
    addSource(incidentSource)
    addSource(responderSource)
    addSource(staleResponderSource)

    fun addClusterCircle(id: String, sourceId: String, color: Int) {
        addLayer(CircleLayer(id, sourceId).apply {
            setProperties(PropertyFactory.circleColor(color), PropertyFactory.circleRadius(20f),
                PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(2f))
            setFilter(Expression.has("point_count"))
        })
    }
    fun addClusterCount(id: String, sourceId: String) {
        addLayer(SymbolLayer(id, sourceId).apply {
            setProperties(PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
                PropertyFactory.textSize(12f), PropertyFactory.textColor(Color.WHITE),
                PropertyFactory.textIgnorePlacement(true), PropertyFactory.textAllowOverlap(true))
            setFilter(Expression.has("point_count"))
        })
    }
    val unclustered = Expression.neq(Expression.get("cluster"), true)
    fun addPinLayer(id: String, sourceId: String, image: String, filter: Expression, opacity: Float = 1f, size: Float = 0.78f) {
        addLayer(SymbolLayer(id, sourceId).apply {
            setProperties(
                PropertyFactory.iconImage(image),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconOpacity(opacity),
                PropertyFactory.iconSize(size),
            )
            setFilter(filter)
        })
    }
    addClusterCircle(INCIDENT_CLUSTER_LAYER_ID, INCIDENT_CLUSTER_SOURCE_ID, Color.rgb(183, 28, 28))
    addClusterCount(INCIDENT_COUNT_LAYER_ID, INCIDENT_CLUSTER_SOURCE_ID)
    addPinLayer(INCIDENT_POINT_LAYER_ID, INCIDENT_CLUSTER_SOURCE_ID, "tacresq-incident-pin",
        Expression.all(unclustered, Expression.neq(Expression.get("selected"), true)))
    addPinLayer(INCIDENT_SELECTED_POINT_LAYER_ID, INCIDENT_CLUSTER_SOURCE_ID, "tacresq-selected-incident-pin",
        Expression.all(unclustered, Expression.eq(Expression.get("selected"), true)), size = 0.98f)
    addClusterCircle(RESPONDER_CLUSTER_LAYER_ID, RESPONDER_CLUSTER_SOURCE_ID, Color.rgb(0, 105, 92))
    addClusterCount(RESPONDER_COUNT_LAYER_ID, RESPONDER_CLUSTER_SOURCE_ID)
    addPinLayer(RESPONDER_POINT_LAYER_ID, RESPONDER_CLUSTER_SOURCE_ID, "tacresq-responder-pin", unclustered)
    addClusterCircle(STALE_RESPONDER_CLUSTER_LAYER_ID, STALE_RESPONDER_CLUSTER_SOURCE_ID, Color.rgb(97, 97, 97))
    addClusterCount(STALE_RESPONDER_COUNT_LAYER_ID, STALE_RESPONDER_CLUSTER_SOURCE_ID)
    addPinLayer(RESPONDER_STALE_POINT_LAYER_ID, STALE_RESPONDER_CLUSTER_SOURCE_ID,
        "tacresq-stale-responder-pin", unclustered, opacity = 0.55f)
    return GpsMapClusterSources(incidentSource, responderSource, staleResponderSource)
}

private fun Incident.toGpsClusterFeature(selectedId: Long?): Feature? {
    val lat = latitude ?: return null
    val lng = longitude ?: return null
    if (!hasValidMapCoordinates()) return null
    val props = JsonObject().apply {
        addProperty("incident_id", id.toString())
        addProperty("kind", "incident")
        addProperty("status", status.label)
        addProperty("selected", id == selectedId)
    }
    return Feature.fromGeometry(Point.fromLngLat(lng, lat), props)
}

private fun ResponderLocation.toGpsClusterFeature(now: Instant): Feature? {
    val age = mapAgeSeconds(now) ?: return null
    val freshness = mapFreshness(now) ?: return null
    val props = JsonObject().apply {
        addProperty("incident_id", incidentId.toString())
        addProperty("user_id", userId.toString())
        addProperty("kind", "responder")
        addProperty("freshness", freshness.name.lowercase())
        addProperty("age_seconds", age)
    }
    return Feature.fromGeometry(Point.fromLngLat(longitude, latitude), props)
}


/**
 * Converts the app's vector pin drawables to MapLibre marker icons. Icons are
 * created once per map screen and reused for all annotations.
 */
private fun Context.mapLibrePinBitmap(resourceId: Int, alpha: Int = 255, scale: Float = 0.75f): Bitmap {
    val drawable = requireNotNull(ContextCompat.getDrawable(this, resourceId)).mutate()
    val safeScale = scale.takeIf { it.isFinite() && it > 0f } ?: 0.75f
    val width = ((drawable.intrinsicWidth.takeIf { it > 0 } ?: 48) * safeScale).roundToInt().coerceAtLeast(1)
    val iconHeight = ((drawable.intrinsicHeight.takeIf { it > 0 } ?: 56) * safeScale).roundToInt().coerceAtLeast(1)
    // Transparent lower half keeps the geographic coordinate at the pin tip.
    val bitmap = Bitmap.createBitmap(width, iconHeight * 2, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.alpha = alpha.coerceIn(0, 255)
    drawable.setBounds(0, 0, width, iconHeight)
    drawable.draw(canvas)
    return bitmap
}

private fun Context.mapLibreStylePinBitmap(resourceId: Int, alpha: Int = 255, scale: Float = 0.75f): Bitmap {
    val drawable = requireNotNull(ContextCompat.getDrawable(this, resourceId)).mutate()
    val safeScale = scale.takeIf { it.isFinite() && it > 0f } ?: 0.75f
    val width = ((drawable.intrinsicWidth.takeIf { it > 0 } ?: 48) * safeScale).roundToInt().coerceAtLeast(1)
    val height = ((drawable.intrinsicHeight.takeIf { it > 0 } ?: 56) * safeScale).roundToInt().coerceAtLeast(1)
    // Style symbols use icon-anchor=bottom, so the actual bitmap edge must be the pin tip.
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.alpha = alpha.coerceIn(0, 255)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)
    return bitmap
}

private fun Context.mapLibrePinIcon(resourceId: Int, alpha: Int = 255, scale: Float = 0.75f): Icon =
    IconFactory.getInstance(this).fromBitmap(mapLibrePinBitmap(resourceId, alpha, scale))


private fun positionMapCompass(map: MapLibreMap, headerBottomPx: Int, density: Float) {
    // The MapView is underneath the Compose header. Position the native compass
    // from the header's measured bottom edge rather than a guessed screen offset.
    val topMargin = (headerBottomPx + (12f * density).roundToInt())
        .coerceAtLeast((72f * density).roundToInt())
    map.uiSettings.setCompassEnabled(true)
    map.uiSettings.setCompassGravity(Gravity.TOP or Gravity.RIGHT)
    map.uiSettings.setCompassMargins(0, topMargin, (12f * density).roundToInt(), 0)
}

private fun ConnectivityManager.hasValidatedInternet(): Boolean {
    val network = activeNetwork ?: return false
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun Incident.hasValidMapCoordinates(): Boolean {
    val lat = latitude ?: return false
    val lng = longitude ?: return false
    return lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
}

private enum class MapLocationFreshness(val label: String) {
    FRESH("Fresh"),
    AGING("Aging"),
    STALE("Stale"),
}

private enum class GpsMapMarkerType {
    INCIDENT,
    FIELD_UNIT,
    DEVICE_LOCATION,
}

/** Stable interaction metadata kept separately from user-facing marker titles. */
/** Structural marker equality avoids delimiter collisions in user-controlled text. */
private data class GpsMapMarkerRenderSignature(
    val latitude: Double,
    val longitude: Double,
    val iconVariant: String,
    val title: String,
    val snippet: String,
)

private data class GpsMapMarkerTarget(
    val type: GpsMapMarkerType,
    val position: LatLng,
    val title: String,
    val summary: String,
    val incidentId: Long? = null,
    val userId: Long? = null,
    val stale: Boolean = false,
) {
    val responderKey: String?
        get() = if (type == GpsMapMarkerType.FIELD_UNIT && incidentId != null && userId != null) {
            "$incidentId:$userId"
        } else {
            null
        }
}

@Composable
private fun RowScope.MapFreshnessChip(
    freshness: MapLocationFreshness,
    count: Int,
    ageDescription: String,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content, marker) = when (freshness) {
        MapLocationFreshness.FRESH -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, colors.secondary)
        MapLocationFreshness.AGING -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer, colors.tertiary)
        MapLocationFreshness.STALE -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, colors.outline)
    }
    Surface(
        modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {
            contentDescription = "${freshness.label} field-unit locations: $count. $ageDescription."
        },
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Surface(
                modifier = Modifier.size(7.dp),
                shape = RoundedCornerShape(50),
                color = marker,
            ) {}
            Text(
                "${freshness.label} $count",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun ResponderLocation.mapAgeSeconds(now: Instant = Instant.now()): Long? {
    if (!latitude.isFinite() || !longitude.isFinite() ||
        latitude !in -90.0..90.0 || longitude !in -180.0..180.0
    ) return null
    val recorded = runCatching { Instant.parse(recordedAt) }.getOrNull() ?: return null
    // Duration.seconds truncates sub-second negative durations toward zero.
    // Compare instants first so even a timestamp milliseconds in the future is invalid.
    if (recorded.isAfter(now)) return null
    return runCatching { Duration.between(recorded, now).seconds }.getOrNull()
}

private fun ResponderLocation.mapFreshness(now: Instant = Instant.now()): MapLocationFreshness? {
    val age = mapAgeSeconds(now) ?: return null
    return when {
        age < 30L -> MapLocationFreshness.FRESH
        age <= 120L -> MapLocationFreshness.AGING
        else -> MapLocationFreshness.STALE
    }
}

private fun formatLocationAge(ageSeconds: Long): String = when {
    ageSeconds < 60L -> "under 1m ago"
    ageSeconds < 3600L -> "${ageSeconds / 60L}m ago"
    else -> "${ageSeconds / 3600L}h ago"
}

/**
 * Accuracy is a radius in meters, not a point coordinate. Never present malformed
 * server values (NaN, infinity, or negative numbers) as trustworthy precision.
 */
private fun Float?.validAccuracyLabel(prefix: String): String? =
    this?.takeIf { it.isFinite() && it >= 0f }
        ?.let { "$prefix${it.roundToInt()} m" }

/**
 * Incident coordinates may be valid but old. Keep the pin visible, while making
 * the recorded age explicit so users can confirm the destination before navigating.
 */
private fun Incident.locationAgeStatus(now: Instant = Instant.now()): Pair<String, Boolean> {
    val rawTimestamp = locationRecordedAt
        ?: return "Location time unavailable — confirm before navigating." to true
    val recordedAt = runCatching { Instant.parse(rawTimestamp) }.getOrNull()
        ?: return "Location timestamp invalid — confirm before navigating." to true
    // Compare instants before calculating whole seconds; sub-second future times
    // otherwise truncate to zero and could be presented as a current location.
    if (recordedAt.isAfter(now)) {
        return "Location timestamp is in the future — confirm before navigating." to true
    }
    val ageSeconds = runCatching { Duration.between(recordedAt, now).seconds }.getOrNull()
        ?: return "Location timestamp invalid — confirm before navigating." to true
    if (ageSeconds < 0L) {
        return "Location timestamp is in the future — confirm before navigating." to true
    }
    val recordedLabel = "Location recorded ${formatLocationAge(ageSeconds)}"
    return if (ageSeconds > 600L) {
        "$recordedLabel — confirm before navigating." to true
    } else {
        recordedLabel to false
    }
}

@Composable
private fun GpsMapFilterButtons(
    role: Role,
    hasIncidents: Boolean,
    hasActiveUnits: Boolean,
    onShowIncidents: () -> Unit,
    onShowActiveUnits: () -> Unit,
) {
    if (!hasIncidents && !(role == Role.DISPATCHER && hasActiveUnits)) return

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasIncidents) {
            androidx.compose.material3.AssistChip(
                onClick = onShowIncidents,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "Center map on active incidents"
                },
                label = { Text("Incidents", maxLines = 1) },
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
        if (role == Role.DISPATCHER && hasActiveUnits) {
            androidx.compose.material3.AssistChip(
                onClick = onShowActiveUnits,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "Center map on responder field units"
                },
                label = { Text("Field units", maxLines = 1) },
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun GpsMapCameraControls(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onShowAll: () -> Unit,
) {
    androidx.compose.material3.FilledTonalIconButton(
        onClick = onShowAll,
        enabled = enabled,
        modifier = modifier
            .size(56.dp)
            .semantics {
                contentDescription = "Fit all visible map locations"
            },
        shape = RoundedCornerShape(20.dp),
    ) {
        androidx.compose.material3.Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpsMapScreen(
    role: Role,
    user: User,
    incidents: List<Incident>,
    locationProvider: LocationProvider,
    routingProvider: RoutingProvider? = null,
    trackingController: ResponderLocationTrackingController? = null,
    initialSelectedIncidentId: Long? = null,
    responderLocations: List<ResponderLocation> = emptyList(),
    onRefreshResponderLocations: () -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val mapDensity = context.resources.displayMetrics.density
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val trackingStatusFlow = remember(trackingController) {
        trackingController?.status ?: MutableStateFlow(ResponderTrackingStatus())
    }
    val trackingStatus by trackingStatusFlow.collectAsStateWithLifecycle()
    // Keep the latest callback without restarting the polling coroutine on recomposition.
    val refreshResponderLocations by rememberUpdatedState(onRefreshResponderLocations)
    var deviceFix by remember { mutableStateOf<GeoFix?>(null) }
    // Increment on every selection transition so A → B → A cannot revive A's old route.
    var routeSelectionGeneration by remember { mutableStateOf(0) }
    var selectedIncidentId by rememberSaveable { mutableStateOf<Long?>(null) }
    var appliedInitialSelectionId by rememberSaveable { mutableStateOf<Long?>(null) }
    val mapSheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = androidx.compose.material3.rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
        ),
    )
    androidx.compose.runtime.LaunchedEffect(initialSelectedIncidentId) {
        val requestedId = initialSelectedIncidentId
        if (requestedId != null && requestedId != appliedInitialSelectionId) {
            routeSelectionGeneration += 1
            selectedIncidentId = requestedId
            appliedInitialSelectionId = requestedId
            mapSheetState.bottomSheetState.expand()
        }
    }
    // System Back collapses the selected-place sheet before leaving the map.
    BackHandler {
        if (mapSheetState.bottomSheetState.currentValue == SheetValue.Expanded) {
            scope.launch { mapSheetState.bottomSheetState.partialExpand() }
        } else {
            onBack()
        }
    }
    val latestDeviceFix by rememberUpdatedState(deviceFix)
    var hasAutoFramedInitialMapPoints by remember { mutableStateOf(false) }
    var hasAutoFramedIncidentPoints by remember { mutableStateOf(false) }
    var freshnessTick by remember { mutableStateOf(0) }
    var locationStatus by remember { mutableStateOf<String?>(null) }
    var selectedResponderKey by remember { mutableStateOf<String?>(null) }
    var selectedMarkerChoices by remember { mutableStateOf<List<GpsMapMarkerTarget>>(emptyList()) }
    var isLocationRequestInProgress by remember { mutableStateOf(false) }
    var retryLocationAfterSettings by remember { mutableStateOf(false) }
    var showAppSettingsAction by remember { mutableStateOf(false) }
    var routeResult by remember { mutableStateOf<RouteResult?>(null) }
    var routeIncidentId by remember { mutableStateOf<Long?>(null) }
    var routeStatus by remember { mutableStateOf<String?>(null) }
    var isRouteLoading by remember { mutableStateOf(false) }
    var pendingRouteIncident by remember { mutableStateOf<Incident?>(null) }
    var pendingNavigationIncident by remember { mutableStateOf<Incident?>(null) }
    var navigationPermissionStep by remember { mutableStateOf<String?>(null) }
    var routeProviderRecipient by remember(routingProvider) { mutableStateOf<String?>(null) }
    var isCheckingRouteProvider by remember(routingProvider) { mutableStateOf(false) }
    var routeProviderDisclosureUnavailable by remember(routingProvider) { mutableStateOf(false) }
    var routeJob by remember { mutableStateOf<Job?>(null) }
    var routeRequestGeneration by remember { mutableStateOf(0) }
    var requestRoute: (Incident) -> Unit = {}
    var hasValidatedInternet by remember { mutableStateOf<Boolean?>(null) }
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    DisposableEffect(connectivityManager) {
        if (connectivityManager == null) {
            hasValidatedInternet = false
            onDispose {}
        } else {
            fun refreshConnectivity() {
                val connected = runCatching { connectivityManager.hasValidatedInternet() }.getOrDefault(false)
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    hasValidatedInternet = connected
                } else {
                    Handler(Looper.getMainLooper()).post { hasValidatedInternet = connected }
                }
            }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshConnectivity()
                override fun onLost(network: Network) = refreshConnectivity()
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    refreshConnectivity()
            }
            refreshConnectivity()
            runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
                .onFailure { hasValidatedInternet = false }
            onDispose {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        }
    }
    val visibleIncidents = when (role) {
        Role.REQUESTER, Role.RESPONDER, Role.DRIVER -> incidents.filter { it.status.isActive }
        Role.DISPATCHER -> incidents.filter { it.status.isActive || it.status.name == "PENDING" }
        Role.ADMIN -> emptyList()
    }
    val validIncidentPoints = visibleIncidents.mapNotNull { incident ->
        val lat = incident.latitude
        val lng = incident.longitude
        if (incident.hasValidMapCoordinates() && lat != null && lng != null) {
            LatLng(lat, lng)
        } else {
            null
        }
    }
    val mapNow = remember(freshnessTick) { Instant.now() }
    val selectedIncident = visibleIncidents.firstOrNull { it.id == selectedIncidentId }
    val validResponderLocations = if (role == Role.DISPATCHER) {
        responderLocations.filter { it.mapAgeSeconds(mapNow) != null }
    } else {
        emptyList()
    }
    val freshResponderCount = validResponderLocations.count { it.mapFreshness(mapNow) == MapLocationFreshness.FRESH }
    val agingResponderCount = validResponderLocations.count { it.mapFreshness(mapNow) == MapLocationFreshness.AGING }
    val staleResponderCount = validResponderLocations.count { it.mapFreshness(mapNow) == MapLocationFreshness.STALE }
    // Annotation text only changes when its meaningful age bucket or freshness state
    // changes. The 15-second UI clock can still refresh sheet counts without forcing
    // every MapLibre marker and route line to be cleared and recreated.
    val mapAnnotationTimeKey = buildString {
        visibleIncidents.forEach { incident ->
            append("incident:").append(incident.id).append(':')
            append(incident.latitude).append(',').append(incident.longitude).append(':')
            append(incident.type.label).append(':').append(incident.status.label).append(':')
            append(incident.locationSource).append(':').append(incident.accuracyM).append(':')
            append(incident.locationNotes).append(':')
            append(incident.locationAgeStatus(mapNow).first).append('|')
        }
        validResponderLocations.forEach { location ->
            val ageSeconds = location.mapAgeSeconds(mapNow) ?: -1L
            append("unit:").append(location.userId).append(':').append(location.incidentId).append(':')
            append(location.latitude).append(',').append(location.longitude).append(':')
            append(location.accuracyM).append(':')
            append(location.mapFreshness(mapNow)?.name ?: "UNKNOWN").append(':')
            append(formatLocationAge(ageSeconds)).append('|')
        }
    }
    // Device and route signatures track only the geometry actually drawn on the map.
    val deviceFixMapKey = deviceFix?.let { "${it.latitude}:${it.longitude}" }
    val hasConfirmedIncidentCoordinates = visibleIncidents.any { it.hasValidMapCoordinates() }
    val validResponderPoints = validResponderLocations.map { LatLng(it.latitude, it.longitude) }
    // Keep stale last-known positions visible, but never let them dominate the initial
    // camera bounds. Fresh/aging units can still be framed when no incident pins exist;
    // dispatchers can explicitly center the map on stale units with the Field units action.
    val responderPointsForInitialFrame = validResponderLocations
        .filter { it.mapFreshness(mapNow) != MapLocationFreshness.STALE }
        .map { LatLng(it.latitude, it.longitude) }
    val initialMapPoints = if (role == Role.DISPATCHER) {
        validIncidentPoints + responderPointsForInitialFrame
    } else {
        validIncidentPoints
    }
    val incidentPinSummary = validIncidentPoints.size.takeIf { it > 0 }?.let { count ->
        if (count == 1) "1 incident pin on map" else "$count incident pins on map"
    }
    val defaultMapStatus = when {
        validIncidentPoints.isNotEmpty() ->
            "$incidentPinSummary."
        visibleIncidents.isNotEmpty() ->
            "Active incidents have no valid map pins."
        else ->
            "No incident pins yet."
    }
    val roleMapTitle = selectedIncident?.let { "Incident #${it.id} · ${it.type.label}" } ?: when (role) {
        Role.REQUESTER -> if (deviceFix != null) "Your location" else "Your map"
        Role.RESPONDER, Role.DRIVER -> "Response map"
        Role.DISPATCHER -> "Dispatch overview"
        Role.ADMIN -> "Map"
    }
    val roleMapSummary = when (role) {
        Role.REQUESTER -> when {
            showAppSettingsAction -> "Location access is off · incident pins remain visible."
            deviceFix != null -> "One-time location preview · this device only."
            else -> defaultMapStatus
        }
        Role.RESPONDER, Role.DRIVER -> defaultMapStatus
        Role.DISPATCHER -> if (validResponderLocations.isNotEmpty()) {
            "$defaultMapStatus $freshResponderCount field units currently fresh."
        } else {
            "$defaultMapStatus No field-unit location reports; units share locations only while en route."
        }
        Role.ADMIN -> defaultMapStatus
    }
    val collapsedMapSummary = when {
        showAppSettingsAction -> "Location access is off · incident pins remain visible."
        else -> locationStatus ?: roleMapSummary
    }
    val emptyMapMessage = when (role) {
        Role.REQUESTER -> "No active incident locations are available right now. You can still explore the map."
        Role.RESPONDER, Role.DRIVER -> "No active response locations are available right now."
        Role.DISPATCHER -> if (validResponderLocations.isNotEmpty()) {
            "No incident locations are available. Review field-unit freshness below."
        } else {
            "No incident or field-unit locations are available right now."
        }
        Role.ADMIN -> "No incident locations are available right now."
    }

    // Keep a selected field-unit row highlighted while its report remains available.
    androidx.compose.runtime.LaunchedEffect(validResponderLocations) {
        val selectedKey = selectedResponderKey ?: return@LaunchedEffect
        if (validResponderLocations.none { "${it.incidentId}:${it.userId}" == selectedKey }) {
            selectedResponderKey = null
        }
    }

    // Clear incident selection only when the selected incident is no longer visible.
    // Routine incident refreshes must not erase a marker selection or map action status.
    androidx.compose.runtime.LaunchedEffect(visibleIncidents) {
        if (selectedIncidentId != null && visibleIncidents.none { it.id == selectedIncidentId }) {
            routeSelectionGeneration += 1
            selectedIncidentId = null
        }
    }

    androidx.compose.runtime.LaunchedEffect(routingProvider, role, user.id) {
        routeProviderRecipient = null
        routeProviderDisclosureUnavailable = false
        val provider = routingProvider
        if ((role != Role.RESPONDER && role != Role.DRIVER) || provider == null) {
            isCheckingRouteProvider = false
            return@LaunchedEffect
        }
        isCheckingRouteProvider = true
        val disclosure = try {
            provider.providerDisclosure().getOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val recipient = disclosure?.consentRecipient()
        routeProviderRecipient = recipient
        routeProviderDisclosureUnavailable = recipient == null
        isCheckingRouteProvider = false
    }

    androidx.compose.runtime.LaunchedEffect(freshnessTick, routeResult) {
        val route = routeResult ?: return@LaunchedEffect
        if (!route.isUsable(System.currentTimeMillis())) {
            routeResult = null
            routeStatus = "This route preview expired. Request a fresh route before proceeding."
        }
    }

    // Pause periodic work whenever the app/screen is not STARTED. This avoids
    // background recomposition/network polling and refreshes freshness on return.
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(15_000)
                freshnessTick += 1
            }
        }
    }

    // Dispatcher location polling is foreground-only and performs an immediate
    // refresh on every return to STARTED; cancellation on STOPPED ends the loop.
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner, role, user.id) {
        if (role == Role.DISPATCHER) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                refreshResponderLocations()
                while (true) {
                    delay(15_000)
                    refreshResponderLocations()
                }
            }
        }
    }

    fun selectMarkerTarget(target: GpsMapMarkerTarget) {
        selectedMarkerChoices = emptyList()
        when (target.type) {
            GpsMapMarkerType.INCIDENT -> {
                val incidentId = target.incidentId ?: return
                if (visibleIncidents.none { it.id == incidentId }) {
                    locationStatus = "That incident location is no longer available. Choose a current map pin."
                    return
                }
                selectedResponderKey = null
                routeSelectionGeneration += 1
                selectedIncidentId = incidentId
                locationStatus = null
            }
            GpsMapMarkerType.FIELD_UNIT -> {
                val responderKey = target.responderKey
                val currentLocation = validResponderLocations.firstOrNull {
                    "${it.incidentId}:${it.userId}" == responderKey
                }
                if (responderKey == null || currentLocation == null) {
                    locationStatus = "That field-unit report is no longer available or has expired. Refresh the map locations."
                    return
                }
                routeSelectionGeneration += 1
                selectedIncidentId = null
                selectedResponderKey = responderKey
                locationStatus = if (currentLocation.mapFreshness(mapNow) == MapLocationFreshness.STALE) {
                    "Responder location · Last known only; confirm with dispatch."
                } else {
                    "Responder location · Consent-based foreground report; see the highlighted field-unit entry for age and accuracy."
                }
            }
            GpsMapMarkerType.DEVICE_LOCATION -> {
                val currentFix = deviceFix
                if (currentFix == null ||
                    currentFix.latitude != target.position.latitude ||
                    currentFix.longitude != target.position.longitude
                ) {
                    locationStatus = "That one-time device location preview has changed. Use Locate me to refresh it."
                    return
                }
                routeSelectionGeneration += 1
                selectedIncidentId = null
                selectedResponderKey = null
                locationStatus = "Your location · One-time preview on this device only; not uploaded or continuously tracked."
            }
        }
        scope.launch { mapSheetState.bottomSheetState.expand() }
    }
    // MapLibre installs its click listener once; always dispatch through the latest
    // Compose callback so refreshed incidents and responder reports are revalidated.
    val latestSelectMarkerTarget by rememberUpdatedState(::selectMarkerTarget)
    val latestVisibleIncidents by rememberUpdatedState(visibleIncidents)
    val latestValidResponderLocations by rememberUpdatedState(validResponderLocations)



    var mapLibreMap by remember(context, role) { mutableStateOf<MapLibreMap?>(null) }
    var mapStyleLoaded by remember(context, role) { mutableStateOf(false) }
    var mapDataLoaded by remember(context, role) { mutableStateOf(false) }
    var mapLoadError by remember(context, role) { mutableStateOf(false) }
    var savedCameraLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var savedCameraLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var savedCameraZoom by rememberSaveable { mutableStateOf<Double?>(null) }
    var savedCameraBearing by rememberSaveable { mutableStateOf<Double?>(null) }
    var savedCameraTilt by rememberSaveable { mutableStateOf<Double?>(null) }
    var mapHeaderBottomPx by remember { mutableStateOf(0) }
    val markerTargets = remember(context, role) { mutableMapOf<Long, GpsMapMarkerTarget>() }
    // Keep annotation instances across refreshes; reconcile only changed features.
    val renderedMarkers = remember(context, role) { mutableMapOf<String, Marker>() }
    val renderedMarkerSignatures = remember(context, role) { mutableMapOf<String, GpsMapMarkerRenderSignature>() }
    var renderedMapOwner by remember(context, role) { mutableStateOf<MapLibreMap?>(null) }
    var renderedRoutePolyline by remember(context, role) { mutableStateOf<Polyline?>(null) }
    var renderedRouteSignature by remember(context, role) { mutableStateOf<List<Pair<Double, Double>>?>(null) }
    var incidentClusterSource by remember(context, role) { mutableStateOf<GeoJsonSource?>(null) }
    var responderClusterSource by remember(context, role) { mutableStateOf<GeoJsonSource?>(null) }
    var staleResponderClusterSource by remember(context, role) { mutableStateOf<GeoJsonSource?>(null) }
    val mapView = remember(context, role) {
        MapLibre.getInstance(context)
        MapView(context).apply {
            contentDescription = if (role == Role.DISPATCHER) {
                "Interactive emergency response map. Select an incident pin to open its details. Drag the bottom sheet to review incident locations and responder freshness."
            } else {
                "Interactive emergency response map. Select an incident pin to open its details. Drag the bottom sheet to review active incident locations."
            }
            onCreate(null)
        }
    }

    DisposableEffect(mapView, lifecycleOwner) {
        var disposed = false
        var cameraIdleListener: MapLibreMap.OnCameraIdleListener? = null
        var mapIsStarted = false
        var mapIsResumed = false
        val lifecycle = lifecycleOwner.lifecycle
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (!mapIsStarted) {
                        mapView.onStart()
                        mapIsStarted = true
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (!mapIsStarted) {
                        mapView.onStart()
                        mapIsStarted = true
                    }
                    if (!mapIsResumed) {
                        mapView.onResume()
                        mapIsResumed = true
                    }
                    if (!locationProvider.hasPermission() && latestDeviceFix != null) {
                        deviceFix = null
                        locationStatus = "Location permission is off. Previous preview cleared. Grant permission to locate again."
                    }
                    if (locationProvider.hasPermission()) showAppSettingsAction = false
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (mapIsResumed) {
                        mapView.onPause()
                        mapIsResumed = false
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    if (mapIsResumed) {
                        mapView.onPause()
                        mapIsResumed = false
                    }
                    if (mapIsStarted) {
                        mapView.onStop()
                        mapIsStarted = false
                    }
                }
                else -> Unit
            }
        }
        val memoryCallbacks = object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
            override fun onLowMemory() {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    if (!disposed) mapView.onLowMemory()
                } else {
                    Handler(Looper.getMainLooper()).post {
                        if (!disposed) mapView.onLowMemory()
                    }
                }
            }
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) onLowMemory()
            }
        }
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        lifecycle.addObserver(lifecycleObserver)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !mapIsStarted) {
            mapView.onStart()
            mapIsStarted = true
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !mapIsResumed) {
            mapView.onResume()
            mapIsResumed = true
        }
        val failLoadingListener = MapView.OnDidFailLoadingMapListener {
            if (!disposed) {
                mapDataLoaded = false
                mapLoadError = true
                locationStatus = MAP_DATA_UNAVAILABLE_STATUS
            }
        }
        val finishLoadingListener = MapView.OnDidFinishLoadingMapListener {
            if (!disposed) {
                mapDataLoaded = true
                mapLoadError = false
                if (locationStatus == MAP_DATA_UNAVAILABLE_STATUS) locationStatus = null
            }
        }
        mapView.addOnDidFailLoadingMapListener(failLoadingListener)
        mapView.addOnDidFinishLoadingMapListener(finishLoadingListener)
        mapView.getMapAsync { map ->
            if (disposed) return@getMapAsync
            mapLibreMap = map
            val cameraListener = MapLibreMap.OnCameraIdleListener {
                if (!disposed) {
                    val camera = map.cameraPosition
                    val target = camera.target
                    if (target != null && target.latitude.isFinite() && target.longitude.isFinite() &&
                        camera.zoom.isFinite()
                    ) {
                        savedCameraLatitude = target.latitude
                        savedCameraLongitude = target.longitude
                        savedCameraZoom = camera.zoom
                        savedCameraBearing = camera.bearing
                        savedCameraTilt = camera.tilt
                    }
                }
            }
            cameraIdleListener = cameraListener
            map.addOnCameraIdleListener(cameraListener)
            map.setOnMarkerClickListener { marker ->
                // Resolve behavior from the annotation ID, never from editable display text.
                val target = markerTargets[marker.id] ?: return@setOnMarkerClickListener false
                // Several pins can occupy the same screen area even when their coordinates
                // are not identical. Resolve the visible collision in screen pixels at the
                // current zoom instead of silently selecting whichever annotation is on top.
                val candidates = markerTargets.values.toList()
                val anchorIndex = candidates.indexOfFirst { it == target }
                val nearbyIndices = if (anchorIndex >= 0) {
                    val screenPoints = candidates.map { candidate ->
                        map.projection.toScreenLocation(candidate.position).let { it.x to it.y }
                    }
                    gpsMapNearbyTargetIndices(
                        points = screenPoints,
                        anchorIndex = anchorIndex,
                        radiusPx = 48f * mapDensity,
                    )
                } else {
                    emptyList()
                }
                val nearbyTargets = nearbyIndices
                    .sortedBy { if (it == anchorIndex) 0 else 1 }
                    .mapNotNull(candidates::getOrNull)
                    .distinctBy { listOf(it.type.name, it.incidentId?.toString().orEmpty(), it.userId?.toString().orEmpty()).joinToString(":") }
                if (nearbyTargets.size > 1) {
                    selectedMarkerChoices = nearbyTargets
                    routeSelectionGeneration += 1
                    selectedIncidentId = null
                    selectedResponderKey = null
                    locationStatus = "${nearbyTargets.size} map locations overlap here. Choose the location you need."
                    scope.launch { mapSheetState.bottomSheetState.expand() }
                } else {
                    latestSelectMarkerTarget(target)
                }
                true
            }
            map.setStyle(OPENFREEMAP_LIBERTY_STYLE) {
            map.getStyle { style ->
                val sources = style.installGpsMapClusterLayers(context)
                incidentClusterSource = sources.incidents
                responderClusterSource = sources.responders
                staleResponderClusterSource = sources.staleResponders
            }
                // A style replacement invalidates style-backed annotations. Force the
                // renderer effect to rebuild markers and route on the newly loaded style.
                renderedMapOwner = null
                mapStyleLoaded = true
                positionMapCompass(
                    map,
                    mapHeaderBottomPx.takeIf { it > 0 } ?: (180f * mapDensity).roundToInt(),
                    mapDensity,
                )
                val restoredTarget = if (
                    savedCameraLatitude != null && savedCameraLongitude != null &&
                    savedCameraLatitude!!.isFinite() && savedCameraLongitude!!.isFinite() &&
                    savedCameraLatitude!! in -90.0..90.0 && savedCameraLongitude!! in -180.0..180.0
                ) {
                    LatLng(savedCameraLatitude!!, savedCameraLongitude!!)
                } else {
                    LatLng(TACLOBAN_LATITUDE, TACLOBAN_LONGITUDE)
                }
                map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                    .target(restoredTarget)
                    .zoom(savedCameraZoom?.takeIf { it.isFinite() } ?: 12.0)
                    .bearing(savedCameraBearing?.takeIf { it.isFinite() } ?: 0.0)
                    .tilt(savedCameraTilt?.takeIf { it.isFinite() } ?: 0.0)
                    .build()
            }
        }
        onDispose {
            disposed = true
            mapView.removeOnDidFailLoadingMapListener(failLoadingListener)
            mapView.removeOnDidFinishLoadingMapListener(finishLoadingListener)
            lifecycle.removeObserver(lifecycleObserver)
            context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            cameraIdleListener?.let { listener ->
                mapLibreMap?.removeOnCameraIdleListener(listener)
            }
            cameraIdleListener = null
            if (mapIsResumed) mapView.onPause()
            if (mapIsStarted) mapView.onStop()
            mapView.onDestroy()
            mapLibreMap = null
            mapStyleLoaded = false
            mapDataLoaded = false
            mapLoadError = false
        }
    }

    val incidentPinIcon = remember(context) {
        context.mapLibrePinIcon(com.tacresq.app.R.drawable.ic_map_incident_pin)
    }
    val selectedIncidentPinIcon = remember(context) {
        context.mapLibrePinIcon(com.tacresq.app.R.drawable.ic_map_incident_pin, scale = 0.98f)
    }
    val responderPinIcon = remember(context) {
        context.mapLibrePinIcon(com.tacresq.app.R.drawable.ic_map_responder_pin)
    }
    val staleResponderPinIcon = remember(context) {
        context.mapLibrePinIcon(com.tacresq.app.R.drawable.ic_map_responder_pin, alpha = 110)
    }
    val myLocationPinIcon = remember(context) {
        context.mapLibrePinIcon(com.tacresq.app.R.drawable.ic_map_my_location_pin)
    }

    fun animateToPoint(point: LatLng, zoom: Double) {
        mapLibreMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), zoom),
            420,
        )
    }

    fun ensureMapReadyForCamera(): Boolean {
        if (mapLibreMap == null || !mapStyleLoaded) {
            locationStatus = "Map is still loading. Try centering this location again in a moment."
            return false
        }
        return true
    }

    fun fitMapPoints(points: List<LatLng>) {
        val map = mapLibreMap ?: return
        if (points.isEmpty()) return
        if (points.size == 1) {
            animateToPoint(points.first(), 15.0)
            return
        }
        val bounds = LatLngBounds.Builder()
            .includes(points)
            .build()
        val density = context.resources.displayMetrics.density
        val sheetTop = runCatching { mapSheetState.bottomSheetState.requireOffset() }.getOrNull()
        val insets = gpsMapCameraInsets(mapView.width, mapView.height, density, sheetTop)
        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(
                bounds,
                insets.left,
                insets.top,
                insets.right,
                insets.bottom,
            ),
            480,
        )
    }

    fun showIncidentPins() {
        if (validIncidentPoints.isNotEmpty() && !ensureMapReadyForCamera()) return
        when (validIncidentPoints.size) {
            0 -> locationStatus = "There are no valid incident coordinates to center on yet."
            1 -> {
                deviceFix = null
                animateToPoint(validIncidentPoints.first(), 15.0)
                locationStatus = "Centered on 1 incident pin."
            }
            else -> {
                deviceFix = null
                fitMapPoints(validIncidentPoints)
                locationStatus = "Showing ${validIncidentPoints.size} incident pins."
            }
        }
    }

    fun focusIncidentOnMap(incident: Incident) {
        selectedResponderKey = null
        routeSelectionGeneration += 1
        selectedIncidentId = incident.id
        scope.launch { mapSheetState.bottomSheetState.expand() }
        val latitude = incident.latitude
        val longitude = incident.longitude
        if (!incident.hasValidMapCoordinates() || latitude == null || longitude == null) {
            locationStatus = "Incident #${incident.id} has no usable map coordinates."
            return
        }
        if (!ensureMapReadyForCamera()) return
        deviceFix = null
        animateToPoint(LatLng(latitude, longitude), 15.0)
        locationStatus = "Centered on incident #${incident.id}. Check the recorded location time before navigating."
    }

    fun showActiveUnits() {
        if (validResponderPoints.isNotEmpty() && !ensureMapReadyForCamera()) return
        when (validResponderPoints.size) {
            0 -> locationStatus = "No valid field-unit location reports are available. Units must be en route and explicitly sharing foreground location."
            1 -> {
                deviceFix = null
                animateToPoint(validResponderPoints.first(), 15.0)
                val freshness = validResponderLocations.first().mapFreshness(mapNow)
                locationStatus = if (freshness == MapLocationFreshness.STALE) {
                    "Centered on a stale unit pin · confirm with dispatch."
                } else {
                    "Centered on latest unit pin · ${freshness?.label?.lowercase() ?: "freshness unknown"}."
                }
            }
            else -> {
                deviceFix = null
                fitMapPoints(validResponderPoints)
                locationStatus = "Showing field-unit locations: $freshResponderCount fresh, $agingResponderCount aging, $staleResponderCount stale. Stale pins are not live."
            }
        }
    }

    fun showAllVisiblePoints() {
        val mapPoints = if (role == Role.DISPATCHER) {
            validIncidentPoints + validResponderPoints
        } else {
            validIncidentPoints
        }
        val points = mapPoints + listOfNotNull(deviceFix?.let { LatLng(it.latitude, it.longitude) })
        if (points.isEmpty()) {
            locationStatus = "No valid map locations are available to show yet."
            return
        }
        if (mapLibreMap == null || !mapStyleLoaded) {
            locationStatus = "Map is still loading. Try again in a moment."
            return
        }
        fitMapPoints(points)
        locationStatus = if (role == Role.DISPATCHER && validResponderPoints.isNotEmpty()) {
            "Showing all incident and field-unit locations. Stale unit pins are last known, not live."
        } else if (deviceFix != null && mapPoints.isNotEmpty()) {
            "Showing incident locations and your one-time device preview."
        } else if (points.size == 1) {
            "Showing the available map location."
        } else {
            "Showing all available incident locations."
        }
    }

    fun retryMapLoad() {
        val map = mapLibreMap
        if (map == null) {
            locationStatus = "Map is still initializing. Try again in a moment."
            return
        }
        mapLoadError = false
        mapDataLoaded = false
        mapStyleLoaded = false
        locationStatus = "Retrying map data…"
        incidentClusterSource = null
        responderClusterSource = null
        staleResponderClusterSource = null
        map.setStyle(OPENFREEMAP_LIBERTY_STYLE) {
            map.getStyle { style ->
                val sources = style.installGpsMapClusterLayers(context)
                incidentClusterSource = sources.incidents
                responderClusterSource = sources.responders
            }
            // Retry replaces the style too; never keep references to annotations
            // created against the previous style.
            renderedMapOwner = null
            mapStyleLoaded = true
            positionMapCompass(
                map,
                mapHeaderBottomPx.takeIf { it > 0 } ?: (180f * mapDensity).roundToInt(),
                mapDensity,
            )
        }
    }

    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pending = pendingRouteIncident
            if (pending != null) {
                pendingRouteIncident = null
                requestRoute(pending)
            } else {
                retryLocationAfterSettings = true
            }
        } else {
            locationStatus = "Location services remain off. Incident pins remain available; enable Location in Quick Settings or try again."
        }
    }

    fun openAppLocationSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                locationStatus = "Could not open TACResQ app settings. Open Android Settings, choose Apps, then TACResQ, and allow Location."
            }
    }

    fun locateOnce() {
        // Claim synchronously before launching: rapid taps must not start overlapping location requests.
        if (isLocationRequestInProgress) return
        isLocationRequestInProgress = true
        scope.launch {
            try {
                deviceFix = null
                showAppSettingsAction = false
                locationStatus = "Checking device location settings…"
                when (val settings = locationProvider.checkLocationSettings()) {
                    LocationSettingsCheck.Ready -> {
                        locationStatus = "Getting a fresh device location…"
                        when (val result = locationProvider.currentFix()) {
                            is LocationCaptureResult.Success -> {
                                showAppSettingsAction = false
                                val fix = result.fix
                                deviceFix = fix
                                // Recenter after the native MapView has processed the latest layout.
                                // Zoom in enough for the user to recognize their own position, and
                                // do this on every Locate me action (not only the first map render).
                                mapView.post {
                                    // Match zoom to the reported uncertainty; avoid implying
                                    // street-level precision when the fix is only approximate.
                                    animateToPoint(LatLng(fix.latitude, fix.longitude), gpsMapPreviewZoom(fix.accuracyM))
                                    mapView.invalidate()
                                }
                                val precision = if (fix.hasPrecisePermission) "Precise" else "Approximate"
                                locationStatus = "One-time $precision location preview on this device only; not uploaded. ${gpsAccuracyStatus(fix.accuracyM)}"
                            }
                            LocationCaptureResult.PermissionDenied -> {
                                showAppSettingsAction = true
                                locationStatus = "Location permission is unavailable. Incident pins remain available; allow Location in TACResQ app settings to use Locate me"
                            }
                            LocationCaptureResult.ServicesDisabled -> {
                                locationStatus = "Opening Android Location settings. Turn Location on, return here, and tap Locate me again."
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                }.onFailure {
                                    locationStatus = "Device Location is off. Turn it on in Quick Settings, return here, and tap Locate me again."
                                }
                            }
                            LocationCaptureResult.TimedOut -> {
                                locationStatus = "No fresh location fix arrived in time. Try again."
                            }
                            LocationCaptureResult.InvalidFix -> {
                                locationStatus = "The device returned invalid or stale coordinates. Try again."
                            }
                            LocationCaptureResult.Unavailable -> {
                                locationStatus = "Current location is unavailable. Check signal and try again."
                            }
                        }
                    }
                    is LocationSettingsCheck.NeedsResolution -> {
                        locationStatus = "Android needs your approval to enable the device location settings required for a fresh fix."
                        runCatching {
                            locationSettingsLauncher.launch(
                                IntentSenderRequest.Builder(settings.exception.resolution).build(),
                            )
                        }.onFailure {
                            locationStatus = "Could not open the location settings prompt. Enable Location in Quick Settings and try again."
                        }
                    }
                    LocationSettingsCheck.Unavailable -> {
                        locationStatus = "Could not open the automatic location prompt. Opening Android Location settings instead."
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }.onFailure {
                            locationStatus = "Could not open Location settings. Enable Location in Quick Settings and try again."
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Respect lifecycle cancellation; do not turn it into a location error.
                throw cancelled
            } catch (failure: Exception) {
                // Location providers and OEM settings APIs can throw unexpectedly.
                // Keep the map/incident pins usable and give the user a recoverable status.
                deviceFix = null
                locationStatus = "Could not get your location right now. Incident pins remain available; try again."
            } finally {
                isLocationRequestInProgress = false
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(retryLocationAfterSettings) {
        if (retryLocationAfterSettings) {
            retryLocationAfterSettings = false
            locateOnce()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingNavigationIncident != null) {
            if (granted) navigationPermissionStep = "check"
            else {
                routeStatus = "Notifications are required for the visible location-sharing service. No navigation session was started."
                pendingNavigationIncident = null
                navigationPermissionStep = null
            }
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showAppSettingsAction = false
            if (pendingNavigationIncident != null) {
                navigationPermissionStep = "check"
            } else {
                val pending = pendingRouteIncident
                if (pending != null) {
                    pendingRouteIncident = null
                    requestRoute(pending)
                } else {
                    locateOnce()
                }
            }
        } else {
            deviceFix = null
            showAppSettingsAction = true
            if (pendingNavigationIncident != null) {
                routeStatus = "Location permission denied. No live navigation or location sharing was started."
                pendingNavigationIncident = null
                navigationPermissionStep = null
            } else {
                routeStatus = if (pendingRouteIncident != null) {
                    "Location permission denied. No route was requested; allow approximate location and try again."
                } else routeStatus
                pendingRouteIncident = null
            }
            locationStatus = "Location permission denied. Incident pins remain available; allow Location in TACResQ app settings to use Locate me"
        }
    }
    androidx.compose.runtime.LaunchedEffect(navigationPermissionStep) {
        when (navigationPermissionStep) {
            "check" -> {
                val incident = pendingNavigationIncident
                if (incident == null || trackingController == null) {
                    routeStatus = "Live navigation is unavailable on this screen."
                    pendingNavigationIncident = null
                    navigationPermissionStep = null
                } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    navigationPermissionStep = "notifications"
                } else if (!locationProvider.hasApproximatePermission()) {
                    navigationPermissionStep = "location"
                } else {
                    navigationPermissionStep = "start"
                }
            }
            "notifications" -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            "location" -> locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            "start" -> {
                val incident = pendingNavigationIncident
                if (incident != null && trackingController != null) {
                    val result = trackingController.startNavigation(incident.id)
                    routeIncidentId = incident.id
                    routeResult = null
                    routeStatus = result.exceptionOrNull()?.message
                        ?: "Live navigation and dispatcher location sharing are starting."
                    pendingNavigationIncident = null
                }
                navigationPermissionStep = null
            }
            null -> Unit
        }
    }
    requestRoute = { incident ->
        routeJob?.cancel()
        routeRequestGeneration += 1
        val generation = routeRequestGeneration
        val selectionGeneration = routeSelectionGeneration
        fun isCurrentRequest(): Boolean =
            generation == routeRequestGeneration &&
                selectionGeneration == routeSelectionGeneration &&
                selectedIncidentId == incident.id
        routeResult = null
        routeIncidentId = incident.id
        isRouteLoading = false
        val provider = routingProvider
        val destinationLatitude = incident.latitude
        val destinationLongitude = incident.longitude
        when {
            role != Role.RESPONDER && role != Role.DRIVER -> {
                routeStatus = "In-app route previews are available to assigned field responders and drivers."
            }
            incident.assignedTo != user.id && user.id !in incident.assignedUserIds -> {
                routeStatus = "Only the assigned responder or driver can request an in-app route for this incident."
            }
            incident.statusFor(user.id) != IncidentStatus.ASSIGNED &&
                incident.statusFor(user.id) != IncidentStatus.EN_ROUTE -> {
                routeStatus = "In-app routing is available only while your assigned unit is assigned or en route."
            }
            provider == null -> {
                routeStatus = "In-app routing is disabled or not configured. No route or ETA was generated; a server-side driving service must be configured and enabled."
            }
            !incident.hasValidMapCoordinates() || destinationLatitude == null || destinationLongitude == null -> {
                routeStatus = "This incident has no valid destination coordinate. Confirm the location with dispatch."
            }
            !locationProvider.hasApproximatePermission() -> {
                pendingRouteIncident = incident
                routeStatus = "Allow approximate location to get a fresh route origin."
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            else -> {
                isRouteLoading = true
                routeStatus = "Getting a fresh location and calculating a road-route estimate…"
                routeJob = scope.launch {
                    try {
                        val settings = locationProvider.checkLocationSettings()
                        if (!isCurrentRequest()) return@launch
                        when (settings) {
                            LocationSettingsCheck.Ready -> Unit
                            is LocationSettingsCheck.NeedsResolution -> {
                                pendingRouteIncident = incident
                                routeStatus = "Enable device location to calculate a route."
                                locationSettingsLauncher.launch(
                                    IntentSenderRequest.Builder(settings.exception.resolution).build(),
                                )
                                return@launch
                            }
                            LocationSettingsCheck.Unavailable -> {
                                routeStatus = "Device location settings are unavailable. No route was requested."
                                return@launch
                            }
                        }
                        val capture = locationProvider.currentFix()
                        if (!isCurrentRequest()) return@launch
                        val fix = when (capture) {
                            is LocationCaptureResult.Success -> capture.fix
                            LocationCaptureResult.PermissionDenied -> {
                                routeStatus = "Approximate location permission is required for a current route origin."
                                return@launch
                            }
                            LocationCaptureResult.ServicesDisabled -> {
                                routeStatus = "Device location is off. Enable it before requesting a route."
                                return@launch
                            }
                            LocationCaptureResult.TimedOut,
                            LocationCaptureResult.InvalidFix,
                            LocationCaptureResult.Unavailable,
                            -> {
                                routeStatus = "A fresh current location is unavailable. No route was requested."
                                return@launch
                            }
                        }
                        // Selection can change while location capture is suspended. Do not
                        // send a route request for an incident the user has left.
                        if (!isCurrentRequest()) {
                            return@launch
                        }
                        val result = provider.route(
                            RouteCoordinate(fix.latitude, fix.longitude),
                            RouteCoordinate(destinationLatitude, destinationLongitude),
                            RouteMode.DRIVING,
                            incident.id,
                        )
                        // Providers may not cooperate with coroutine cancellation. Reject
                        // late results before they can replace route, status, or camera state.
                        if (generation != routeRequestGeneration || selectedIncidentId != incident.id) {
                            return@launch
                        }
                        val route = result.getOrNull()
                        if (route == null || !route.isUsable(System.currentTimeMillis())) {
                            routeStatus = result.exceptionOrNull()?.message
                                ?: "The routing service returned no current usable route."
                            return@launch
                        }
                        if (generation != routeRequestGeneration || selectedIncidentId != incident.id) {
                            return@launch
                        }
                        routeResult = route
                        routeIncidentId = incident.id
                        routeStatus = "Route preview only. No live traffic, flooding, road closures, or emergency-vehicle access is verified."
                        val routePoints = route.geometry.map { LatLng(it.latitude, it.longitude) }
                        mapView.post {
                            if (isCurrentRequest()) {
                                fitMapPoints(routePoints)
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (isCurrentRequest()) {
                            routeStatus = "Could not calculate a route. Check connectivity and confirm the destination with dispatch."
                        }
                    } finally {
                        if (generation == routeRequestGeneration) isRouteLoading = false
                    }
                }
            }
        }
    }
    fun canStartLiveNavigation(incident: Incident): Boolean =
        (role == Role.RESPONDER || role == Role.DRIVER) && trackingController != null &&
            routingProvider != null &&
            (incident.assignedTo == user.id || user.id in incident.assignedUserIds) &&
            incident.statusFor(user.id) == IncidentStatus.EN_ROUTE &&
            incident.hasValidMapCoordinates()

    fun liveNavigationUnavailableMessage(incident: Incident): String? {
        if (!canStartLiveNavigation(incident) ||
            (trackingStatus.navigationActive && trackingStatus.incidentId == incident.id) ||
            routeProviderRecipient != null
        ) return null
        return if (isCheckingRouteProvider) {
            "Checking the active routing provider before enabling live navigation. This check sends no location coordinates."
        } else if (routeProviderDisclosureUnavailable) {
            "The active routing provider could not be verified. This check shared no coordinates; live navigation is unavailable."
        } else null
    }

    fun beginLiveNavigation(incident: Incident) {
        if (!canStartLiveNavigation(incident)) {
            routeStatus = "Live navigation is available only to your assigned en-route unit with a configured route provider."
            return
        }
        if (routeProviderRecipient == null) {
            routeStatus = "The active routing provider could not be verified. No live navigation or location sharing was started."
            return
        }
        pendingNavigationIncident = incident
        routeIncidentId = incident.id
        routeResult = null
        navigationPermissionStep = "check"
    }

    val onLocateClick: () -> Unit = {
        if (locationProvider.hasPermission()) {
            locateOnce()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    val liveNavigationForSelection = trackingStatus.navigationActive &&
        trackingStatus.incidentId == selectedIncidentId
    val displayedRoute = if (liveNavigationForSelection) {
        trackingStatus.navigationRoute?.takeIf {
            it.isUsable(System.currentTimeMillis(), maxAgeMillis = Long.MAX_VALUE)
        }
    } else {
        routeResult?.takeIf {
            routeIncidentId == selectedIncidentId && it.isUsable(System.currentTimeMillis())
        }
    }
    // Compare actual ordered geometry at the effect boundary; a hash collision must not
    // prevent reconciliation from observing a changed route.
    val displayedRouteMapKey = displayedRoute?.geometry?.map { it.latitude to it.longitude }

    androidx.compose.runtime.LaunchedEffect(
        mapLibreMap,
        mapStyleLoaded,
        incidentClusterSource,
        responderClusterSource,
        staleResponderClusterSource,
        deviceFixMapKey,
        displayedRouteMapKey,
        trackingStatus.incidentId,
        trackingStatus.navigationActive,
        selectedIncidentId,
        mapAnnotationTimeKey,
    ) {
        val map = mapLibreMap
        if (map == null || !mapStyleLoaded) return@LaunchedEffect

        // Clear once when the underlying MapLibre map instance changes. Normal
        // data refreshes are reconciled incrementally and retain unchanged annotations.
        if (renderedMapOwner !== map) {
            map.clear()
            renderedMarkers.clear()
            renderedMarkerSignatures.clear()
            markerTargets.clear()
            renderedRoutePolyline = null
            renderedRouteSignature = null
            renderedMapOwner = map
        }
        markerTargets.clear()
        val desiredMarkerKeys = mutableSetOf<String>()

        fun upsertMarker(
            key: String,
            position: LatLng,
            icon: Icon,
            title: String,
            snippet: String,
            signature: GpsMapMarkerRenderSignature,
        ): Marker {
            desiredMarkerKeys += key
            val existing = renderedMarkers[key]
            if (existing == null) {
                return map.addMarker(
                    MarkerOptions().position(position).icon(icon).title(title).snippet(snippet),
                ).also {
                    renderedMarkers[key] = it
                    renderedMarkerSignatures[key] = signature
                }
            }
            if (renderedMarkerSignatures[key] != signature) {
                existing.position = position
                existing.icon = icon
                existing.title = title
                existing.snippet = snippet
                map.updateMarker(existing)
                renderedMarkerSignatures[key] = signature
            }
            return existing
        }

        // Publish independently clustered emergency incidents and field-unit reports.
        val incidentFeatures = visibleIncidents.mapNotNull { it.toGpsClusterFeature(selectedIncidentId) }
        incidentClusterSource?.setGeoJson(FeatureCollection.fromFeatures(incidentFeatures.toTypedArray()))
        val responderFeatures = validResponderLocations
            .filter { it.mapFreshness(mapNow) != MapLocationFreshness.STALE }
            .mapNotNull { it.toGpsClusterFeature(mapNow) }
        responderClusterSource?.setGeoJson(FeatureCollection.fromFeatures(responderFeatures.toTypedArray()))
        val staleResponderFeatures = validResponderLocations
            .filter { it.mapFreshness(mapNow) == MapLocationFreshness.STALE }
            .mapNotNull { it.toGpsClusterFeature(mapNow) }
        staleResponderClusterSource?.setGeoJson(
            FeatureCollection.fromFeatures(staleResponderFeatures.toTypedArray()),
        )

        deviceFix?.let { fix ->
            val marker = upsertMarker(
                key = "device-location",
                position = LatLng(fix.latitude, fix.longitude),
                icon = myLocationPinIcon,
                title = "Your location · this device only",
                snippet = "One-time location preview. Not uploaded or continuously tracked.",
                signature = GpsMapMarkerRenderSignature(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    iconVariant = "device-location",
                    title = "Your location · this device only",
                    snippet = "One-time location preview. Not uploaded or continuously tracked.",
                ),
            )
            markerTargets[marker.id] = GpsMapMarkerTarget(
                type = GpsMapMarkerType.DEVICE_LOCATION,
                position = LatLng(fix.latitude, fix.longitude),
                title = "Your location · this device",
                summary = "One-time preview only · not uploaded or continuously tracked",
            )
        }
        // Compare the full ordered geometry, not hashCode(): a collision must never
        // leave an obsolete route visible after a genuine route change.
        val routeSignature = displayedRoute?.geometry?.map { it.latitude to it.longitude }
        if (renderedRouteSignature != routeSignature) {
            renderedRoutePolyline?.let(map::removePolyline)
            renderedRoutePolyline = displayedRoute?.let { route ->
                map.addPolyline(
                    PolylineOptions()
                        .addAll(route.geometry.map { LatLng(it.latitude, it.longitude) })
                        .color(Color.rgb(0, 119, 182))
                        .width(6f),
                )
            }
            renderedRouteSignature = routeSignature
        }

        // Remove only records no longer present in the validated visible set.
        (renderedMarkers.keys - desiredMarkerKeys).forEach { key ->
            renderedMarkers.remove(key)?.let(map::removeMarker)
            renderedMarkerSignatures.remove(key)
        }

        val hasRestorableCamera = savedCameraLatitude != null && savedCameraLongitude != null
        val shouldFrameInitialUnits = !hasRestorableCamera && !hasAutoFramedInitialMapPoints &&
            validIncidentPoints.isEmpty() && responderPointsForInitialFrame.isNotEmpty()
        val shouldFrameIncidentPoints = !hasRestorableCamera && !hasAutoFramedIncidentPoints && validIncidentPoints.isNotEmpty()
        if (deviceFix == null && (shouldFrameInitialUnits || shouldFrameIncidentPoints)) {
            hasAutoFramedInitialMapPoints = true
            if (shouldFrameIncidentPoints) hasAutoFramedIncidentPoints = true
            val pointsToFrame = if (shouldFrameIncidentPoints) initialMapPoints else responderPointsForInitialFrame
            mapView.post {
                fitMapPoints(pointsToFrame)
                locationStatus = if (pointsToFrame.size == 1) {
                    if (shouldFrameIncidentPoints) "Showing 1 incident location."
                    else "Map centered on field units; incident pins are pending."
                } else if (shouldFrameIncidentPoints) {
                    "Incident locations and eligible field units are in view. Stale units are last known, not live."
                } else {
                    "Showing eligible field-unit locations while waiting for incident locations. Stale unit markers are labeled as last known, not live."
                }
            }
        }
    }


    DisposableEffect(mapLibreMap, incidentClusterSource, responderClusterSource, staleResponderClusterSource) {
        val map = mapLibreMap
        if (map == null) return@DisposableEffect onDispose {}
        val listener = MapLibreMap.OnMapClickListener { coordinate ->
            val pixel = map.projection.toScreenLocation(coordinate)
            fun clusterTargets(source: GeoJsonSource, feature: Feature, incidents: Boolean): List<GpsMapMarkerTarget> {
                val leaves = source.getClusterLeaves(feature, 50L, 0L).features().orEmpty()
                return if (incidents) {
                    leaves.mapNotNull { leaf ->
                        val id = leaf.getStringProperty("incident_id")?.toLongOrNull() ?: return@mapNotNull null
                        val incident = latestVisibleIncidents.firstOrNull { it.id == id } ?: return@mapNotNull null
                        val lat = incident.latitude ?: return@mapNotNull null
                        val lng = incident.longitude ?: return@mapNotNull null
                        GpsMapMarkerTarget(GpsMapMarkerType.INCIDENT, LatLng(lat, lng),
                            "Incident #${incident.id} · ${incident.type.label}",
                            "Status: ${incident.status.label} · ${incident.locationAgeStatus().first}",
                            incidentId = incident.id)
                    }
                } else {
                    leaves.mapNotNull { leaf ->
                        val incidentId = leaf.getStringProperty("incident_id")?.toLongOrNull()
                            ?: return@mapNotNull null
                        val userId = leaf.getStringProperty("user_id")?.toLongOrNull()
                            ?: return@mapNotNull null
                        val unit = latestValidResponderLocations.firstOrNull {
                            it.incidentId == incidentId && it.userId == userId
                        } ?: return@mapNotNull null
                        val age = unit.mapAgeSeconds() ?: return@mapNotNull null
                        val freshness = unit.mapFreshness() ?: return@mapNotNull null
                        GpsMapMarkerTarget(GpsMapMarkerType.FIELD_UNIT, LatLng(unit.latitude, unit.longitude),
                            "Responder #${unit.userId} · Incident #${unit.incidentId}",
                            if (freshness == MapLocationFreshness.STALE) {
                                "Last known location · ${formatLocationAge(age)} ago · not live"
                            } else "${freshness.label} · last reported ${formatLocationAge(age)} ago",
                            incidentId = unit.incidentId, userId = unit.userId,
                            stale = freshness == MapLocationFreshness.STALE)
                    }
                }
            }
            fun showClusterChoices(choices: List<GpsMapMarkerTarget>, mixedSources: Boolean) {
                val unique = choices.distinctBy { "${it.type}:${it.incidentId}:${it.userId}" }
                if (unique.isEmpty()) {
                    locationStatus = "These clustered locations have changed. Refresh the map and try again."
                    return
                }
                selectedMarkerChoices = unique
                routeSelectionGeneration += 1
                selectedIncidentId = null
                selectedResponderKey = null
                locationStatus = if (mixedSources) {
                    "Incident and field-unit clusters overlap. Choose the exact location."
                } else "Showing up to ${unique.size} locations in this dense cluster."
                scope.launch { mapSheetState.bottomSheetState.expand() }
            }
            val clusterLayers = listOf(
                Triple(incidentClusterSource, INCIDENT_CLUSTER_LAYER_ID, true),
                Triple(responderClusterSource, RESPONDER_CLUSTER_LAYER_ID, false),
                Triple(staleResponderClusterSource, STALE_RESPONDER_CLUSTER_LAYER_ID, false),
            )
            val clusterHits = clusterLayers.mapNotNull { (source, layerId, incidents) ->
                val feature = map.queryRenderedFeatures(pixel, arrayOf(layerId)).firstOrNull()
                    ?: return@mapNotNull null
                if (!feature.hasProperty("point_count") || source == null) return@mapNotNull null
                Triple(source, feature, incidents)
            }
            if (clusterHits.size > 1) {
                val choices = try {
                    clusterHits.flatMap { (source, feature, incidents) -> clusterTargets(source, feature, incidents) }
                } catch (_: RuntimeException) { emptyList() }
                showClusterChoices(choices, true)
                return@OnMapClickListener true
            }
            if (clusterHits.size == 1) {
                val (source, feature, incidents) = clusterHits.first()
                try {
                    val expansionZoom = source.getClusterExpansionZoom(feature)
                    val point = feature.geometry() as? Point ?: return@OnMapClickListener true
                    val position = point.coordinates()
                    if (expansionZoom > map.cameraPosition.zoom && map.cameraPosition.zoom < 20.0) {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            LatLng(position.latitude(), position.longitude()),
                            expansionZoom.toDouble().coerceAtMost(20.0)), 420)
                    } else showClusterChoices(clusterTargets(source, feature, incidents), false)
                } catch (_: RuntimeException) {
                    locationStatus = "Map locations refreshed. Tap the updated cluster again."
                }
                return@OnMapClickListener true
            }
            // Query a 48dp screen-space target, not only the tiny painted circle.
            // When multiple points occupy that target, present a nearest-first chooser.
            val density = mapView.resources.displayMetrics.density
            val radius = 24f * density
            val hitArea = RectF(pixel.x - radius, pixel.y - radius, pixel.x + radius, pixel.y + radius)
            val incidentTargets = map.queryRenderedFeatures(hitArea, arrayOf(INCIDENT_POINT_LAYER_ID, INCIDENT_SELECTED_POINT_LAYER_ID))
                .mapNotNull { feature ->
                    val id = feature.getStringProperty("incident_id")?.toLongOrNull() ?: return@mapNotNull null
                    val incident = latestVisibleIncidents.firstOrNull { it.id == id } ?: return@mapNotNull null
                    val lat = incident.latitude ?: return@mapNotNull null
                    val lng = incident.longitude ?: return@mapNotNull null
                    GpsMapMarkerTarget(
                        type = GpsMapMarkerType.INCIDENT,
                        position = LatLng(lat, lng),
                        title = "Incident #${incident.id} · ${incident.type.label}",
                        summary = "Status: ${incident.status.label} · ${incident.locationAgeStatus().first}",
                        incidentId = incident.id,
                    )
                }
                .distinctBy { it.incidentId }
                .sortedBy { target ->
                    val screen = map.projection.toScreenLocation(target.position)
                    hypot((screen.x - pixel.x).toDouble(), (screen.y - pixel.y).toDouble())
                }
            val responderTargets = map.queryRenderedFeatures(
                hitArea, arrayOf(RESPONDER_POINT_LAYER_ID, RESPONDER_STALE_POINT_LAYER_ID))
                .mapNotNull { feature ->
                    val incidentId = feature.getStringProperty("incident_id")?.toLongOrNull()
                        ?: return@mapNotNull null
                    val userId = feature.getStringProperty("user_id")?.toLongOrNull()
                        ?: return@mapNotNull null
                    val unit = latestValidResponderLocations.firstOrNull {
                        it.incidentId == incidentId && it.userId == userId
                    } ?: return@mapNotNull null
                    val age = unit.mapAgeSeconds() ?: return@mapNotNull null
                    val freshness = unit.mapFreshness() ?: return@mapNotNull null
                    GpsMapMarkerTarget(
                        type = GpsMapMarkerType.FIELD_UNIT,
                        position = LatLng(unit.latitude, unit.longitude),
                        title = "Responder #${unit.userId} · Incident #${unit.incidentId}",
                        summary = if (freshness == MapLocationFreshness.STALE) {
                            "Last known location · ${formatLocationAge(age)} ago · not live"
                        } else "${freshness.label} · last reported ${formatLocationAge(age)} ago",
                        incidentId = unit.incidentId, userId = unit.userId,
                        stale = freshness == MapLocationFreshness.STALE,
                    )
                }
                .distinctBy { it.responderKey }
                .sortedBy { target ->
                    val screen = map.projection.toScreenLocation(target.position)
                    hypot((screen.x - pixel.x).toDouble(), (screen.y - pixel.y).toDouble())
                }
            val nearbyTargets = (incidentTargets + responderTargets)
                .distinctBy { "${it.type}:${it.incidentId}:${it.userId}" }
                .sortedBy { target ->
                    val screen = map.projection.toScreenLocation(target.position)
                    hypot((screen.x - pixel.x).toDouble(), (screen.y - pixel.y).toDouble())
                }
            if (nearbyTargets.isNotEmpty()) {
                if (nearbyTargets.size > 1) {
                    selectedMarkerChoices = nearbyTargets
                    routeSelectionGeneration += 1
                    selectedIncidentId = null
                    selectedResponderKey = null
                    locationStatus = "Multiple incident and field-unit locations are close together. Choose the correct one."
                    scope.launch { mapSheetState.bottomSheetState.expand() }
                } else latestSelectMarkerTarget(nearbyTargets.first())
                return@OnMapClickListener true
            }
            false
        }
        map.addOnMapClickListener(listener)
        onDispose { map.removeOnMapClickListener(listener) }
    }

    BottomSheetScaffold(
        modifier = Modifier.fillMaxSize().semantics { paneTitle = "GPS Map" },
        scaffoldState = mapSheetState,
        sheetPeekHeight = 120.dp,
        sheetMaxWidth = 680.dp,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetTonalElevation = 2.dp,
        sheetShadowElevation = 8.dp,
        sheetDragHandle = { BottomSheetDefaults.DragHandle() },
        sheetSwipeEnabled = true,
        sheetContent = {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val mapDetailsMaxHeight = (maxHeight * 0.55f).coerceAtMost(420.dp)
                val compactMapPane = maxWidth < 360.dp
                val sheetExpanded = mapSheetState.bottomSheetState.currentValue == SheetValue.Expanded
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                roleMapTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = collapsedMapSummary,
                                modifier = Modifier.semantics {
                                    if (locationStatus != null || showAppSettingsAction) liveRegion = LiveRegionMode.Polite
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        androidx.compose.material3.IconButton(
                            onClick = {
                                scope.launch {
                                    if (sheetExpanded) {
                                        mapSheetState.bottomSheetState.partialExpand()
                                    } else {
                                        mapSheetState.bottomSheetState.expand()
                                    }
                                }
                            },
                            modifier = Modifier.size(48.dp).semantics {
                                contentDescription = if (sheetExpanded) {
                                    "Collapse map details"
                                } else {
                                    "Expand map details"
                                }
                            },
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = if (sheetExpanded) {
                                    androidx.compose.material.icons.Icons.Default.ExpandLess
                                } else {
                                    androidx.compose.material.icons.Icons.Default.ExpandMore
                                },
                                contentDescription = null,
                            )
                        }
                        if (sheetExpanded) {
                            androidx.compose.material3.IconButton(
                                onClick = ::showAllVisiblePoints,
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = "Fit all visible map locations"
                                },
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.LocationOn,
                                    contentDescription = null,
                                )
                            }
                        }
                        if (selectedIncident != null) {
                            androidx.compose.material3.TextButton(
                                onClick = { selectedIncidentId = null },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Clear") }
                        }
                    }

                    if (selectedMarkerChoices.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Overlapping map locations",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                "These pins are too close to select reliably on the map. Choose one to open its details.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            selectedMarkerChoices.forEachIndexed { index, choice ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .clickable(
                                            role = androidx.compose.ui.semantics.Role.Button,
                                            onClickLabel = "Open ${choice.title}",
                                        ) { selectMarkerTarget(choice) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(
                                        alpha = if (index == 0) 0.82f else 0.48f,
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            choice.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                        Text(
                                            choice.summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                            }
                            androidx.compose.material3.TextButton(
                                onClick = { selectedMarkerChoices = emptyList(); locationStatus = null },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Dismiss location choices") }
                        }
                    }

                    if (role == Role.DISPATCHER && validResponderLocations.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Field-unit location freshness",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                MapFreshnessChip(
                                    MapLocationFreshness.FRESH,
                                    freshResponderCount,
                                    "updated under 30 seconds ago",
                                )
                                MapFreshnessChip(
                                    MapLocationFreshness.AGING,
                                    agingResponderCount,
                                    "updated 30 to 120 seconds ago",
                                )
                                MapFreshnessChip(
                                    MapLocationFreshness.STALE,
                                    staleResponderCount,
                                    "older than 120 seconds and not live",
                                )
                            }
                        }
                    }

                    if (isRouteLoading || routeResult != null || routeStatus != null || liveNavigationForSelection) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        when {
                                            trackingStatus.navigationCalculating && liveNavigationForSelection -> "Updating live route…"
                                            liveNavigationForSelection -> "Live navigation"
                                            isRouteLoading -> "Calculating in-app route…"
                                            else -> "In-app route preview"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    displayedRoute?.let { route ->
                                        val distance = String.format(java.util.Locale.US, "%.1f km", route.distanceMeters / 1_000.0)
                                        val minutes = (route.durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
                                        Text("$distance · estimated $minutes min", style = MaterialTheme.typography.bodyMedium)
                                        if (route.instructions.isNotEmpty()) {
                                            Text("Suggested maneuvers · not live turn-by-turn", style = MaterialTheme.typography.labelMedium)
                                            route.instructions.take(5).forEachIndexed { index, instruction ->
                                                Text("${index + 1}. ${instruction.text}", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    routeStatus?.let { message ->
                                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (liveNavigationForSelection) {
                                        trackingStatus.navigationMessage?.let { message ->
                                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        androidx.compose.material3.TextButton(
                                            onClick = { trackingController?.stop("Live navigation and location sharing stopped by you.") },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text("Stop navigation and location sharing") }
                                    }
                                    if (routeResult != null) {
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                routeJob?.cancel()
                                                routeRequestGeneration += 1
                                                isRouteLoading = false
                                                pendingRouteIncident = null
                                                routeResult = null
                                                routeIncidentId = null
                                                routeStatus = null
                                            },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text("Clear route preview") }
                                    }
                                }
                        }
                    }

                    if (selectedIncident != null) {
                        MapIncidentCard(
                            incident = selectedIncident,
                            context = context,
                            now = mapNow,
                            showNavigationActions = role != Role.REQUESTER,
                            onFocusOnMap = ::focusIncidentOnMap,
                            onNavigate = requestRoute,
                            onStartNavigation = if (
                                canStartLiveNavigation(selectedIncident) && routeProviderRecipient != null &&
                                !(trackingStatus.navigationActive && trackingStatus.incidentId == selectedIncident.id)
                            ) ({ incident -> beginLiveNavigation(incident) }) else null,
                            liveNavigationProviderRecipient = routeProviderRecipient,
                            liveNavigationUnavailableMessage = liveNavigationUnavailableMessage(selectedIncident),
                        )
                    }

                    if (showAppSettingsAction) {
                        androidx.compose.material3.Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            if (compactMapPane) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        androidx.compose.material3.Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.LocationOn,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                "Location access is off",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                "Incident pins remain available. Enable location to center this map on your device.",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    androidx.compose.material3.TextButton(
                                        onClick = ::openAppLocationSettings,
                                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                                    ) {
                                        Text("Open settings")
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    androidx.compose.material3.Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            "Location access is off",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            "Incident pins remain available. Enable location to center this map on your device.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    androidx.compose.material3.TextButton(
                                        onClick = ::openAppLocationSettings,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text("Open settings")
                                    }
                                }
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = mapDetailsMaxHeight),
                        contentPadding = PaddingValues(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "map-key", contentType = "map-info") {
                            Text(
                                if (role == Role.DISPATCHER) {
                                    "Map key · Incident pin: active emergency · Field-unit pin: responder location"
                                } else {
                                    "Map key · Incident pin: active emergency"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (role == Role.DISPATCHER) {
                            item(key = "unit-freshness-help", contentType = "map-info") {
                                Text(
                                    "Fresh under 30s · Aging 30–120s · Stale over 120s. Stale markers are last-known positions, not live.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (deviceFix != null) {
                            item(key = "local-preview-disclosure", contentType = "map-info") {
                                Text(
                                    "One-time preview · stays on this device, not uploaded.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (visibleIncidents.isNotEmpty()) {
                            item(key = "incidents-heading", contentType = "section-heading") {
                                Text(
                                    if (selectedIncident == null) "Incidents" else "Other incidents",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.semantics { heading() },
                                )
                            }
                            itemsIndexed(
                                visibleIncidents.filterNot { it.id == selectedIncidentId },
                                key = { _, incident -> "incident-${incident.id}" },
                                contentType = { _, _ -> "incident" },
                            ) { _, incident ->
                                MapIncidentCard(
                                    incident = incident,
                                    context = context,
                                    now = mapNow,
                                    showNavigationActions = role != Role.REQUESTER,
                                    onFocusOnMap = ::focusIncidentOnMap,
                                    onNavigate = requestRoute,
                                    onStartNavigation = if (
                                        canStartLiveNavigation(incident) && routeProviderRecipient != null &&
                                        !(trackingStatus.navigationActive && trackingStatus.incidentId == incident.id)
                                    ) ({ selected -> beginLiveNavigation(selected) }) else null,
                                    liveNavigationProviderRecipient = routeProviderRecipient,
                                    liveNavigationUnavailableMessage = liveNavigationUnavailableMessage(incident),
                                )
                            }
                        } else {
                            item(key = "no-incidents", contentType = "map-info") {
                                Text(
                                    emptyMapMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (role == Role.DISPATCHER && validResponderLocations.isNotEmpty()) {
                            item(key = "field-units-heading", contentType = "section-heading") {
                                Text(
                                    "Field-unit locations",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.semantics { heading() },
                                )
                            }
                            itemsIndexed(
                                validResponderLocations,
                                key = { _, location -> "unit-${location.incidentId}-${location.userId}" },
                                contentType = { _, _ -> "field-unit" },
                            ) { _, location ->
                                val ageSeconds = location.mapAgeSeconds(mapNow)
                                val freshness = location.mapFreshness(mapNow)
                                val responderKey = "${location.incidentId}:${location.userId}"
                                val isSelectedResponder = selectedResponderKey == responderKey
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable(
                                            role = androidx.compose.ui.semantics.Role.Button,
                                            onClickLabel = "Center map on responder ${location.userId}",
                                        ) {
                                            selectedResponderKey = responderKey
                                            if (mapLibreMap == null || !mapStyleLoaded) {
                                                locationStatus = "Map is still loading. Try centering this field unit again in a moment."
                                            } else {
                                                deviceFix = null
                                                animateToPoint(LatLng(location.latitude, location.longitude), 15.0)
                                                locationStatus = if (freshness == MapLocationFreshness.STALE) {
                                                    "Centered on responder #${location.userId}'s stale position. This is last known, not live; confirm with dispatch."
                                                } else {
                                                    "Centered on responder #${location.userId} · ${freshness?.label?.lowercase() ?: "freshness unknown"}."
                                                }
                                            }
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelectedResponder) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                                    },
                                ) {
                                    Text(
                                    text = buildString {
                                        append("Responder #${location.userId} · Incident #${location.incidentId} · ")
                                        append(freshness?.label ?: "Freshness unavailable")
                                        if (ageSeconds != null) append(" · last reported ${formatLocationAge(ageSeconds)}")
                                        location.accuracyM.validAccuracyLabel(" · accuracy about ")?.let { append(it) }
                                        if (freshness == MapLocationFreshness.STALE) append(" · last known, not live")
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                        .semantics {
                                            contentDescription = buildString {
                                                append(if (isSelectedResponder) "Selected. " else "")
                                                append("Center map on responder ${location.userId}, incident ${location.incidentId}. ")
                                                append(freshness?.label ?: "Freshness unavailable")
                                                if (ageSeconds != null) append(", last reported ${formatLocationAge(ageSeconds)}")
                                                if (freshness == MapLocationFreshness.STALE) append(". Last known position, not live")
                                            }
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelectedResponder) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                }
                            }
                        }
                    }

                    Text(
                        text = "© OpenMapTiles · © OpenStreetMap contributors",
                        modifier = Modifier
                            .align(Alignment.End)
                            .heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Open OpenFreeMap map data and attribution details") {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://openfreemap.org/"),
                                        )
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) {
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { it.invalidate() },
        )
        MapLoadStatusOverlay(isLoading = !mapDataLoaded && !mapLoadError)

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .onGloballyPositioned { coordinates ->
                    mapHeaderBottomPx = coordinates.size.height
                    mapLibreMap?.let { map ->
                        positionMapCompass(map, coordinates.size.height, mapDensity)
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp),

                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    androidx.compose.material3.FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = "Back from GPS map"
                        },
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Surface(
                        // Keep the title's contrast backing content-sized. A weighted,
                        // full-row surface reads like a search field even though it is not one.
                        modifier = Modifier.widthIn(max = 180.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            if (role == Role.REQUESTER) "Map" else "GPS Map",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (mapLoadError) {
                        androidx.compose.material3.FilledTonalButton(
                            onClick = ::retryMapLoad,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Retry map")
                        }
                    } else if (hasValidatedInternet == false) {
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            Text(
                                "Offline",
                                modifier = Modifier
                                    .semantics {
                                        contentDescription = "Internet unavailable. Map tiles may not load; incident details remain available."
                                    }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                    if (role != Role.REQUESTER && isLocationRequestInProgress) {
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.size(48.dp),
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier
                                    .size(18.dp)
                                    .semantics {
                                        contentDescription = "Getting your location"
                                    },
                                strokeWidth = 2.dp,
                            )
                        }
                    } else if (role != Role.REQUESTER && locationProvider.hasPrecisePermission()) {
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = ::locateOnce,
                            modifier = Modifier.size(48.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.MyLocation,
                                contentDescription = if (deviceFix == null) "Locate me" else "Refresh location",
                            )
                        }
                    } else if (role != Role.REQUESTER && !locationProvider.hasApproximatePermission()) {
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = onLocateClick,
                            modifier = Modifier.size(48.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.MyLocation,
                                contentDescription = if (deviceFix == null) "Locate me" else "Refresh location",
                            )
                        }
                    } else if (role != Role.REQUESTER) {
                        LocationButton(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "Use precise location for a one-time map preview"
                                },
                            backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                            textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            textType = LocationButtonTextType.None,
                            cornerRadius = 24.dp,
                            onPermissionResult = { granted ->
                                if (granted) {
                                    locateOnce()
                                } else if (locationProvider.hasApproximatePermission()) {
                                    locateOnce()
                                } else {
                                    deviceFix = null
                                    showAppSettingsAction = true
                                    locationStatus = "Location permission not granted. Incident pins remain available; choose a map location manually or allow Location in app settings."
                                }
                            },
                            onError = {
                                locationStatus = "The secure location button could not start. Incident pins remain available; try again."
                            },
                        )
                    }

            GpsMapFilterButtons(
                role = role,
                hasIncidents = validIncidentPoints.isNotEmpty(),
                hasActiveUnits = validResponderLocations.isNotEmpty(),
                onShowIncidents = ::showIncidentPins,
                onShowActiveUnits = ::showActiveUnits,
            )
        }

        if (mapSheetState.bottomSheetState.currentValue != SheetValue.Expanded) {
            GpsMapCameraControls(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, bottom = 136.dp),
                enabled = mapStyleLoaded,
                onShowAll = ::showAllVisiblePoints,
            )
        }

      }
    }
}

@Composable
private fun IncidentMapActions(
    incidentId: Long,
    needsConfirmation: Boolean,
    stacked: Boolean,
    showNavigation: Boolean = true,
    onFocus: () -> Unit,
    onNavigate: () -> Unit,
) {
    val focusPadding = PaddingValues(horizontal = if (stacked) 10.dp else 8.dp, vertical = 6.dp)
    val navigatePadding = focusPadding
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IncidentMapFocusButton(incidentId, onFocus, Modifier.fillMaxWidth(), focusPadding)
            if (showNavigation) {
                IncidentMapNavigateButton(incidentId, needsConfirmation, onNavigate, Modifier.fillMaxWidth(), navigatePadding)
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showNavigation) {
                IncidentMapFocusButton(incidentId, onFocus, Modifier.weight(1f), focusPadding)
                IncidentMapNavigateButton(incidentId, needsConfirmation, onNavigate, Modifier.weight(1f), navigatePadding)
            } else {
                IncidentMapFocusButton(incidentId, onFocus, Modifier.fillMaxWidth(), focusPadding)
            }
        }
    }
}

@Composable
private fun IncidentMapFocusButton(
    incidentId: Long,
    onClick: () -> Unit,
    modifier: Modifier,
    padding: PaddingValues,
) {
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "Show incident #$incidentId on map"
        },
        contentPadding = padding,
    ) {
        Text("Show on map")
    }
}

@Composable
private fun IncidentMapNavigateButton(
    incidentId: Long,
    needsConfirmation: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    padding: PaddingValues,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = if (needsConfirmation) {
                "Review location before previewing a route to incident #$incidentId"
            } else {
                "Preview an in-app route to incident #$incidentId"
            }
        },
        contentPadding = padding,
    ) {
        Text(if (needsConfirmation) "Review location" else "Route in TACResQ")
    }
}

@Composable
private fun MapIncidentCard(
    incident: Incident,
    context: Context,
    now: Instant,
    showNavigationActions: Boolean = true,
    onFocusOnMap: (Incident) -> Unit,
    onNavigate: (Incident) -> Unit,
    onStartNavigation: ((Incident) -> Unit)? = null,
    liveNavigationProviderRecipient: String? = null,
    liveNavigationUnavailableMessage: String? = null,
) {
    val lat = incident.latitude
    val lng = incident.longitude
    val hasUsableCoordinates = lat != null && lng != null &&
        lat.isFinite() && lng.isFinite() &&
        lat in -90.0..90.0 && lng in -180.0..180.0
    val (ageLabel, needsConfirmation) = incident.locationAgeStatus(now)
    var showNavigationConfirmation by remember(incident.id) { mutableStateOf(false) }
    var externalNavigationPending by remember(incident.id) { mutableStateOf(false) }
    var liveNavigationPending by remember(incident.id) { mutableStateOf(false) }
    var showLiveNavigationConsent by remember(incident.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Keep the incident title readable beside its status on compact cards.
            // Wider layouts retain the single-line information hierarchy.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 360.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Incident #${incident.id} · ${incident.type.label}",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusPill(incident.status.label, incident.status.pillColor())
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Incident #${incident.id} · ${incident.type.label}",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(incident.status.label, incident.status.pillColor())
                    }
                }
            }
            if (hasUsableCoordinates) {
                Text(
                    ageLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (needsConfirmation) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val locationSourceLabel = when (incident.locationSource) {
                    "gps" -> "GPS"
                    "manual" -> "Manual pin"
                    else -> "Source unavailable"
                }
                val accuracyLabel = if (incident.locationSource == "gps") {
                    incident.accuracyM.validAccuracyLabel(" · accuracy about ").orEmpty()
                } else ""
                Text(
                    locationSourceLabel + accuracyLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                incident.locationNotes?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        "Landmark: $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val navigateToIncident: (Boolean) -> Unit = { useExternalMaps ->
                    if (needsConfirmation) {
                        externalNavigationPending = useExternalMaps
                        liveNavigationPending = false
                        showNavigationConfirmation = true
                    } else if (useExternalMaps) {
                        openExternalMaps(context, lat!!, lng!!)
                    } else {
                        onNavigate(incident)
                    }
                }
                // Keep the two actions identical across compact and regular widths.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    IncidentMapActions(
                        incidentId = incident.id,
                        needsConfirmation = needsConfirmation,
                        stacked = maxWidth < 320.dp,
                        showNavigation = showNavigationActions,
                        onFocus = { onFocusOnMap(incident) },
                        onNavigate = { navigateToIncident(false) },
                    )
                }
                if (showNavigationActions) {
                    androidx.compose.material3.TextButton(
                        onClick = { navigateToIncident(true) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Open external Maps instead")
                    }
                    if (onStartNavigation != null) {
                        Button(
                            onClick = {
                                if (needsConfirmation) {
                                    liveNavigationPending = true
                                    externalNavigationPending = false
                                    showNavigationConfirmation = true
                                } else {
                                    showLiveNavigationConsent = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { Text("Start live navigation & share location") }
                    } else {
                        liveNavigationUnavailableMessage?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                // A landmark-only request is still actionable. Reuse the shared
                // provenance formatter so missing, partial, and invalid pins are distinct.
                val hasLandmarkFallback = lat == null && lng == null &&
                    (incident.locationSource == null || incident.locationSource == "none") &&
                    !incident.locationNotes.isNullOrBlank()
                Text(
                    incidentLocationText(incident),
                    color = if (hasLandmarkFallback) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (showNavigationConfirmation && hasUsableCoordinates) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showNavigationConfirmation = false
                externalNavigationPending = false
                liveNavigationPending = false
            },
            title = { Text("Confirm incident location") },
            text = {
                Text(
                    "This incident location may be outdated or its timestamp cannot be verified. " +
                        ageLabel + " Confirm the location with the requester or dispatcher before proceeding."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showNavigationConfirmation = false
                        when {
                            externalNavigationPending -> openExternalMaps(context, lat!!, lng!!)
                            liveNavigationPending -> {
                                liveNavigationPending = false
                                showLiveNavigationConsent = true
                            }
                            else -> onNavigate(incident)
                        }
                    },
                ) {
                    Text(
                        when {
                            externalNavigationPending -> "Open external Maps anyway"
                            liveNavigationPending -> "Review and start live route"
                            else -> "Preview route anyway"
                        },
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showNavigationConfirmation = false
                        externalNavigationPending = false
                        liveNavigationPending = false
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
    if (showLiveNavigationConsent && onStartNavigation != null && hasUsableCoordinates) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLiveNavigationConsent = false },
            title = { Text("Start live navigation and location sharing?") },
            text = {
                Text(
                    "TACResQ will start a visible location-sharing service. Fresh approximate locations will be shared with authorized dispatch and used to monitor this route. The active route recipient was verified from TACResQ's server before this prompt: $liveNavigationProviderRecipient. Route requests go to TACResQ's server, which forwards the current route origin and this incident destination to that named recipient for the initial route and qualifying reroutes. Recalculation requires sustained off-route fixes and is limited to at most one request every 30 seconds. This is route guidance, not live traffic, hazard, flooding, road-closure, or emergency-vehicle access verification. A persistent notification lets you stop navigation and location sharing at any time."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showLiveNavigationConsent = false
                        onStartNavigation(incident)
                    },
                ) { Text("Start navigation") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showLiveNavigationConsent = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun openExternalMaps(context: Context, latitude: Double, longitude: Double) {
    val navigation = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$latitude,$longitude&mode=d"))
        .setPackage("com.google.android.apps.maps")
    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude"))

    // Some devices block an explicit package launch or have a broken maps handler.
    // Try the generic geo intent after either a missing handler or a security denial.
    val opened = try {
        context.startActivity(navigation)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
    if (opened) return

    val fallbackOpened = try {
        context.startActivity(fallback)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
    if (!fallbackOpened) {
        Toast.makeText(context, "Unable to open a maps app on this phone.", Toast.LENGTH_SHORT).show()
    }
}
