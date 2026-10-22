#!/usr/bin/env python3
"""Offline guardrails for the responder-location SQL contract.

These source checks are not a substitute for executing the migrations against
Postgres with multiple authenticated roles.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "supabase/migrations/20261020062532_responder_location_tracking.sql").read_text()
dashboard = (ROOT / "backend/web/src/pages/Home.tsx").read_text()
location_provider = (ROOT / "app/src/main/java/com/tacresq/app/location/LocationProvider.kt").read_text()
responder_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()
map_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
write_rpc = migration.split("create or replace function public.api_record_location_v2(", 1)[1].split("\n$$;", 1)[0]
read_rpc = migration.split("create or replace function public.api_active_responder_locations()", 1)[1].split("\n$$;", 1)[0]
read_policy = migration.split("create policy location_logs_read_dispatcher_or_owner", 1)[1].split(";", 1)[0]

checks = {
    "write RPC is privileged only after explicit validation": "security definer" in write_rpc and "auth.uid()" in write_rpc and "incident_status <> 'en_route'" in write_rpc,
    "write RPC validates coordinate and timestamp ranges": "p_latitude < -90" in write_rpc and "p_longitude > 180" in write_rpc and "interval '5 minutes'" in write_rpc and "interval '15 minutes'" in write_rpc,
    "server serializes and throttles writes; direct inserts are revoked": "pg_advisory_xact_lock(actor_id)" in write_rpc and "interval '8 seconds'" in write_rpc and "revoke insert on public.location_logs from authenticated" in migration,
    "dispatcher read RPC rejects non-dispatchers": "tacresq_actor_role()) is distinct from 'dispatcher'" in read_rpc,
    "dispatcher RPC returns only active assigned responder rows": "i.status = 'en_route'" in read_rpc and "l.user_id = i.assigned_to" in read_rpc,
    "dispatcher RPC filters malformed coordinates and future timestamps": "l.latitude between -90 and 90" in read_rpc and "l.longitude between -180 and 180" in read_rpc and "l.recorded_at <= now()" in read_rpc,
    "RLS removes completed and unbound rows from direct reads": "i.status = 'en_route'" in read_policy and "incident_id is null" not in read_policy,
    "dashboard exposes freshness and missing/configuration states": all(text in dashboard for text in ('ageMs < 30_000', 'ageMs <= 120_000', 'unavailable', 'no live marker is shown')),
    "Android requests approximate updates with movement and cadence limits": all(text in location_provider for text in ('responderForegroundUpdates()', 'GRANULARITY_COARSE', 'RESPONDER_INTERVAL_MS = 12_000L', 'RESPONDER_MIN_DISTANCE_M = 20f')),
    "Android unregisters callback on flow cancellation": "awaitClose()" in location_provider and "finally {\n            client.removeLocationUpdates(callback)" in location_provider,
    "responder consent permits start without sharing and requests permission in context": "Share location & start" in responder_screen and "Start without sharing" in responder_screen and "ACCESS_COARSE_LOCATION" in responder_screen,
    "Android stops sharing on foreground exit and reports send metadata": "Lifecycle.Event.ON_STOP" in responder_screen and "vm.stopTracking(" in responder_screen and "Last sent $time" in responder_screen and "repo.recordResponderLocation" in responder_screen,
    "resuming tracking clears prior session send metadata": "lastLocationSentAt = null" in responder_screen and "lastLocationAccuracyM = null" in responder_screen and "fun resumeTracking(incidentId: Long)" in responder_screen,
    "responder navigation blocks missing, non-finite, and out-of-range coordinates": all(text in responder_screen for text in ("latitude.isFinite() && longitude.isFinite()", "latitude in -90.0..90.0 && longitude in -180.0..180.0", "No valid GPS pin is available.", "Incident coordinates are invalid. Confirm the destination with dispatch before navigating.")),
    "Android dispatcher map distinguishes fresh, aging, and stale locations": all(text in map_screen for text in ("MapLocationFreshness.FRESH", "MapLocationFreshness.AGING", "MapLocationFreshness.STALE", "age <= 120L", "else -> MapLocationFreshness.STALE")),
    "Android map rejects future timestamps and warns stale pins are not live": all(text in map_screen for text in ("return age.takeIf { it >= 0L }", "STALE — last known position", "Do not treat this marker as live", "Stale markers are last-known positions, not live.")),
    "Android map recalculates location freshness while open": all(text in map_screen for text in ("remember(freshnessTick) { Instant.now() }", "delay(15_000)", "freshnessTick += 1")),
    "Android incident pins show recorded location age and warn on missing, invalid, future, or old timestamps": all(text in map_screen for text in ("private fun Incident.locationAgeStatus", "Location time unavailable — confirm before navigating.", "Location timestamp invalid — confirm before navigating.", "Location timestamp is in the future — confirm before navigating.", "ageSeconds > 600L", "confirm before navigating.", "Location recorded ${formatLocationAge(ageSeconds)}", "val locationAgeLabel = incident.locationAgeStatus(mapNow).first", "color = if (locationNeedsConfirmation) MaterialTheme.colorScheme.error")),
    "sign-out stops location sharing before clearing the session": 'Location sharing stopped because you signed out.' in responder_screen and "onLogout()" in responder_screen,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} responder-location contract check(s) failed")
print(f"Responder-location source contract OK: {len(checks)} checks.")
