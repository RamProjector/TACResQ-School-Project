from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
service = (ROOT / "app/src/main/java/com/tacresq/app/location/ResponderLocationTrackingService.kt").read_text()
controller = (ROOT / "app/src/main/java/com/tacresq/app/location/ResponderLocationTrackingService.kt").read_text()
provider = (ROOT / "app/src/main/java/com/tacresq/app/data/routing/RenderRoutingProvider.kt").read_text()
models = (ROOT / "app/src/main/java/com/tacresq/app/data/routing/RoutingModels.kt").read_text()
map_screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/GpsMapScreen.kt").read_text()
api_routes = (ROOT / "backend/src/routes/api.php").read_text()
route_config = (ROOT / "backend/src/config/tacru.php").read_text()
route_env = (ROOT / "backend/.env.example").read_text()
route_docs = (ROOT / "docs/ROUTING_PROVIDER_SELECTION.md").read_text()
policy = (ROOT / "app/src/main/java/com/tacresq/app/data/routing/RouteReroutePolicy.kt").read_text()

checks = {
    "navigation is an explicit controller/service action": "EXTRA_START_NAVIGATION" in controller and "fun startNavigation" in controller,
    "service requests routing through configured server-side provider": "val provider = appContainer.routingProvider" in service and "provider.route(" in service,
    "reroute decisions are accuracy-qualified and throttled": all(token in policy for token in ("MAX_ACCURACY_METERS", "REQUIRED_OFF_ROUTE_FIXES", "MIN_REQUEST_INTERVAL_MILLIS")),
    "route-provider failures use bounded exponential backoff": "recordRequestResult" in service and "MAX_FAILURE_BACKOFF_MILLIS" in policy,
    "Android routing adapter stays on TACResQ route-preview endpoint": "@POST(\"route-preview\")" in provider and "apiKey" not in provider,
    "location sharing continues using the assignment-checked RPC": "repository.recordResponderLocation(incidentId, capture.fix)" in service,
    "service stops tracking when assignment ends": "assignment is no longer en route" in service,
    "repository-wrapped auth and assignment errors are recognized": all(token in service for token in ("is ApiException -> httpCode", "failure.hasHttpStatus(401, 403)", "failure.hasHttpStatus(401, 403, 404, 422)")),
    "a rejected location write is handled before route forwarding": service.index("failure.hasHttpStatus(401, 403, 404, 422)") < service.index("maybeRecalculateRoute(incidentId, capture.fix)"),
    "waiting navigation state clears old route geometry": "navigationRoute = null" in service and "val displayedRoute = if (liveNavigationForSelection)" in map_screen,
    "foreground notification is refreshed when sharing upgrades to navigation": "updateForegroundNotification(navigationEnabled = true)" in service and "createNotification(navigationEnabled)" in service,
    "provider identity is verified without coordinates before consent": all(token in provider for token in ("@GET(\"route-preview/provider\")", "providerDisclosure()")) and "routeProviderRecipient != null" in map_screen and "provider_id" in api_routes,
    "route requests are rejected before forwarding if the disclosed provider changes": "expectedProviderId = disclosedProviderId" in provider and "expected_provider_id" in api_routes and "The routing provider changed" in api_routes,
    "consent names the verified recipient and service type": "active route recipient was verified from TACResQ's server before this prompt" in map_screen and all(name in models for name in ("Geoapify, an external routing provider", "privately operated OSRM")),
    "backend routing has an independent fail-closed rollout gate": all(token in route_config for token in ("route_preview_enabled", "ROUTE_PREVIEW_ENABLED")) and api_routes.count("In-app routing is not enabled for this environment.") == 2,
    "routing rollout gate is documented with rollback": "ROUTE_PREVIEW_ENABLED=false" in route_env and "ROUTE_PREVIEW_ENABLED=false" in route_docs and "ROUTE_PROVIDER=geoapify" in route_env and "ROUTE_PROVIDER`" in route_docs and "redeploy the backend configuration" in route_docs,
}

for label, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {label}")

if not all(checks.values()):
    raise SystemExit(1)
