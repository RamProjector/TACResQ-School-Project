#!/usr/bin/env python3
"""Offline guardrails for the responder-location SQL contract.

These source checks are not a substitute for executing the migrations against
Postgres with multiple authenticated roles.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "supabase/migrations/20261020062532_responder_location_tracking.sql").read_text()
current_location_migration = (ROOT / "supabase/migrations/20261023103737_per_responder_assignment_status.sql").read_text().lower()
dashboard = (ROOT / "backend/web/src/pages/Home.tsx").read_text()
location_provider = (ROOT / "app/src/main/java/com/tacresq/app/location/LocationProvider.kt").read_text()
responder_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()
app_root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
map_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
gradle = (ROOT / "app/build.gradle.kts").read_text()
tracking_service = (ROOT / "app/src/main/java/com/tacresq/app/location/ResponderLocationTrackingService.kt").read_text()
app_container = (ROOT / "app/src/main/java/com/tacresq/app/AppContainer.kt").read_text()
render_routing = (ROOT / "app/src/main/java/com/tacresq/app/data/routing/RenderRoutingProvider.kt").read_text()
render_api = (ROOT / "backend/src/routes/api.php").read_text()
render_config = (ROOT / "backend/src/config/tacru.php").read_text()
osrm_docker = (ROOT / "backend/routing/osrm/Dockerfile").read_text()
osrm_start = (ROOT / "backend/routing/osrm/start-osrm.sh").read_text()
osrm_docs = (ROOT / "backend/routing/README.md").read_text()
write_rpc = current_location_migration.split("create or replace function public.api_record_location_v2(", 1)[1].split("\n$function$;", 1)[0]
read_rpc = current_location_migration.split("create or replace function public.api_active_responder_locations()", 1)[1].split("\n$function$;", 1)[0]
read_policy = current_location_migration.split("create policy location_logs_read_dispatcher_or_owner", 1)[1].split(";", 1)[0]

checks = {
    "write RPC is privileged only after explicit validation": "security definer" in write_rpc and "auth.uid()" in write_rpc and "incident_status <> 'en_route'" in write_rpc,
    "write RPC validates coordinate and timestamp ranges": "p_latitude < -90" in write_rpc and "p_longitude > 180" in write_rpc and "interval '5 minutes'" in write_rpc and "interval '15 minutes'" in write_rpc,
    "server serializes and throttles writes; direct inserts are revoked": "pg_advisory_xact_lock(actor_id)" in write_rpc and "interval '8 seconds'" in write_rpc and "revoke insert on public.location_logs from authenticated" in migration,
    "dispatcher read RPC rejects non-dispatchers": "tacresq_actor_role() is distinct from 'dispatcher'" in read_rpc,
    "dispatcher RPC returns active members of multi-unit assignments": "i.status = 'en_route'" in read_rpc and "l.user_id = a.user_id" in read_rpc and "a.responder_status = 'en_route'" in read_rpc,
    "current dispatcher RPC filters malformed coordinates and future timestamps": "l.latitude between -90 and 90" in read_rpc and "l.longitude between -180 and 180" in read_rpc and "l.recorded_at <= now() + interval '5 minutes'" in read_rpc,
    "current RLS policy limits location reads to the active assigned unit": "i.status = 'en_route'" in read_policy and "a.user_id = location_logs.user_id" in read_policy and "a.responder_status = 'en_route'" in read_policy,
    "dashboard exposes freshness and missing/configuration states": all(text in dashboard for text in ('ageMs < 30_000', 'ageMs <= 120_000', 'unavailable', 'no live marker is shown')),
    "API 37 precise one-time location uses the secure AndroidX Location Button": (
        "targetSdk = 37" in gradle
        and "androidx.core.locationbutton:locationbutton-compose:1.0.0-alpha01" in gradle
        and 'android:usesPermissionFlags="onlyForLocationButton"' in manifest
        and 'android:name="android.permission.USE_LOCATION_BUTTON"' in manifest
        and "LocationButton(" in map_screen
        and "LocationButtonTextType.None" in map_screen
        and "onPermissionResult = { granted ->" in map_screen
    ),
    "approximate location remains available without granting session precision": (
        "locationProvider.hasApproximatePermission()" in map_screen
        and "else if (locationProvider.hasApproximatePermission())" in map_screen
        and "locationProvider.hasPrecisePermission()" in map_screen
        and "Incident pins remain available" in map_screen
    ),
    "Android requests approximate updates with movement and cadence limits": all(text in location_provider for text in ('responderForegroundUpdates()', 'GRANULARITY_COARSE', 'RESPONDER_INTERVAL_MS = 12_000L', 'RESPONDER_MIN_DISTANCE_M = 20f')),
    "Android responder tracking validates the matching coarse request through Play Services rather than OEM LocationManager flags": (
        "when (checkResponderLocationSettings())" in location_provider
        and "Priority.PRIORITY_BALANCED_POWER_ACCURACY" in location_provider
        and "LocationSettingsCheck.NeedsResolution" in location_provider
        and "LocationSettingsCheck.Unavailable" in location_provider
        and "checkLocationSettings(settings).await()" in location_provider
        and "isLocationEnabled()" not in location_provider
        and "LocationManager" not in location_provider
    ),
    "Android unregisters callback on flow cancellation": "awaitClose()" in location_provider and "finally {\n            client.removeLocationUpdates(callback)" in location_provider,
    "responder consent permits start without sharing and requests permission in context": "Share location & start" in responder_screen and "Start without sharing" in responder_screen and "ACCESS_COARSE_LOCATION" in responder_screen,
    "responder sharing uses an explicit location foreground service with a user stop action": all(text in tracking_service for text in ("ServiceCompat.startForeground(", "FOREGROUND_SERVICE_TYPE_LOCATION", "ACTION_STOP", '"Stop sharing"', "serviceScope.launch { trackAssignment(incidentId) }")),
    "service revalidates the en-route assignment and session without relying on GPS callbacks": all(text in tracking_service for text in ("assignmentMonitorJob", "monitorAssignment(incidentId)", "repository.myIncidents()", "statusFor(user.id) == IncidentStatus.EN_ROUTE", "is HttpException -> code()", "is ApiException -> httpCode", "failure.hasHttpStatus(401, 403)", "active assignment could not be verified")),
    "foreground service stops on location or notification permission withdrawal": all(text in tracking_service for text in ("hasRequiredPermissions(this)", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS", "permission was withdrawn")),
    "manifest declares a non-exported location foreground service without background location scope": all(text in manifest for text in ("FOREGROUND_SERVICE_LOCATION", 'android:name=".location.ResponderLocationTrackingService"', 'android:foregroundServiceType="location"', 'android:exported="false"')) and "ACCESS_BACKGROUND_LOCATION" not in manifest,
    "service owns coarse location collection and sends only through the assignment-checked RPC": all(text in tracking_service for text in ("responderForegroundUpdates().collect", "repository.recordResponderLocation")) and "GRANULARITY_COARSE" in location_provider and "Lifecycle.Event.ON_STOP" not in responder_screen,
    "resuming tracking clears prior session send metadata": "lastLocationSentAt = null" in responder_screen and "lastLocationAccuracyM = null" in responder_screen and "fun resumeTracking(incidentId: Long)" in responder_screen,
    "responder primary route action opens the assigned incident in the in-app map": 'Text("Open in-app route preview")' in responder_screen and "onOpenMap = openMapIncident" in (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text() and "initialSelectedIncidentId = selectedIncidentId" in (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text(),
    "route request fails closed on role, assignment, assignment status, and invalid destination": all(text in map_screen for text in ("role != Role.RESPONDER && role != Role.DRIVER", "incident.assignedTo != user.id", "IncidentStatus.ASSIGNED", "IncidentStatus.EN_ROUTE", "incident.hasValidMapCoordinates()")),
    "route preview uses a fresh current origin, configured provider, freshness gate, and explicit fallback": all(text in map_screen for text in ("provider.route(", "locationProvider.currentFix()", "route.isUsable(System.currentTimeMillis())", "PolylineOptions()", "Locale.US", "not live turn-by-turn", "Open external Maps instead", "No live traffic, flooding, road closures")),
    "routing uses the authenticated Render proxy and stays disabled unless an Android build explicitly opts in": all(text in app_container for text in ("RenderRoutingClientFactory.create(AppConfig.BASE_URL)", "if (AppConfig.USE_FAKE_API || !AppConfig.ROUTE_PREVIEW_ENABLED) null")) and all(text in render_routing for text in ('@POST("route-preview")', '@Header("Authorization")', '"Bearer $token"', 'incidentId = incidentId', 'mode != RouteMode.DRIVING')) and all(text in gradle for text in ("tacresqRoutePreviewEnabled", '"ROUTE_PREVIEW_ENABLED"')) and "ORS_API_KEY" not in render_routing,
    "Render route proxy defaults to Geoapify and retains explicit private OSRM selection": all(text in render_api for text in ("/route-preview", "getUser($token)", "api_me", "api_my_incidents", "['assigned_user_ids']", "['assigned_user_statuses']", "route-preview-user:", "config('tacru.osrm_base_url'", "route/v1/driving", "->post($routerBaseUrl", "'coordinates' =>", "if ($provider === 'geoapify')", "api-eu.geoapify.com/v1/routing", "if ($provider !== 'osrm')")) and all(text in render_config for text in ("env('OSRM_BASE_URL'", "env('ROUTE_PROVIDER', 'geoapify')", "env('GEOAPIFY_API_KEY'")) and "api.openrouteservice.org" not in render_api,
    "Geoapify route previews disclose external coordinate processing and never silently retry another provider": all(text in responder_screen for text in ("approximate current location", "incident's location", "configured routing service", "If it fails, no second provider is contacted")) and "No automatic failover to a second provider occurs" in (ROOT / "docs/IN_APP_ROUTING_FOUNDATION.md").read_text(),
    "OSRM service is pinned, requires an external graph volume, and suppresses coordinate-bearing access logs": all(text in osrm_docker for text in ("ghcr.io/project-osrm/osrm-backend:v26.10.0", "DISABLE_ACCESS_LOGGING=1")) and all(text in osrm_start for text in ("${GRAPH_PREFIX}.osrm", "osrm-routed --algorithm mld")) and "Coordinates are sent in OSRM's JSON POST body, not in the URL" in osrm_docs,
    "Android dispatcher map distinguishes fresh, aging, and stale locations": all(text in map_screen for text in ("MapLocationFreshness.FRESH", "MapLocationFreshness.AGING", "MapLocationFreshness.STALE", "age <= 120L", "else -> MapLocationFreshness.STALE")),
    "Android map rejects future timestamps and warns stale pins are not live": all(text in map_screen for text in ("if (recorded.isAfter(now)) return null", "if (recordedAt.isAfter(now))", "STALE — last known position", "Do not treat this marker as live", "Stale markers are last-known positions, not live.")),
    "Android map refreshes incident-location ages for every role without polling responder locations for non-dispatchers": (
        all(text in map_screen for text in (
            "remember(freshnessTick) { Instant.now() }",
            "lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED)",
            "LaunchedEffect(lifecycleOwner)",
            "delay(15_000)",
            "freshnessTick += 1",
            "LaunchedEffect(lifecycleOwner, role, user.id)",
            "refreshResponderLocations()",
        ))
        and "freshnessTick += 1" not in map_screen.split("LaunchedEffect(lifecycleOwner, role, user.id)", 1)[1].split("val mapView = remember(context)", 1)[0]
    ),
    "Android incident pins show recorded location age and warn on missing, invalid, future, or old timestamps": all(text in map_screen for text in ("private fun Incident.locationAgeStatus", "Location time unavailable — confirm before navigating.", "Location timestamp invalid — confirm before navigating.", "Location timestamp is in the future — confirm before navigating.", "ageSeconds > 600L", "confirm before navigating.", "Location recorded ${formatLocationAge(ageSeconds)}", "val (ageLabel, needsConfirmation) = incident.locationAgeStatus(now)", "color = if (needsConfirmation) MaterialTheme.colorScheme.error")),
    "expanded incident cards share the ticking map clock so stale-location warnings keep updating": all(text in map_screen for text in ("MapIncidentCard(", "context = context,", "now = mapNow,", "private fun MapIncidentCard(", "now: Instant,", "incident.locationAgeStatus(now)")),
    "responder sign-out stops location sharing before clearing the session": 'Location sharing stopped because you signed out.' in app_root and "vm.stopTracking(" in app_root and "responderLogout" in app_root,
}
location_hardening_migration = (ROOT / "supabase/migrations/20261112215429_isolate_per_responder_location_writer.sql").read_text().lower()
location_reader_migration = (ROOT / "supabase/migrations/20261112215554_isolate_multi_responder_location_reader.sql").read_text().lower()
location_rate_index_migration = (ROOT / "supabase/migrations/20261112215513_location_logs_received_rate_index_concurrently.sql").read_text().lower()
checks.update({
    "private GPS writer locks incident and assignment rows and serializes responder writes": (
        "for update of i, a;" in location_hardening_migration
        and "pg_catalog.pg_advisory_xact_lock(actor_id)" in location_hardening_migration
    ),
    "private GPS writer has a server receipt-time rate-limit index": (
        "location_logs_received_rate_idx" in location_rate_index_migration
        and "coalesce(received_at, recorded_at)" in location_rate_index_migration
    ),
    "dispatcher reader rejects future and stale device timestamps": (
        "l.recorded_at <= pg_catalog.now()" in location_reader_migration
        and "l.recorded_at >= pg_catalog.now() - interval '2 minutes'" in location_reader_migration
        and "coalesce(l.received_at, l.recorded_at) desc" in location_reader_migration
    ),
})
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} responder-location contract check(s) failed")
print(f"Responder-location source contract OK: {len(checks)} checks.")
