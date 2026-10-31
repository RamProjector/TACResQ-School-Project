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
    "Android stops sharing on foreground exit and reports send metadata": "Lifecycle.Event.ON_STOP" in responder_screen and "vm.stopTracking(" in responder_screen and "Last sent $time" in responder_screen and "repo.recordResponderLocation" in responder_screen,
    "resuming tracking clears prior session send metadata": "lastLocationSentAt = null" in responder_screen and "lastLocationAccuracyM = null" in responder_screen and "fun resumeTracking(incidentId: Long)" in responder_screen,
    "responder navigation blocks missing, non-finite, and out-of-range coordinates": all(text in responder_screen for text in ("latitude.isFinite() && longitude.isFinite()", "latitude in -90.0..90.0 && longitude in -180.0..180.0", "No valid map pin. Use the landmark and confirm the destination with dispatch.", "Incident coordinates are invalid. Confirm the destination with dispatch before navigating.")),
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
    "Android incident pins show recorded location age and warn on missing, invalid, future, or old timestamps": all(text in map_screen for text in ("private fun Incident.locationAgeStatus", "Location time unavailable — confirm before navigating.", "Location timestamp invalid — confirm before navigating.", "Location timestamp is in the future — confirm before navigating.", "ageSeconds > 600L", "confirm before navigating.", "Location recorded ${formatLocationAge(ageSeconds)}", "val locationAgeLabel = incident.locationAgeStatus(mapNow).first", "val (ageLabel, needsConfirmation) = incident.locationAgeStatus(now)", "color = if (needsConfirmation) MaterialTheme.colorScheme.error")),
    "expanded incident cards share the ticking map clock so stale-location warnings keep updating": all(text in map_screen for text in ("MapIncidentCard(", "context = context,", "now = mapNow,", "private fun MapIncidentCard(", "now: Instant,", "incident.locationAgeStatus(now)")),
    "responder sign-out stops location sharing before clearing the session": 'Location sharing stopped because you signed out.' in app_root and "vm.stopTracking(" in app_root and "responderLogout" in app_root,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} responder-location contract check(s) failed")
print(f"Responder-location source contract OK: {len(checks)} checks.")
