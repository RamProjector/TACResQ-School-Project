# Optional private OSRM route service

This directory contains a reproducible local runner for the optional private OSRM alternative supported by TACResQ's authenticated route-preview proxy. The backend defaults to Geoapify for the free Render deployment and can use OSRM when `ROUTE_PROVIDER=osrm` and a private service is available. This directory does not deploy a router, apply a Laravel environment value, enable Android routing, or create a production graph. The Android app never calls OSRM or Geoapify directly. See [`docs/ROUTING_PROVIDER_SELECTION.md`](../../docs/ROUTING_PROVIDER_SELECTION.md) for location-data, pricing, and fallback guidance.

## Free and legal operating model

OSRM is open-source software and the graph can be generated from OpenStreetMap data. OpenStreetMap data is available under the Open Database License (ODbL), so keep the existing visible `OpenStreetMap` attribution and link to the [OSM copyright and license page](https://www.openstreetmap.org/copyright). Use a bounded extract obtained under its provider's terms, record its source/date, and do not commit the PBF or generated graph files.

The software and data may be free, but a reliable always-on private host, persistent disk, backups, and monitoring may have a cost. Do not use a public OSRM demo endpoint for responder coordinates or treat this prototype as an emergency-service availability guarantee.

## Local workflow

1. Obtain a bounded `.osm.pbf` extract for the approved TACRU operating area. Keep it outside Git.
2. Prepare the graph with the pinned OSRM image and the car profile:

   ```sh
   cd backend/routing
   ./prepare-graph.sh /path/to/approved-area.osm.pbf
   ```

   The generated `tacresq.osrm*` files are written to `backend/routing/graph/`, which is ignored by Git.
3. Start the router with a loopback-only host binding:

   ```sh
   cd backend/routing
   docker compose up --build
   ```

   The service is exposed only at `127.0.0.1:${OSRM_PORT:-5000}` for local smoke testing. The Compose service is named `tacresq-osrm` for private container-network use.
4. Run the synthetic in-area smoke test. It validates only that the graph returns a positive driving distance and duration; it does not log coordinates or prove road passability:

   ```sh
   ./smoke-test.sh
   ```

The smoke test defaults to two synthetic Tacloban-area points. Override `ROUTE_ORIGIN` and `ROUTE_DESTINATION` when a reviewer has approved a different in-area test pair. The values use OSRM's `longitude,latitude` order.

For complete local verification, including image build, graph mount, startup readiness, route response, coordinate-bearing log detection, and automatic teardown, run:

```sh
./verify-local.sh
```

This helper requires `graph/tacresq.osrm` and Docker. It runs only against the local Compose service and removes the containers and network when it exits. It does not configure Laravel or any production service.

## Connecting Laravel privately

The authenticated Laravel `/api/route-preview` handler verifies the caller's Supabase session and own active assignment, derives the destination from caller-authorized incident data, and sends only origin/destination coordinates to the selected provider. It does not send a bearer token, incident ID, description, profile data, or requester information. Coordinates are sent in OSRM's JSON POST body, not in the URL. Geoapify receives them in the fixed EU endpoint's query string, and its API key is also a query parameter; do not log that URL. TACResQ does not automatically retry another provider on failure, avoiding silent additional location disclosure.

For a private container network, configure Laravel with `OSRM_BASE_URL=http://tacresq-osrm:5000`. Do not point it at a public routing API. The backend intentionally rejects dotted domains, public IPs, loopback literals, and IPv6 literals so a production deployment cannot silently route precise responder locations to an arbitrary public host. The Laravel service and OSRM service must therefore share a private network or an approved internal service-discovery path.

## Authorized Geoapify Route Planner smoke test (not production routing)

The separate `POST /api/geoapify-smoke-test` endpoint is only a gated credential/connectivity diagnostic. It is disabled unless `GEOAPIFY_SMOKE_TEST_TOKEN` is set to a random value of at least 32 characters. It accepts no query string or request body and is rate limited. It sends one fixed JSON `POST` to `https://api.geoapify.com/v1/routeplanner` with one synthetic agent start and one synthetic job location in Tacloban; coordinates use GeoJSON `[longitude, latitude]` order. No end location is supplied, so the request contains two locations.

Geoapify's published Route Planner formula charges `locations × locations` for requests with fewer than 10 locations; this minimal two-location test therefore costs 4 credits per request. Confirm current account pricing before any additional calls. The response contains only a pass/fail summary and route/job counts; it does not return route geometry, provider response details, or credentials. Geoapify requires its API key in the outbound query string, so application code intentionally suppresses exception details and never logs that URL.

Set `GEOAPIFY_API_KEY` only as a Render backend environment secret. For an explicitly approved smoke test, set a separate strong temporary `GEOAPIFY_SMOKE_TEST_TOKEN`, POST to the endpoint with that token in `X-TACResQ-Smoke-Token`, and then clear the smoke-test token. Never put either value in source control, Android, a client-supplied URL/body, a PR, or logs. Geoapify requires the API key in the backend's outbound request query; this fixed-host call is the only place the key is sent. A missing key, invalid key, rate limit, malformed response, or network error produces a sanitized response. The diagnostic uses fixed synthetic points and does not change the operational `/api/route-preview` provider selection.

## Graph preparation and updates

`prepare-graph.sh` runs the pinned MLD sequence from the official OSRM image:

```text
osrm-extract -p /opt/car.lua
osrm-partition
osrm-customize
```

The runtime wrapper starts only when `/var/data/tacresq.osrm` exists. Keep the graph and source extract out of Git. Before replacing a graph, prepare it in a separate directory, run the smoke test against it, retain the previous graph for rollback, and record the source extract date and OSRM image tag. Re-run the route contract and backend tests after an image or graph update.

## Safety and deployment gates

The current implementation supports a static driving preview only. It does not provide pedestrian routing, turn-by-turn voice guidance, live rerouting, traffic, closures, flooding, emergency-vehicle access restrictions, or dispatch-approved road-condition overlays. A route is guidance, not proof of current road passability or a guaranteed ETA. Keep the external Maps fallback and dispatcher confirmation flow.

Before enabling `-PtacresqRoutePreviewEnabled=true`, a reviewer must verify all of the following with synthetic accounts and data:

- the private router starts from a real approved graph;
- Laravel can reach it through the private hostname;
- the smoke route returns valid geometry, distance, duration, and bounded instructions;
- access logs contain no coordinates or request bodies;
- rate limits and authorization reject unrelated incidents and roles;
- graph storage, retention, backups, updates, and rollback are understood;
- the team accepts the host's privacy and cost implications.

No Render service, disk, environment variable, live migration, graph, or deployment is created by these files.

## References

- [OSRM HTTP API](https://project-osrm.org/docs/v26.10.0/http) — route requests and response fields.
- [OSRM self-hosting guide](https://github.com/Project-OSRM/osrm-backend) — official image and preprocessing workflow.
- [OSRM server options](https://github.com/Project-OSRM/osrm-backend/blob/master/docs/routed.md) — access-log configuration.
- [OpenStreetMap copyright and license](https://www.openstreetmap.org/copyright) — ODbL and attribution requirements.
- [OpenStreetMap Foundation attribution guidelines](https://osmfoundation.org/wiki/Licence/Attribution_Guidelines) — routing-engine and interactive-map attribution guidance.
- [Geoapify Route Planner guide](https://apidocs.geoapify.com/how-to/route-optimization/choose-jobs-or-shipments/) — JSON POST format, task types, and coordinate order.
- [Geoapify pricing details](https://www.geoapify.com/pricing-details/) — Route Planner credit formula.
