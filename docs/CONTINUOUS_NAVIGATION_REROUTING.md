# Continuous in-app navigation and rerouting

**Status:** Implementation in progress. This feature remains opt-in and is not operationally validated. No live Geoapify request or real responder location was used for development checks.

## Design

A responder or driver explicitly starts a live route for their own en-route assignment. This starts or upgrades the existing location foreground service. The same fresh, coarse location stream continues to be sent through the assignment-checked TACResQ location RPC for dispatcher visibility and is evaluated against the active route geometry for off-route detection. The routing provider remains server-side; Android sends route-preview requests only to TACResQ.

The first sufficiently accurate current fix requests the initial route. Later recalculation is triggered only after at least two consecutive accurate fixes remain meaningfully distant from the current route. Low-quality or missing-accuracy fixes do not trigger recalculation. A 30-second minimum interval between provider requests means a basic two-point route costs at most two credits per minute while continuously deviated, before any route-distance surcharge. Normal GPS sampling therefore never becomes a routing request per fix. Provider errors trigger exponential retry backoff up to five minutes. A reroute replaces the displayed route; if recalculation fails, the last known route is retained and the UI reports the failure without silently switching providers.

Stopping location sharing stops the navigation session as well. The service also stops when the account/session is unauthorized, the unit no longer has the en-route assignment, required permissions are withdrawn, or Android ends the service. The app does not infer arrival or change response status from GPS. Dispatcher map markers remain last-reported positions with existing freshness warnings.

## Evidence and constraints

Android documents a `location` foreground-service type for long-running navigation and location-sharing use cases. The service must be started while the app has a visible activity when it relies on while-in-use location permission; starting it from the background is restricted. This implementation retains the user-visible, explicit start action, persistent stop notification, and no `ACCESS_BACKGROUND_LOCATION` permission. [1] [2] [3]

Geoapify's standard Routing API returns route geometry and route information for ordered waypoints. A basic two-waypoint calculation is priced at one credit per waypoint pair; Geoapify also documents an additional distance surcharge for routes exceeding 500 km. Therefore every reroute has an external-provider and credit cost. The implementation throttles recalculation locally to at most one request every 30 seconds and keeps backend rate limiting as a second safeguard. Geoapify's documented `free_flow` and `approximated` traffic values are traffic models, not a promise of live traffic conditions. [4] [5] [6]

The project has not yet confirmed a successful Geoapify route response on the configured account. Routing remains disabled unless the Android build explicitly opts in. Do not use real field coordinates to validate the provider; complete a separately authorized synthetic test and controlled device test before enabling routing for operational use. [7]

## Validation gates

Before operational use, verify with a synthetic test account and controlled Android device that: route start is explicit; dispatch receives only valid, assignment-authorized location updates; repeated on-route fixes do not call the router; sustained off-route fixes cause a bounded reroute; failed reroutes preserve a truthful unavailable state; service stop, sign-out, permission revocation, assignment end, and process/service termination clear navigation; routes render on the map; dispatcher freshness changes are accurate; and no live location is sent to a second provider.

## References

[1]: https://developer.android.com/develop/background-work/services/fgs/service-types "Android foreground service types"
[2]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start "Restrictions on starting foreground services from the background"
[3]: https://developer.android.com/develop/sensors-and-location/location/background "Android background location guidance"
[4]: https://apidocs.geoapify.com/docs/routing/ "Geoapify Routing API"
[5]: https://www.geoapify.com/pricing-details/ "Geoapify pricing details"
[6]: https://apidocs.geoapify.com/how-to/routing/traffic-route-times/ "Geoapify traffic route times"
[7]: https://apidocs.geoapify.com/docs/route-planner/ "Geoapify Route Planner API"
