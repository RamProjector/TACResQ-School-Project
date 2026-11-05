from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
service = (ROOT / "app/src/main/java/com/tacresq/app/location/ResponderLocationTrackingService.kt").read_text()
controller = (ROOT / "app/src/main/java/com/tacresq/app/location/ResponderLocationTrackingService.kt").read_text()
provider = (ROOT / "app/src/main/java/com/tacresq/app/data/routing/RenderRoutingProvider.kt").read_text()
policy = (ROOT / "app/src/main/java/com/tacresq/app/data/routing/RouteReroutePolicy.kt").read_text()

checks = {
    "navigation is an explicit controller/service action": "EXTRA_START_NAVIGATION" in controller and "fun startNavigation" in controller,
    "service requests routing through configured server-side provider": "val provider = appContainer.routingProvider" in service and "provider.route(" in service,
    "reroute decisions are accuracy-qualified and throttled": all(token in policy for token in ("MAX_ACCURACY_METERS", "REQUIRED_OFF_ROUTE_FIXES", "MIN_REQUEST_INTERVAL_MILLIS")),
    "route-provider failures use bounded exponential backoff": "recordRequestResult" in service and "MAX_FAILURE_BACKOFF_MILLIS" in policy,
    "Android routing adapter stays on TACResQ route-preview endpoint": "@POST(\"route-preview\")" in provider and "apiKey" not in provider,
    "location sharing continues using the assignment-checked RPC": "repository.recordResponderLocation(incidentId, capture.fix)" in service,
    "service stops tracking when assignment ends": "assignment is no longer en route" in service,
}

for label, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {label}")

if not all(checks.values()):
    raise SystemExit(1)
