# TACResQ in-app routing foundation

Status: the authenticated Android-to-Laravel route-preview adapter supports Geoapify Routing API and private OSRM. `ROUTE_PROVIDER` defaults to `geoapify`, avoiding the need to host an OSRM graph/service on a free Render instance; set it to `osrm` only when a private router is actually provisioned. Android's route-preview feature remains disabled by default. Geoapify requires `GEOAPIFY_API_KEY` as a backend secret.

## Architecture and authorization

MapLibre Native renders the map; a responder/driver can request a static driving preview only for their own active assigned incident. The Android app obtains a fresh location fix and sends the incident ID, origin, and the user's Supabase bearer token to TACResQ's authenticated Render API.

Laravel validates the Supabase session, permits only responder/driver roles, confirms the caller's individual assignment and own assignment/incident status (`assigned` or `en_route`), and derives the destination from caller-authorized incident data. The client cannot supply a destination. The server sends only origin and destination coordinates to the selected provider. No bearer token, incident ID, incident narrative, requester identity, or profile data is forwarded. For OSRM, only a private single-label hostname is allowed. For Geoapify, the fixed EU endpoint receives coordinates in its `waypoints` query and the server-side key in its query. No automatic failover to a second provider occurs. Per-user rate limiting is 10 route requests per minute.

OSRM receives coordinates in a POST JSON body rather than its URL. The optional OSRM container sets `DISABLE_ACCESS_LOGGING=1` so OSRM does not write request lines to stdout. Geoapify requires coordinates and its API key in a GET URL; application code suppresses URL and exception logging, but Geoapify and its infrastructure can receive that request. Review its privacy policy and DPA before sending actual responder locations. More details and provider selection guidance are in [`ROUTING_PROVIDER_SELECTION.md`](ROUTING_PROVIDER_SELECTION.md).

## Engine and mode limits

The optional private OSRM wrapper pins `ghcr.io/project-osrm/osrm-backend:v26.10.0`, runs the MLD algorithm, and fails at startup if the mounted graph is missing. The separate graph must be prepared from a deliberately bounded OSM extract with the OSRM car profile and the matching OSRM release. Geoapify is an optional hosted driving provider configured server-side. Both providers calculate a static driving preview only; walking, turn-by-turn voice guidance, live rerouting, live traffic, closures, flood data, emergency-vehicle access restrictions, and dispatch-approved road-condition overlays are not provided.

The backend normalizes GeoJSON route geometry, distance, duration, and bounded maneuver text. Android validates route geometry and timestamps again, applies a two-minute freshness limit, draws a map line, and shows a static route-preview card. Routes are guidance only; they are not evidence of current road passability or a guaranteed ETA. The explicit external Maps fallback remains available.

## Enablement and infrastructure boundary

The route provider defaults to Geoapify. Add `GEOAPIFY_API_KEY` as a secret on the Render backend service; do not put it in Android, the web dashboard, or source control. Geoapify receives responder-origin and assigned-incident destination coordinates. For private OSRM instead, set `ROUTE_PROVIDER=osrm` and configure `OSRM_BASE_URL` to its private internal single-label hostname, such as `http://tacresq-osrm:5000` (use the actual host and port). Do not point OSRM at a public routing API. If Geoapify is selected but its key is missing, route preview fails closed with a configuration error.

Android `ROUTE_PREVIEW_ENABLED` defaults to false. A debug build requires both `-PuseSupabase=true` and `-PtacresqRoutePreviewEnabled=true`; a release build requires the explicit `-PtacresqRoutePreviewEnabled=true` flag. Enable routing only after the selected provider is configured, synthetic route checks pass, and the location-processing disclosure is approved. Enabling the client flag alone does not create a service or make routing available.

Self-hosting requires operating-area graph selection, pre-processing, persistent storage, sufficient memory/CPU, updates/rollback, monitoring, and access/retention review. A separate private service and disk may require paid hosting. Geoapify charges credits and processes coordinates externally; its free-tier allowance is shared across its APIs. Do not automatically fail over between providers because this silently discloses the same location to an additional recipient. See [`ROUTING_PROVIDER_SELECTION.md`](ROUTING_PROVIDER_SELECTION.md) for the recommended manual fallback and [`backend/routing/README.md`](../backend/routing/README.md) for the optional OSRM container workflow.

## Validation and remaining gaps

Automated Android unit tests and Laravel feature tests cover authenticated requests, prohibited roles, foreign/unassigned/inactive incidents, absent router configuration, rejection of public OSRM hosts, coordinates-only OSRM POST data, fixed-host Geoapify requests, Geoapify geometry/instruction normalization, missing/rejected keys, sanitized provider errors, no automatic OSRM retry, malformed/oversized geometry, and user rate limits. These tests use synthetic inputs and fake HTTP responses; they still require exact-head CI or a PHP/Laravel environment to execute.

Not validated in this work: Android Gradle build/unit-test execution, starting the OSRM image, preparing a real TACRU-area graph, sizing memory/disk, connecting a live private service, live Supabase/Render behavior, a route on local roads, device GPS/network behavior, or dispatcher operational acceptance. A PR CI pass is not evidence for those live properties. Never treat this prototype as an operational emergency-service navigation system.

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
