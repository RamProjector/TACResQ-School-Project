# TACResQ routing provider selection

## Recommended approach

Geoapify is the default hosted provider for TACResQ's static driving preview because the project is deployed on a free Render instance that is not a practical host for an always-on OSRM service and graph. Set `GEOAPIFY_API_KEY` as a Render backend secret; never send it to Android or commit it. `ROUTE_PROVIDER=osrm` remains available only when a private OSRM service is actually provisioned. The backend provider choice never falls back silently.

The route-preview API keeps its existing authorization boundary: it validates the signed-in field unit, confirms that unit's active assignment, and derives the destination from the authorized incident. The client supplies only the current origin and incident ID. The provider receives only the origin and destination coordinates; it does not receive the bearer token, incident ID, requester identity, or incident narrative.

## Location data and provider behavior

Geoapify's Routing API uses a GET request. Its `waypoints` query parameter contains ordered `latitude,longitude` coordinate pairs, and the API key is also a query parameter. Consequently, precise route coordinates and the API key exist in the outbound request URL. The backend uses Geoapify's fixed EU endpoint, disables redirects, and does not log the request URL or exception details. This reduces exposure in TACResQ logs, but it cannot prevent Geoapify or its infrastructure providers from seeing the request.

Geoapify's published privacy policy says it retains request bodies, headers, IP addresses, and timestamps for API requests; successful-request data is generally kept for no longer than 24 hours, while its DPA allows exceptional request details to be retained for up to two months in specified cases. The EU endpoint is described in its DPA as EU/EEA processing. This is still external processing of responder and incident locations, not private/self-hosted routing. Review the current policy and any organizational privacy requirements before enabling it for real responder coordinates.

The Android route action now explains, before the request, that approximate current location and incident coordinates go to TACResQ's configured routing service. It also states that TACResQ will not silently contact a second provider if the selected service fails.

## Credits and limits

Geoapify's current pricing details say a route request costs one credit per waypoint pair, plus one additional credit for each 500 km of returned route length when the route exceeds 500 km. The Free plan advertises 3,000 credits per day, shared across Geoapify APIs. A two-point route under 500 km therefore normally costs one credit, but geocoding, maps, or other APIs using the same account consume that shared pool too. Repeated previews can consume credits even when the user does not complete a response workflow. The app's existing per-user request throttle is a safeguard, not a hard account-wide daily quota.

Geoapify's `drive` profile represents passenger-car routing. It does not establish emergency-vehicle access, road passability, closures, flooding conditions, or live traffic. TACResQ should continue to label results as static estimates and should not treat the calculated duration as a dispatch promise or safety determination.

## Fallback policy

Do not automatically fail over from Geoapify to another service. A retry against a second provider would disclose the same precise coordinates to an additional party and could spend more credits. If Geoapify is unavailable, rejects the configured key, or is out of credits, the backend returns a sanitized failure and the responder can deliberately choose the existing external Maps action.

The existing private OSRM implementation remains available as a server-side alternative by setting `ROUTE_PROVIDER=osrm` and configuring its private `OSRM_BASE_URL`. It must use an internal-only host with an approved, maintained area graph. Do not set OSRM to a public demo endpoint. Keep the provider selection manual: automatic failover would send coordinates to an additional party and should not be introduced without explicit privacy approval and provider-specific monitoring.

## Enablement and validation gate

- Keep `ROUTE_PREVIEW_ENABLED=false` until provider configuration, privacy review, synthetic QA, and device validation are complete. This backend kill switch is independent of the Android build flag, so routing can be withdrawn without shipping a new APK; disabled endpoints return a sanitized 503 before authentication or any upstream request.
- When approved, set `ROUTE_PREVIEW_ENABLED=true` and keep `ROUTE_PROVIDER` at its default `geoapify` for the free Render setup. The app's route-preview build flag remains a separate explicit enablement gate.
- To roll routing back, set `ROUTE_PREVIEW_ENABLED=false`, redeploy the backend configuration, and verify both route-preview endpoints return the disabled response without contacting Supabase or the provider.
- Store only `GEOAPIFY_API_KEY` in the backend service's secret manager. Keep the temporary smoke-test token unset except during a separately approved diagnostic, then clear it.
- Before enabling the Android route-preview flag for real traffic, verify the key with an authorized synthetic test, inspect sanitized status only, and confirm that the location disclosure is appropriate.
- Do not use real responder or incident coordinates for provider tests. Do not log raw upstream URLs, response bodies, or HTTP exception messages.
- Keep external Maps as a user-initiated fallback. Do not automatically send coordinates to another provider after an upstream error.

## References

- [1] Geoapify Routing API request and response documentation: https://apidocs.geoapify.com/docs/routing/
- [2] Geoapify Pricing Details: https://www.geoapify.com/pricing-details/
- [3] Geoapify Privacy Policy: https://www.geoapify.com/privacy-policy/
- [4] Geoapify Data Processing Agreement: https://www.geoapify.com/data-processing-agreement/
- [5] TACResQ in-app routing foundation: [IN_APP_ROUTING_FOUNDATION.md](IN_APP_ROUTING_FOUNDATION.md)
- [6] Private OSRM setup and safety constraints: [backend/routing/README.md](../backend/routing/README.md)
