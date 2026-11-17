# GPS map interaction review

## Scope

This review targets the Android full-screen GPS map in `GpsMapScreen.kt`, its presentation helpers, and the dispatcher field-unit detail sheet. It does not change location permissions, responder consent, tracking lifecycle, routing providers, incident state, backend payloads, or database schemas.

## Findings and changes

### Overlapping pins could hide emergency locations

MapLibre marker annotations are individually tappable, but several incident and field-unit markers can render within the same visible tap area. A tap could therefore select only the top annotation and leave the other nearby location inaccessible from the map.

The map now compares marker anchor positions using MapLibre's current screen projection. If multiple pins fall within a 48 dp tap radius, it opens an explicit **Overlapping map locations** chooser in the bottom sheet. The tapped item appears first, followed by other nearby items. The user selects the intended incident, responder, or device location before its normal detail behavior runs. This is a disambiguation affordance, not coordinate clustering: no pins are merged, hidden, or represented as a synthetic count.

### Stable metadata remains separate from presentation

Marker IDs continue to resolve typed targets. Each target now also retains its validated coordinate and concise chooser title/summary. The chooser never parses marker title text to recover incident or responder identity.

### Accessibility and emergency context

Chooser entries are full-width, at least 52 dp high, expose button semantics, and retain concise freshness context. Stale responder entries explicitly say they are last-known and not live. Existing detailed accuracy and age remain in the dispatcher field-unit list.

## Research basis

- MapLibre's Android `Projection.toScreenLocation(LatLng)` returns a `PointF` in screen pixels relative to the map view. The collision threshold is therefore scaled from dp using the device density before comparing positions. See the [MapLibre Projection API](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-projection/to-screen-location.html).
- The app remains on its current MapLibre Android 13.7.1 OpenGL artifact. This patch does not introduce a renderer or annotation-plugin migration.
- The pure screen-radius helper has JVM tests for boundary inclusion, invalid coordinates, and invalid radii. Those tests have not been run in this commit.

## Remaining map work

- Validate tap behavior on device at multiple zoom levels, map pitches, and compact layouts.
- Evaluate actual marker icon footprints and touch accessibility on representative devices; 48 dp is a conservative interaction radius, not a guarantee that every icon collision is resolved perfectly.
- Consider data-driven GeoJSON clustering only after defining separate incident and responder layers, selected-feature behavior, stale-location treatment, and cluster expansion semantics.
- Reduce full annotation redraws without losing correct route/selection updates; profile before replacing the legacy annotation renderer.
- Keep the deprecated legacy Marker API migration separate until the annotation plugin is confirmed compatible with the app's exact SDK artifact and behavior.

## Explicitly unverified

No Gradle build, unit tests, emulator/device run, map-tile/network test, or performance profile was run for this change. The change does not establish that all map collisions are eliminated on physical devices.
