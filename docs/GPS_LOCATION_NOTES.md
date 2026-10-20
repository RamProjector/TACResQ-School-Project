# TACResQ one-time GPS location: implementation basis

This note records the platform requirements and source references behind the requester SOS location flow. It is not a substitute for a production privacy review or Play Console declaration.

## Product behavior

- A requester initiates location access by tapping Android's system Location Button; TACResQ does not start location collection at screen-open or in the background.
- The app requests a single current fix with a bounded wait and rejects invalid/stale results. It does not fall back to `lastLocation`.
- The fix is previewed with its reported accuracy and capture time, then sent only with the requester's explicit SOS confirmation.
- If location permission is denied, system Location is disabled, or no current fix arrives, the SOS can still be sent without coordinates. Any landmark note is explicit. The server never substitutes `0,0`, a cached point, or an assumed location.
- The location is attached to the incident and visible only to the requester, dispatcher, and assigned unit under the existing incident authorization. No continuous requester track is created.

The wireframe street map deliberately keeps its fixed sample/demo marker; it does not read device GPS or follow the requester. The live device fix is used only by the separate SOS submission flow. Also, a client-reported coordinate is not tamper-proof proof of physical presence: device settings, poor signal, mock-location tools, or a modified client can make it inaccurate. Dispatch should treat its age/accuracy as supporting information and verify critical details with the requester; this school prototype is not suitable as the sole basis for real emergency response.

## Platform implementation

The AndroidX Compose entry point is `androidx.core.locationbutton.compose.LocationButton`, with the `SharePreciseLocation` preset. The manifest declares coarse location, fine location with `android:usesPermissionFlags="onlyForLocationButton"`, and `android.permission.USE_LOCATION_BUTTON`. The current AndroidX artifact is `1.0.0-alpha01`; it automatically falls back to a standard permission prompt on Android 16 and lower. Because the artifact is alpha, verify the latest release/API before a production build.

After the user grants access through the button, the app calls Google Play services `FusedLocationProviderClient.getCurrentLocation(CurrentLocationRequest, CancellationToken)`. The request uses high accuracy, permission-level granularity, `setMaxUpdateAgeMillis(0)`, and a 15-second duration. The app adds an 18-second cancellation deadline and a monotonic-age check; `null`, stale, invalid, or unavailable results are handled explicitly.

Google Play's policy preview says apps targeting Android 17 / API 37+ that need precise location only for one-time user-initiated actions must use the Android Location Button, with `onlyForLocationButton`. The policy page states an effective date of January 27, 2027. Check the live policy and library release before release because both the policy preview and the library may change.

## Responder foreground sharing (separate from requester one-time GPS)

When an assigned responder chooses **Share location & start**, the app explains that approximate device location is shared with authorized dispatch for that incident. Android asks for `ACCESS_COARSE_LOCATION` at that action; the tracking request explicitly uses coarse granularity and balanced-power priority, with a nominal 12-second interval and 20-meter minimum movement. It does not use the fine permission reserved for the one-time Location Button flow. Dispatch receives fixes only through `api_record_location_v2`; server checks the assignment, `en_route` state, coordinate/timestamp range, and an 8-second minimum interval. A responder may start without sharing.

The update flow is collected only while the responder screen is visible. It unregisters its fused-location callback on cancellation, and sharing state is cleared when the app leaves the foreground, the responder screen closes, the incident status/assignment changes, permission is denied/withdrawn, or the responder explicitly stops. Returning to the app does not silently restart it: the responder must explicitly resume sharing. A no-fix, unavailable-services, permission, or API/network error is surfaced, and failed points are not queued for later replay. This source-pack implementation has not yet been built or tested on a device; verify platform behavior and current Play policy before any operational or production use.

## Official sources

- Android Developers, [Request session-based location access with the location button](https://developer.android.com/guide/topics/permissions/private-alternatives/location-button) — use cases, manifest permissions, Compose example, and Android 16-and-lower fallback.
- Android Developers, [Core Locationbutton release notes](https://developer.android.com/jetpack/androidx/releases/core-locationbutton) — artifact coordinates and `1.0.0-alpha01` status.
- Android Developers, [Compose `LocationButton` API reference](https://developer.android.com/reference/kotlin/androidx/core/locationbutton/compose/LocationButton.composable) — `onPermissionResult`, `onError`, `onRequestPermissions`, preset text, and remote/local rendering behavior.
- Google Play services, [CurrentLocationRequest.Builder](https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest.Builder) — duration, maximum update age, granularity, and priority.
- Google Play services, [FusedLocationProviderClient](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient) — one-shot `getCurrentLocation`, cancellation, null-on-timeout, and permission behavior.
- Google Play, [Permissions and APIs that Access Sensitive Information — location permissions](https://support.google.com/googleplay/android-developer/answer/16909972) — minimum-scope rules, Android 17 location-button policy, and stated effective date.
