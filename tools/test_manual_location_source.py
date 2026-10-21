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
migration = (ROOT / "supabase/migrations/20261021150000_manual_incident_location_source.sql").read_text()
responder = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()
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
    "incident creation carries source through the Supabase RPC": all(
        text in repository for text in ('fix.manuallySelected -> "manual"', 'fix == null -> "none"')
    ) and all(text in api for text in ('api_create_incident_v3', 'body.locationSource', 'p_location_source')),
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
    ),
    "server response source is verified before claiming location confirmation": (
        "if (incident.locationSource != expectedSource) return false" in requester
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
