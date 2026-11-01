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

The GPS map now has a bounded route-preview flow that requests one route for the responder/driver's own assigned incident, obtains a fresh current origin after the user asks, and draws returned geometry only after result validity/freshness checks. This is a static in-app route preview, not turn-by-turn guidance or automatic rerouting. The adapter intentionally has no default host; without an explicitly configured HTTPS endpoint the route feature remains disabled. No provider credentials belong in the APK.

The adapter has a bounded client factory in `data/routing/ValhallaRoutingClientFactory.kt`. It stays disabled when no endpoint is configured, requires an explicit HTTPS base URL without embedded credentials/query/fragment, and applies 5-second connect, 10-second read/write, and 15-second overall call timeouts. Unit tests cover disabled configuration and rejected unsafe URLs. The client enforces a 2 MiB maximum routing response size, including chunked responses without a trustworthy `Content-Length`. Oversized bodies fail as network errors before Retrofit parses them. Configure only a maintained, operator-approved endpoint; server-side authorization/rate limiting remain required before production use.

## Remaining integration and verification steps
1. Host the routing service behind an authenticated, rate-limited endpoint or use a provider with documented operational terms. Keep API keys server-side and leave the endpoint unset until configured.
2. Validate a fixed synthetic Tacloban route test set and confirm local road access/flood/closure limitations with dispatch staff before relying on results operationally.
3. Add continuous off-route detection/recalculation and maneuver-by-maneuver voice or visual guidance only as a separate, explicitly scoped feature. Do not call the current preview navigation.
4. Retain external navigation as a user-invoked fallback. Never auto-launch another app or claim a route is safe/passable.
5. Validate location permission, service/background lifecycle, connectivity loss, cancellation, repeated route requests, stale GPS, malformed response, no route, and role/assignment authorization on automated tests and physical devices.

## Safety acceptance criteria

- No fabricated route line or ETA when routing fails.
- Invalid, future-dated, or stale location cannot be silently treated as live.
- Destination coordinates must be validated before any request.
- Route requests must be cancellable and must not block the main thread.
- No sensitive incident details or requester identity sent to a third-party routing service; only the minimum required coordinates.
- UI remains usable if routing is unavailable and provides a clear manual fallback.
- No production service is switched until the adapter, CI, and Tacloban route tests pass.

## Responder location continuity

Responder location sharing is owned by a user-started Android location foreground service, not by the current-call screen. The responder must opt in while the app is visible; the service uses coarse updates, a persistent stop control, and the existing assignment-checked location RPC. It checks that the responder's own assignment remains en route, stops on sign-out or permission withdrawal, and does not claim a send succeeded until the backend RPC succeeds. This improves continuity when the responder returns to TACResQ after checking the route preview or another app; it is not a guarantee of uninterrupted GPS, network, or dispatcher visibility. Device/background restrictions and live Supabase behavior remain unverified.


## Map renderer migration (2026-10-31)

The Android GPS screen now uses MapLibre Native (OpenGL ES artifact for broad device compatibility) and OpenFreeMap's Liberty vector style:
- Style URL: `https://tiles.openfreemap.org/styles/liberty`
- No Google Maps SDK, Google Maps API key, or billing account is required.
- Incident, responder, stale last-known, and one-time device-location pins remain rendered from validated app state.
- The requester's full-screen manual SOS pin picker uses the same Liberty style; its center coordinate comes from the live map camera, and confirmation is disabled until the style loads successfully.
- Existing role-specific visibility, responder freshness rules, and one-time local-only location disclosure remain unchanged.
- Attribution is shown as OpenMapTiles and OpenStreetMap data attribution in the map UI; MapLibre's own attribution control remains enabled.
- Map tiles are third-party network data. Do not send requester names, phone numbers, incident narratives, or other personal data to the map provider; only map viewport/tile requests are needed.
- The public OpenFreeMap endpoint has no SLA. The map must not be treated as an authoritative source for road closures, flooding, emergency vehicle access, or live traffic.
- Offline map availability is not guaranteed. Incident details and status remain available separately from map rendering.

The previous osmdroid renderer was removed from the Android dependency graph. Route previews are wired into the GPS map but remain disabled unless an HTTPS Valhalla base URL is explicitly supplied at build time with `-PtacresqRoutingBaseUrl=https://your-approved-host/` or `TACRESQ_ROUTING_BASE_URL`. No production host is selected by default. Deployments must provide authenticated/rate-limited hosting and validate local routes; a preview/ETA is not a turn-by-turn or road-condition guarantee.
