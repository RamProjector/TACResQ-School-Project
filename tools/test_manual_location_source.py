#!/usr/bin/env python3
"""Source-contract checks for manual SOS pins and clearer requester UI.

These checks do not replace an Android build, a live Supabase migration, or device QA.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
requester = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
models = (ROOT / "app/src/main/java/com/tacresq/app/data/model/Models.kt").read_text()
repository = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
fake_api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()
migration = (ROOT / "supabase/migrations/20261021150000_manual_incident_location_source.sql").read_text()
responder = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()
incident_card = (ROOT / "app/src/main/java/com/tacresq/app/ui/common/Components.kt").read_text()
laravel_routes = (ROOT / "backend/src/routes/api.php").read_text()
laravel_model = (ROOT / "backend/src/app/Models/Incident.php").read_text()
laravel_migration = (ROOT / "backend/src/database/migrations/2026_10_21_000003_add_incident_location_source.php").read_text()

checks = {
    "requester can open a real OSM map and select its center coordinate": all(
        text in requester for text in (
            "ManualLocationPickerDialog(", "MapView(context)", "TileSourceFactory.MAPNIK",
            "val point = mapView.mapCenter", 'Text("Use this location")',
        )
    ),
    "manual map pin is explicitly represented instead of mislabelled as GPS": all(
        text in requester for text in (
            'manuallySelected = true', '"Map pin selected"',
            'Coordinates chosen by you on the map · selected',
        )
    ) and "val manuallySelected: Boolean = false" in models,
    "manual pin map follows Android lifecycle and uses app-private tile cache": all(
        text in requester for text in (
            "LifecycleEventObserver", "Lifecycle.Event.ON_RESUME -> {",
            "Lifecycle.Event.ON_PAUSE -> {", "if (!mapIsResumed)", "if (mapIsResumed) mapView.onPause()",
            "mapView.onDetach()",
            "config.setOsmdroidBasePath(osmdroidBase)", "config.setOsmdroidTileCache(tileCache)",
            "config.userAgentValue =",
        )
    ),
    "manual pin shows live center coordinates and warns against blank-map selection": all(
        text in requester for text in (
            "import org.osmdroid.events.MapListener",
            "centerCoordinates.value = point.latitude to point.longitude",
            '"Selected center: %.5f, %.5f"',
            "Wait until roads and landmarks appear before confirming.",
            "If the map stays blank, cancel and use a landmark instead.",
        )
    ),
    "incident creation carries source through the Supabase RPC": all(
        text in repository for text in ('fix.manuallySelected -> "manual"', 'fix == null -> "none"')
    ) and all(text in api for text in ('api_create_incident_v3', 'body.locationSource', 'p_location_source')),
    "test backend echoes the same source metadata for end-to-end UI testing": all(
        text in fake_api for text in (
            "locationRecordedAt = body.locationRecordedAt",
            "locationSource = body.locationSource",
            "accuracyM = body.accuracyM",
        )
    ),
    "migration stores and validates source provenance": all(
        text in migration for text in (
            "add column if not exists location_source text",
            "p_location_source not in ('gps', 'manual', 'none')",
            "p_location_source = 'manual' and p_accuracy_m is not null",
            "NEW.location_source IS DISTINCT FROM OLD.location_source",
            "grant execute on function public.api_create_incident_v3",
        )
    ),
    "legacy GPS RPC default remains compatible while missing locations use none": (
        "alter column location_source set default 'gps'" in migration
        and "case when p_latitude is null then 'none' else 'gps' end" in migration
        and "p_location_source <> 'none'" in migration
    ),
    "incident history and status responses preserve location source": (
        migration.count("'location_source', i.location_source") >= 6
        and "create or replace function public.api_my_incidents()" in migration
        and "create or replace function public.api_all_incidents()" in migration
    ),
    "emergency types use an organized vertical radio selection": (
        "IncidentType.entries.forEach { type ->" in requester
        and "RadioButton(" in requester
        and "FlowRow(" not in requester
    ),
    "success message describes the actual selected location": (
        "SOS sent. Dispatch confirmed the map pin you selected." in requester
        and "SOS sent. Dispatch confirmed the one-time GPS pin you submitted." in requester
        and "TACResQ is not tracking you continuously" not in requester
        and "Only this pin is included with the SOS; ongoing location updates are not sent." in requester
    ),
    "expired manual pins are not mislabeled as GPS fixes": all(
        text in requester for text in (
            "The selected location pin is older than two minutes",
            "The location pin expired while you were reviewing.",
            'LocationNoticeCard("Location pin expired"',
            "choose the map pin again",
        )
    ),
    "server response source is verified before claiming location confirmation": (
        "if (incident.locationSource != expectedSource) return false" in requester
    ),
    "incident cards distinguish manual pins from GPS fixes": all(
        text in incident_card for text in (
            '"gps" -> "GPS pin"', '"manual" -> "Manual map pin"',
            '"Location pin (source unverified)"', 'incident.locationSource == "gps"',
            '"No location pin was shared"',
        )
    ),
    "field units are not described as live before a location is confirmed": all(
        text in responder for text in (
            "waiting for the first confirmed location update",
            "the latest update failed",
            "Responder location is not currently being shared.",
            "Share location & start",
            "Start without sharing",
        )
    ),
    "external routing is distinguished from the in-app incident map": all(
        text in responder for text in (
            'Text("Open navigation app")',
            "Opens an installed maps app for route guidance.",
            "Use TACResQ GPS Map for the incident and field-unit overview.",
        )
    ) and "Text(\"Navigate in Google Maps\")" not in responder,
    "Laravel adapter persists and returns location source without breaking older clients": all(
        text in laravel_routes for text in (
            "'location_source' => $incident->location_source",
            "'location_source' => ['nullable', Rule::in(['gps', 'manual', 'none'])]",
            "$data['location_source'] ??= $hasCoordinates ? 'gps' : 'none'",
            "$hasCoordinates && ($data['location_recorded_at'] ?? null) === null",
            "A location capture time is required with coordinates.",
            "A manually selected pin cannot claim GPS accuracy.",
        )
    ) and "'location_source'" in laravel_model and "location_source" in laravel_migration,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} manual-location/requester UI check(s) failed")
print(f"Manual-location/requester UI source contract OK: {len(checks)} checks.")
