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
migration = (ROOT / "supabase/migrations/20261021155540_manual_incident_location_source.sql").read_text()
responder = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()
incident_card = (ROOT / "app/src/main/java/com/tacresq/app/ui/common/Components.kt").read_text()
laravel_routes = (ROOT / "backend/src/routes/api.php").read_text()
laravel_model = (ROOT / "backend/src/app/Models/Incident.php").read_text()
laravel_migration = (ROOT / "backend/src/database/migrations/2026_10_21_000003_add_incident_location_source.php").read_text()

checks = {
    "requester can open a MapLibre vector map and select its center coordinate": all(
        text in requester for text in (
            "ManualLocationPickerDialog(", "MapLibre.getInstance(context)", "MapView(context)",
            "REQUESTER_MAP_STYLE_URL", "map.setStyle(REQUESTER_MAP_STYLE_URL)", 'Text("Use this location")',
        )
    ),
    "manual pin confirmation submits the live map center only after finite-coordinate validation": all(
        text in requester for text in (
            "val target = mapLibreMap?.cameraPosition?.target",
            "val latitude = target?.latitude ?: centerCoordinates.value.first",
            "val longitude = target?.longitude ?: centerCoordinates.value.second",
            "if (latitude.isFinite() && longitude.isFinite()",
            "onConfirm(latitude, longitude)",
            "enabled = mapStyleLoaded && !mapLoadError",
        )
    ) and all(
        text in requester for text in (
            "latitude !in -90.0..90.0",
            "longitude !in -180.0..180.0",
            "The selected map pin is invalid.",
        )
    ),
    "requester manual location picker is a full-screen map with reachable map controls": all(
        text in requester for text in (
            "DialogProperties(usePlatformDefaultWidth = false)",
            "Choose location",
            "Selected location: %.5f, %.5f",
            "Return to starting location",
            "Map data",
            "REQUESTER_MAP_STYLE_URL",
            "modifier = Modifier.fillMaxSize()",
            "enabled = mapStyleLoaded && !mapLoadError",
        )
    ) and "height(320.dp)" not in requester and "controller.zoomIn()" not in requester and "controller.zoomOut()" not in requester,
    "fallback map center is clearly identified when GPS is unavailable": all(
        text in requester for text in (
            "startsAtTaclobanDefault = initial == null",
            "startsAtTaclobanDefault: Boolean",
            "Starts at Tacloban, not your GPS position. Move the map to the emergency site.",
            "Move the map before confirming.",
            "setOnTouchListener { _, event ->",
            "if (event.action == MotionEvent.ACTION_MOVE) userAdjustedMap = true",
            "enabled = mapStyleLoaded && !mapLoadError",
            "Move the map before confirming.",
        )
    ),
    "manual map pin is explicitly represented instead of mislabelled as GPS": all(
        text in requester for text in (
            'manuallySelected = true', '"Map pin selected"',
            'Coordinates chosen by you on the map · selected',
        )
    ) and "val manuallySelected: Boolean = false" in models,
    "manual pin map forwards the MapLibre lifecycle and blocks selection when map data fails": all(
        text in requester for text in (
            "LifecycleEventObserver", "Lifecycle.Event.ON_START -> {", "Lifecycle.Event.ON_RESUME -> {",
            "Lifecycle.Event.ON_PAUSE -> {", "Lifecycle.Event.ON_STOP -> {",
            "mapView.onStart()", "mapView.onResume()", "mapView.onPause()", "mapView.onStop()",
            "mapView.onDestroy()", "addOnDidFailLoadingMapListener", "mapLoadError",
        )
    ),
    "full-screen requester location picker exposes a named accessibility pane": (
        'paneTitle = "Choose emergency location"' in requester
    ),
    "manual pin shows live center coordinates and warns against blank-map selection": all(
        text in requester for text in (
            "addOnCameraIdleListener",
            "centerCoordinates.value = target.latitude to target.longitude",
            '"Selected location: %.5f, %.5f"',
            "Map pin only; it does not measure GPS accuracy.",
            "Map data is unavailable. Cancel and share a landmark instead.",
        )
    ),
    "incident creation carries source through the Supabase RPC": all(
        text in repository for text in ('fix.manuallySelected -> "manual"', 'fix == null -> "none"')
    ) and all(text in api for text in ('api_create_incident_v4', 'body.locationSource', 'body.clientRequestId', 'p_location_source')),
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
    "location provenance RPCs keep hardened pg_catalog search paths": (
        "set search_path = public, pg_temp" not in migration
        and migration.count("set search_path = pg_catalog") >= 6
    ),
    "migration uses the standard transaction handling convention": (
        "\nbegin;\n\nalter table" not in migration
        and "\ncommit;\n" not in migration
    ),
    "migration PL/pgSQL dollar quotes are balanced": (
        all(not line.rstrip().endswith("as $") for line in migration.splitlines())
        and migration.count("$$;") == migration.count("as $$") + migration.count("do $$")
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
        and "This one-time pin is sent; ongoing tracking is not." in requester
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
    "incident cards distinguish GPS, manual, and unverified location sources without dumping coordinates": all(
        text in incident_card for text in (
            '"gps" -> "GPS"', '"manual" -> "Map pin"',
            '"Unverified pin"', 'incident.locationSource == "gps"',
            '"No location pin$note"', 'val note = incident.locationNotes?.trim()',
        )
    ) and "String.format(Locale.US, \"%.5f, %.5f\", latitude, longitude)" not in incident_card,
    "incident landmark and access notes are never silently line-clamped": (
        "Text(\n                incidentLocationText(incident)," in incident_card
        and "maxLines" not in incident_card.split("incidentLocationText(incident)", 1)[1].split("\n            )", 1)[0]
        and "TextOverflow.Ellipsis" not in incident_card.split("incidentLocationText(incident)", 1)[1].split("\n            )", 1)[0]
    ),
    "field units are not described as live before a location is confirmed": all(
        text in responder for text in (
            "Waiting for the first confirmed location update.",
            "Last location update failed.",
            "Location sharing is off.",
            "Share location & start",
            "Start without sharing",
        )
    ),
    "external routing remains a separate action without redundant helper copy": (
        'Text("Navigate to incident")' in responder
        and 'google.navigation:q=$lat,$lng&mode=d' in responder
        and "Opens an installed maps app for route guidance." not in responder
    ),
    "SOS submission lock is set before launching asynchronous work": (
        "Claim the submission synchronously before launching the coroutine." in requester
        and requester.index("_state.update { it.copy(sending = true, notice = null, error = null) }")
        < requester.index("viewModelScope.launch {", requester.index("fun sendSos("))
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
