# TACResQ in-app routing implementation notes

Status: foundation only; no live routing provider is enabled by this change.

## Decision

Use MapLibre Native as the Android vector renderer with the OpenFreeMap Liberty style. The map renderer and routing engine remain separate provider choices. The map displays OpenStreetMap-derived vector data through OpenFreeMap; route calculation is still disabled until a production routing service is selected and validated. Keep the provider-neutral routing contract so the UI does not bind to one routing vendor.

Valhalla's official route API supports driving (`auto`) and pedestrian (`pedestrian`) costing, route summaries, geometry and maneuvers. References:
- https://valhalla.github.io/valhalla/api/route/api-reference/
- https://valhalla.github.io/valhalla/api/turn-by-turn/overview/
- https://project-osrm.org/docs/v5.24.0/api/

## Why this change does not call a public demo server

TACResQ is an emergency-response application. A public demo instance is not a production availability guarantee, and a route result does not account for live traffic, flooding, road closures, temporary access restrictions, or whether an emergency vehicle can pass. The app must not silently present a straight-line estimate as a road-route ETA.

OpenFreeMap's public style/tile service is free to use without an API key, but it is best-effort and has no SLA or personalized support guarantee. It requires attribution and limits offline downloads to 3,000 tiles or 50 MB per download, with at most four parallel requests and a unique User-Agent. Do not treat it as an emergency-service availability guarantee. See https://openfreemap.org/quick_start/ and https://openfreemap.org/.

## Foundation added

`data/routing/RoutingModels.kt` defines:
- Validated latitude/longitude values.
- Provider-neutral route result, distance, duration, geometry, and instructions.
- A provider interface for driving and walking.
- Valhalla costing names and response DTOs for a future adapter.
- A freshness/validity gate that rejects malformed, future-dated, stale, or non-positive route results.

Unit tests cover invalid coordinates, malformed route metrics, stale/future results, and explicit costing-mode mapping.

## Implementation status

The first adapter increment now exists in `data/routing/ValhallaRoutingProvider.kt`. It accepts an injected Retrofit service, sends only origin/destination coordinates plus explicit costing/units, converts Valhalla polyline6 legs and maneuvers into provider-neutral models, and rejects invalid coordinates, unsuccessful HTTP responses, missing route data, malformed geometry, and unusable metrics. Unit tests cover successful conversion, explicit driving costing, invalid-coordinate short-circuiting, and HTTP failure handling.

This is **not yet live app routing**: no Retrofit base URL is configured, no production routing service is selected, and the map screen does not yet request or draw routes. The adapter intentionally has no default host. The caller must configure connection/read/call timeouts and response-size limits; no provider credentials belong in the APK.

The adapter now has a bounded client factory in `data/routing/ValhallaRoutingClientFactory.kt`. It stays disabled when no endpoint is configured, requires an explicit HTTPS base URL without embedded credentials/query/fragment, and applies 5-second connect, 10-second read/write, and 15-second overall call timeouts. Unit tests cover disabled configuration and rejected unsafe URLs. This still does not choose a provider host or enable routing in the GPS screen. The client now enforces a 2 MiB maximum routing response size, including chunked responses without a trustworthy `Content-Length`. Oversized bodies fail as network errors before Retrofit parses them. Server-side authorization/rate limiting remain required before a production integration.

## Required next integration steps

1. Host the routing service behind an authenticated, rate-limited Render API endpoint or choose a provider with documented production terms. Keep API keys server-side.
2. Wire the adapter through an explicit application configuration and enforce bounded network timeouts and response-size limits.
3. Draw the route as a MapLibre line layer/GeoJSON source, clearly label ETA as an estimate, and show route freshness and route failure states.
4. Select mode by role: driving for driver; driving by default for field responder travelling by vehicle. Walking should be an explicit option only where it makes sense.
5. Retain external navigation as a user-invoked fallback. Never auto-launch another app or claim a route is safe/passable.
6. Validate location permission, foreground lifecycle, connectivity loss, cancellation, repeated route requests, stale GPS, malformed response, no route, and role/assignment authorization.
7. Benchmark a fixed Tacloban test set against OSRM before finalizing Valhalla. Record route availability, distance/ETA differences, and implausible detours. Confirm local road access and flood/closure limitations with dispatch staff.

## Safety acceptance criteria

- No fabricated route line or ETA when routing fails.
- Invalid, future-dated, or stale location cannot be silently treated as live.
- Destination coordinates must be validated before any request.
- Route requests must be cancellable and must not block the main thread.
- No sensitive incident details or requester identity sent to a third-party routing service; only the minimum required coordinates.
- UI remains usable if routing is unavailable and provides a clear manual fallback.
- No production service is switched until the adapter, CI, and Tacloban route tests pass.


## Map renderer migration (2026-10-31)

The Android GPS screen now uses MapLibre Native (OpenGL ES artifact for broad device compatibility) and OpenFreeMap's Liberty vector style:
- Style URL: `https://tiles.openfreemap.org/styles/liberty`
- No Google Maps SDK, Google Maps API key, or billing account is required.
- Incident, responder, stale last-known, and one-time device-location pins remain rendered from validated app state.
- Existing role-specific visibility, responder freshness rules, and one-time local-only location disclosure remain unchanged.
- Attribution is shown as OpenMapTiles and OpenStreetMap data attribution in the map UI; MapLibre's own attribution control remains enabled.
- Map tiles are third-party network data. Do not send requester names, phone numbers, incident narratives, or other personal data to the map provider; only map viewport/tile requests are needed.
- The public OpenFreeMap endpoint has no SLA. The map must not be treated as an authoritative source for road closures, flooding, emergency vehicle access, or live traffic.
- Offline map availability is not guaranteed. Incident details and status remain available separately from map rendering.

The previous osmdroid renderer was removed from the Android dependency graph. Road routing remains disabled; the Valhalla adapter is not configured to contact a production host. A separate routing change must add authenticated/rate-limited hosting, Tacloban route validation, and safe failure states before drawing routes or presenting ETAs.
