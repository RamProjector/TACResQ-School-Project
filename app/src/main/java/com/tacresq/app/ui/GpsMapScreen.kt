package com.tacresq.app.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.graphics.Color
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Explore
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
import com.tacresq.app.ui.common.incidentLocationText
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
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

private const val TACLOBAN_LATITUDE = 11.2443
private const val TACLOBAN_LONGITUDE = 125.0030
private const val OPENFREEMAP_LIBERTY_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val MAP_DATA_UNAVAILABLE_STATUS = "Map data is temporarily unavailable. Incident details remain available."

/**
 * Converts the app's vector pin drawables to MapLibre marker icons. Icons are
 * created once per map screen and reused for all annotations.
 */
private fun Context.mapLibrePinIcon(resourceId: Int, alpha: Int = 255): Icon {
    val drawable = requireNotNull(ContextCompat.getDrawable(this, resourceId)).mutate()
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
    val iconHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: 56
    // MapLibre's legacy Marker annotation anchors icons at their center. Keep the
    // pin tip at the geographic coordinate by adding transparent space below it.
    val bitmap = Bitmap.createBitmap(width, iconHeight * 2, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.alpha = alpha.coerceIn(0, 255)
    drawable.setBounds(0, 0, width, iconHeight)
    drawable.draw(canvas)
    return IconFactory.getInstance(this).fromBitmap(bitmap)
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
    ageSeconds < 60L -> "${ageSeconds}s ago"
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
                modifier = Modifier.heightIn(min = 44.dp).semantics {
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
                modifier = Modifier.heightIn(min = 44.dp).semantics {
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
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onShowAll: () -> Unit,
    onResetNorth: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.IconButton(
                onClick = onZoomIn,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "Zoom in on map"
                },
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Add,
                    contentDescription = null,
                )
            }
            androidx.compose.material3.IconButton(
                onClick = onZoomOut,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "Zoom out on map"
                },
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Remove,
                    contentDescription = null,
                )
            }
            androidx.compose.material3.IconButton(
                onClick = onShowAll,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "Show all available map locations"
                },
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.LocationOn,
                    contentDescription = null,
                )
            }
            androidx.compose.material3.IconButton(
                onClick = onResetNorth,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "Reset map orientation to north"
                },
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Explore,
                    contentDescription = null,
                )
            }
        }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val trackingStatusFlow = remember(trackingController) {
        trackingController?.status ?: MutableStateFlow(ResponderTrackingStatus())
    }
    val trackingStatus by trackingStatusFlow.collectAsStateWithLifecycle()
    // Keep the latest callback without restarting the polling coroutine on recomposition.
    val refreshResponderLocations by rememberUpdatedState(onRefreshResponderLocations)
    var deviceFix by remember { mutableStateOf<GeoFix?>(null) }
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
        Role.ADMIN -> "Operations map"
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
        Role.ADMIN -> "Read-only map overview."
    }
    val collapsedMapSummary = when {
        showAppSettingsAction -> "Location access is off · incident pins remain visible."
        else -> locationStatus ?: roleMapSummary
    }
    val emptyMapMessage = when (role) {
        Role.REQUESTER -> "No active incident locations are available right now. You can still explore the map."
        Role.RESPONDER, Role.DRIVER -> "No active response locations are available right now."
        Role.DISPATCHER -> "No incident or field-unit locations are available right now."
        Role.ADMIN -> "The admin map is read-only. Incident and responder locations are not exposed here."
    }

    // Clear stale action text when the incident set changes; the default is derived from current map data.
    androidx.compose.runtime.LaunchedEffect(visibleIncidents) {
        locationStatus = null
        if (selectedIncidentId != null && visibleIncidents.none { it.id == selectedIncidentId }) {
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

    var mapLibreMap by remember(context, role) { mutableStateOf<MapLibreMap?>(null) }
    var mapStyleLoaded by remember(context, role) { mutableStateOf(false) }
    val mapView = remember(context, role) {
        MapLibre.getInstance(context)
        MapView(context).apply {
            contentDescription = if (role == Role.DISPATCHER) {
                "Emergency response vector map centered on Tacloban City. Select an incident pin to open its details. Drag the bottom sheet to review incident locations and responder freshness."
            } else {
                "Emergency response vector map centered on Tacloban City. Select an incident pin to open its details. Drag the bottom sheet to review active incident locations."
            }
            onCreate(null)
        }
    }

    DisposableEffect(mapView) {
        mapView.addOnDidFailLoadingMapListener {
            locationStatus = MAP_DATA_UNAVAILABLE_STATUS
        }
        mapView.addOnDidFinishLoadingMapListener {
            if (locationStatus == MAP_DATA_UNAVAILABLE_STATUS) locationStatus = null
        }
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.setOnMarkerClickListener { marker ->
                val incidentId = marker.title.removePrefix("Incident #").substringBefore(" ·").toLongOrNull()
                if (incidentId != null) {
                    selectedIncidentId = incidentId
                    scope.launch { mapSheetState.bottomSheetState.expand() }
                    true
                } else {
                    false
                }
            }
            map.setStyle(OPENFREEMAP_LIBERTY_STYLE) {
                mapStyleLoaded = true
                map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                    .target(LatLng(TACLOBAN_LATITUDE, TACLOBAN_LONGITUDE))
                    .zoom(12.0)
                    .build()
            }
        }
        onDispose {
            mapView.onDestroy()
            mapLibreMap = null
            mapStyleLoaded = false
        }
    }

    val incidentPinIcon = remember(context) {
        context.mapLibrePinIcon(com.tacresq.app.R.drawable.ic_map_incident_pin)
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
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 64), 480)
    }

    fun showIncidentPins() {
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
        selectedIncidentId = incident.id
        scope.launch { mapSheetState.bottomSheetState.expand() }
        val latitude = incident.latitude
        val longitude = incident.longitude
        if (!incident.hasValidMapCoordinates() || latitude == null || longitude == null) {
            locationStatus = "Incident #${incident.id} has no usable map coordinates."
            return
        }
        deviceFix = null
        animateToPoint(LatLng(latitude, longitude), 15.0)
        locationStatus = "Centered on incident #${incident.id}. Check the recorded location time before navigating."
    }

    fun showActiveUnits() {
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

    fun zoomMapIn() {
        if (mapLibreMap == null || !mapStyleLoaded) {
            locationStatus = "Map is still loading. Try again in a moment."
            return
        }
        mapLibreMap?.animateCamera(CameraUpdateFactory.zoomIn(), 180)
        locationStatus = "Map zoomed in."
    }

    fun zoomMapOut() {
        if (mapLibreMap == null || !mapStyleLoaded) {
            locationStatus = "Map is still loading. Try again in a moment."
            return
        }
        mapLibreMap?.animateCamera(CameraUpdateFactory.zoomOut(), 180)
        locationStatus = "Map zoomed out."
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

    fun resetMapNorth() {
        if (mapLibreMap == null || !mapStyleLoaded) {
            locationStatus = "Map is still loading. Try again in a moment."
            return
        }
        mapLibreMap?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 260)
        locationStatus = "Map orientation reset to north."
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
                        when (val settings = locationProvider.checkLocationSettings()) {
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
                        val fix = when (val capture = locationProvider.currentFix()) {
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
                        val result = provider.route(
                            RouteCoordinate(fix.latitude, fix.longitude),
                            RouteCoordinate(destinationLatitude, destinationLongitude),
                            RouteMode.DRIVING,
                            incident.id,
                        )
                        val route = result.getOrNull()
                        if (route == null || !route.isUsable(System.currentTimeMillis())) {
                            routeStatus = result.exceptionOrNull()?.message
                                ?: "The routing service returned no current usable route."
                            return@launch
                        }
                        routeResult = route
                        routeIncidentId = incident.id
                        routeStatus = "Route preview only. No live traffic, flooding, road closures, or emergency-vehicle access is verified."
                        val routePoints = route.geometry.map { LatLng(it.latitude, it.longitude) }
                        mapView.post { fitMapPoints(routePoints) }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        routeStatus = "Could not calculate a route. Check connectivity and confirm the destination with dispatch."
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

    DisposableEffect(mapView, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        var mapIsStarted = false
        var mapIsResumed = false
        val observer = LifecycleEventObserver { _, event ->
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
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !mapIsStarted) {
            mapView.onStart()
            mapIsStarted = true
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !mapIsResumed) {
            mapView.onResume()
            mapIsResumed = true
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (mapIsResumed) mapView.onPause()
            if (mapIsStarted) mapView.onStop()
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

    androidx.compose.runtime.LaunchedEffect(
        mapLibreMap,
        mapStyleLoaded,
        visibleIncidents,
        validResponderLocations,
        deviceFix,
        routeResult,
        displayedRoute,
        trackingStatus.incidentId,
        trackingStatus.navigationActive,
        freshnessTick,
    ) {
        val map = mapLibreMap
        if (map == null || !mapStyleLoaded) return@LaunchedEffect

        // The map's annotations are a render of current, validated app state.
        map.clear()
        visibleIncidents.forEach { incident ->
            val latitude = incident.latitude
            val longitude = incident.longitude
            if (incident.hasValidMapCoordinates() && latitude != null && longitude != null) {
                val source = when (incident.locationSource) {
                    "gps" -> "GPS location"
                    "manual" -> "Manually selected map pin"
                    "none" -> "No location source"
                    else -> "Location source not reported"
                }
                val accuracy = if (incident.locationSource == "gps") {
                    incident.accuracyM.validAccuracyLabel("GPS accuracy about ")
                } else null
                val note = incident.locationNotes?.takeIf { it.isNotBlank() }
                val locationAgeLabel = incident.locationAgeStatus(mapNow).first
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(latitude, longitude))
                        .icon(incidentPinIcon)
                        .title("Incident #${incident.id} · ${incident.type.label}")
                        .snippet(
                            listOfNotNull(
                                source,
                                accuracy,
                                "Status: ${incident.status.label}",
                                locationAgeLabel,
                                note,
                            ).joinToString(" · "),
                        ),
                )
            }
        }
        validResponderLocations.forEach { location ->
            val ageSeconds = location.mapAgeSeconds(mapNow) ?: return@forEach
            val freshness = location.mapFreshness(mapNow) ?: return@forEach
            val icon = if (freshness == MapLocationFreshness.STALE) staleResponderPinIcon else responderPinIcon
            val accuracy = location.accuracyM.validAccuracyLabel(" · reported accuracy about ") ?: ""
            val age = formatLocationAge(ageSeconds)
            val snippet = if (freshness == MapLocationFreshness.STALE) {
                "STALE — last known position, $age$accuracy. Do not treat this marker as live; confirm with dispatch."
            } else {
                "${freshness.label} location · last reported $age$accuracy. Dispatcher-only, consented foreground location."
            }
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(location.latitude, location.longitude))
                    .icon(icon)
                    .title(
                        if (freshness == MapLocationFreshness.STALE) {
                            "STALE field-unit location · Incident #${location.incidentId}"
                        } else {
                            "${freshness.label} field-unit location · Incident #${location.incidentId}"
                        },
                    )
                    .snippet(snippet),
            )
        }
        deviceFix?.let { fix ->
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(fix.latitude, fix.longitude))
                    .icon(myLocationPinIcon)
                    .title("Your location · this device only")
                    .snippet("One-time location preview. Not uploaded or continuously tracked."),
            )
        }
        displayedRoute?.let { route ->
            map.addPolyline(
                PolylineOptions()
                    .addAll(route.geometry.map { LatLng(it.latitude, it.longitude) })
                    .color(Color.rgb(0, 119, 182))
                    .width(6f),
            )
        }

        val shouldFrameInitialUnits = !hasAutoFramedInitialMapPoints &&
            validIncidentPoints.isEmpty() && responderPointsForInitialFrame.isNotEmpty()
        val shouldFrameIncidentPoints = !hasAutoFramedIncidentPoints && validIncidentPoints.isNotEmpty()
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
                                maxLines = 1,
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
                        if (selectedIncident != null) {
                            androidx.compose.material3.TextButton(
                                onClick = { selectedIncidentId = null },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Clear") }
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
                                Text(
                                    text = buildString {
                                        append("Responder #${location.userId} · Incident #${location.incidentId} · ")
                                        append(freshness?.label ?: "Freshness unavailable")
                                        if (ageSeconds != null) append(" · last reported ${formatLocationAge(ageSeconds)}")
                                        location.accuracyM.validAccuracyLabel(" · accuracy about ")?.let { append(it) }
                                        if (freshness == MapLocationFreshness.STALE) append(" · last known, not live")
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    androidx.compose.material3.IconButton(
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
                    Text(
                        "GPS Map",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasValidatedInternet == false) {
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
                    if (isLocationRequestInProgress) {
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
                    } else if (locationProvider.hasPrecisePermission()) {
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = ::locateOnce,
                            modifier = Modifier.size(48.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.MyLocation,
                                contentDescription = if (deviceFix == null) "Locate me" else "Refresh location",
                            )
                        }
                    } else if (!locationProvider.hasApproximatePermission()) {
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = onLocateClick,
                            modifier = Modifier.size(48.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.MyLocation,
                                contentDescription = if (deviceFix == null) "Locate me" else "Refresh location",
                            )
                        }
                    } else {
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
                }
            }

            GpsMapFilterButtons(
                role = role,
                hasIncidents = validIncidentPoints.isNotEmpty(),
                hasActiveUnits = validResponderLocations.isNotEmpty(),
                onShowIncidents = ::showIncidentPins,
                onShowActiveUnits = ::showActiveUnits,
            )
        }

        GpsMapCameraControls(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 12.dp, bottom = 136.dp),
            onZoomIn = ::zoomMapIn,
            onZoomOut = ::zoomMapOut,
            onShowAll = ::showAllVisiblePoints,
            onResetNorth = ::resetMapNorth,
        )

        // Keep OSM attribution visible on the map even while the sheet is collapsed.
        Text(
            text = "© OpenMapTiles · © OpenStreetMap contributors",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 12.dp, bottom = 136.dp)
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
}

@Composable
private fun IncidentMapActions(
    incidentId: Long,
    needsConfirmation: Boolean,
    stacked: Boolean,
    onFocus: () -> Unit,
    onNavigate: () -> Unit,
) {
    val focusPadding = PaddingValues(horizontal = if (stacked) 10.dp else 8.dp, vertical = 6.dp)
    val navigatePadding = focusPadding
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IncidentMapFocusButton(incidentId, onFocus, Modifier.fillMaxWidth(), focusPadding)
            IncidentMapNavigateButton(incidentId, needsConfirmation, onNavigate, Modifier.fillMaxWidth(), navigatePadding)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IncidentMapFocusButton(incidentId, onFocus, Modifier.weight(1f), focusPadding)
            IncidentMapNavigateButton(incidentId, needsConfirmation, onNavigate, Modifier.weight(1f), navigatePadding)
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
                        onFocus = { onFocusOnMap(incident) },
                        onNavigate = { navigateToIncident(false) },
                    )
                }
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
