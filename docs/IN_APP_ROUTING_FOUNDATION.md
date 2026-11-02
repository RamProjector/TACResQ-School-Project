# TACResQ in-app routing foundation

Status: the authenticated Android-to-Laravel route-preview adapter and optional OSRM service wrapper are implemented in source. The route feature is disabled by default. No OSRM graph, private service, Render environment setting, paid plan, persistent disk, or deployment has been created. Live routing remains unavailable until the service and graph are separately provisioned and the Android build explicitly opts in.

## Architecture and authorization

MapLibre Native renders the map; OSRM calculates routes in a separate, self-hosted service. A responder/driver can request a static driving preview only for their own active assigned incident. The Android app obtains a fresh location fix and sends the incident ID, origin, and the user's Supabase bearer token to TACResQ's authenticated Render API.

Laravel validates the Supabase session, permits only responder/driver roles, confirms the caller's individual assignment and own assignment/incident status (`assigned` or `en_route`), and derives the destination from caller-authorized incident data. The client cannot supply a destination. The server posts only origin and destination coordinates to a configured OSRM private host. The upstream JSON body contains no bearer token, incident ID, incident narrative, requester identity, or profile data. The host is restricted to an internal single-label hostname; dotted domains, public IPs, loopback literals, and IPv6 literals are rejected. Per-user rate limiting is 10 route requests per minute.

Coordinates are in OSRM's POST JSON body rather than the URL. The optional OSRM container sets `DISABLE_ACCESS_LOGGING=1` so OSRM does not write request lines to stdout. Application code does not log route request/response bodies. This is a mitigation, not a guarantee about host-level network, platform, crash, backup, or administrative retention; review those policies before routing actual responder locations.

## Engine and mode limits

The optional server wrapper pins `ghcr.io/project-osrm/osrm-backend:v26.10.0`, runs the MLD algorithm, and fails at startup if the mounted graph is missing. The separate graph must be prepared from a deliberately bounded OSM extract with the OSRM car profile and the matching OSRM release. Graph files are large generated artifacts and must not be committed to Git. The first implementation supports driving only; walking, turn-by-turn voice guidance, live rerouting, live traffic, closures, flood data, emergency-vehicle access restrictions, and dispatch-approved road-condition overlays are not provided.

The backend normalizes GeoJSON route geometry, distance, duration, and bounded maneuver text. Android validates route geometry and timestamps again, applies a two-minute freshness limit, draws a map line, and shows a static route-preview card. Routes are guidance only; they are not evidence of current road passability or a guaranteed ETA. The explicit external Maps fallback remains available.

## Enablement and infrastructure boundary

The Laravel service remains compatible when `OSRM_BASE_URL` is blank; the endpoint returns a generic unavailable response and sends no coordinates upstream. If a private OSRM service is later provisioned, set the Laravel environment value to its private internal single-label hostname, such as `http://tacresq-osrm:5000` (use the actual host and port). Do not point this variable at a public routing API.

Android `ROUTE_PREVIEW_ENABLED` defaults to false. A debug build requires both `-PuseSupabase=true` and `-PtacresqRoutePreviewEnabled=true`; a release build requires the explicit `-PtacresqRoutePreviewEnabled=true` flag. Only enable after the private endpoint is configured and synthetic route checks pass. Enabling the client flag alone does not create a service or make routing available.

Self-hosting requires operating-area graph selection, pre-processing, persistent storage, sufficient memory/CPU, updates/rollback, monitoring, and access/retention review. A separate private service and disk may require paid hosting. No such paid resources or live service configuration have been authorized or changed in this work. See [`backend/routing/README.md`](../backend/routing/README.md) for the optional container and graph workflow.

## Validation and remaining gaps

Automated Android unit tests and Laravel feature tests cover authenticated requests, prohibited roles, foreign/unassigned/inactive incidents, absent router configuration, rejection of public router hosts, coordinates-only OSRM POST data, response normalization, malformed/oversized geometry, provider errors, and user rate limits. These tests use synthetic inputs and fake HTTP responses.

Not validated: compiling Android locally (the sandbox has no Android SDK), starting the OSRM image, preparing a real TACRU-area graph, sizing memory/disk, connecting a live private service, live Supabase/Render behavior, a route on local roads, device GPS/network behavior, or dispatcher operational acceptance. A PR CI pass is not evidence for those live properties. Never treat this prototype as an operational emergency-service navigation system.

## Map renderer and tile source

The Android GPS screen uses MapLibre Native and OpenFreeMap's Liberty vector style:
- Style URL: `https://tiles.openfreemap.org/styles/liberty`.
- No Google Maps SDK, Google Maps API key, or billing account is required for map rendering.
- Incident, responder, stale last-known, and one-time device-location pins remain rendered from validated app state.
- Attribution for OpenStreetMap-derived map data remains visible through the map UI's attribution control.
- Do not send requester names, phone numbers, incident narratives, or other personal data to map tile providers; only map viewport/tile requests are needed.
- Public map tiles have no SLA. Offline availability is not guaranteed. Incident details and status remain separately available from map rendering.

## References

- [OSRM HTTP API v26.10.0](https://project-osrm.org/docs/v26.10.0/http) — route requests accept JSON POST bodies with coordinates and options.
- [OSRM self-hosting guide](https://github.com/Project-OSRM/osrm-backend) — official Docker image and recommended MLD preprocessing steps.
- [OSRM server options](https://github.com/Project-OSRM/osrm-backend/blob/master/docs/routed.md) — `DISABLE_ACCESS_LOGGING` disables request-line output.
- [OpenStreetMap copyright and attribution](https://www.openstreetmap.org/copyright).
