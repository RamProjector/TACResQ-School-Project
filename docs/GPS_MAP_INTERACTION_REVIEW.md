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


## Follow-up: avoid redraws on routine collection refreshes

The map annotation effect previously depended directly on the incident and responder lists, plus route result objects. A refreshed list instance could therefore clear and recreate every annotation even when the rendered content had not changed. The effect now depends on a render signature containing the fields that affect marker position, title/snippet state, freshness bucket, and age label, plus compact device-coordinate and route-geometry signatures. Selection and navigation state remain explicit effect keys because they change the selected pin or active route. The signature deliberately excludes unrelated incident fields and backing-object identity.

This reduces avoidable annotation churn but is not a measured performance claim: no profiler or device run was performed. The route signature uses the geometry list hash; if route geometry becomes very large or collision-safe incremental identity is needed, replace it with an explicit route revision/key supplied by the routing layer.


## GPS annotation reconciliation — 2026-11-17

- **Finding:** The prior redraw-key improvement reduced how often the annotation effect runs, but every run still called `map.clear()`, then recreated every incident/responder/device marker and the route. A change to one visible marker therefore rebuilt unrelated annotations.
- **Implementation on PR #334:** Replaced whole-map clearing on ordinary refresh with stable-key reconciliation. Incident keys use incident IDs; field-unit keys use the incident/user pair; the one-time device fix has its own key. Existing marker objects update only when their rendered signature changes, disappeared markers alone are removed, and unchanged markers retain their MapLibre annotation IDs.
- **Route isolation:** The route polyline is tracked separately and replaced only when the displayed route geometry signature changes. Marker changes no longer require recreating the route.
- **Lifecycle boundary:** A full clear remains only when the underlying MapLibre map instance changes, preventing annotation references from being reused against a different map.
- **Limitations:** This optimizes the existing legacy annotation renderer; it is not the clustering implementation or a migration away from deprecated APIs. The route signature still uses a geometry hash, so exact geometry comparison and profiling remain follow-up work.
- **Validation:** No build, JVM tests, emulator, physical-device run, or profiling was performed for this commit, in accordance with the current instruction not to run checks after each commit.


## GeoJSON clustering research and design constraints — 2026-11-17

### Confirmed MapLibre Android capability

- The app currently uses `org.maplibre.gl:android-sdk-opengl:13.7.1`. The Android GeoJSON source API exposes `GeoJsonOptions.withCluster`, `withClusterMaxZoom`, `withClusterMinPoints`, `withClusterRadius`, and custom cluster properties. GeoJSON clustering has been supported by MapLibre Native Android since 4.2.0, so the current SDK line is new enough for the core feature.
- Cluster features expose `cluster_id` and `point_count`. The Android `GeoJsonSource` API exposes `getClusterExpansionZoom(feature)`, `getClusterChildren(feature)`, and `getClusterLeaves(feature, limit, offset)`. Use expansion zoom for normal taps; use bounded leaf retrieval only when a chooser is actually needed.
- **Radius unit correction:** Android's `GeoJsonOptions.withClusterRadius(Int)` documentation describes the radius in 1/512ths of a tile. Do not treat the numeric value as Android dp or blindly copy it as a fixed screen-pixel radius. Validate visual density/zoom behavior on device.
- Official references: [GeoJsonOptions](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.style.sources/-geo-json-options/index.html), [withClusterRadius](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.style.sources/-geo-json-options/with-cluster-radius.html), [getClusterExpansionZoom](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.style.sources/-geo-json-source/get-cluster-expansion-zoom.html), [getClusterLeaves](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.style.sources/-geo-json-source/get-cluster-leaves.html), and [official Android clustering example](https://maplibre.org/maplibre-native/android/examples/styling/circle-layer/).

### TACResQ source/layer architecture

- Keep **incident** and **field-unit** points in separate GeoJSON sources. A combined count would be operationally ambiguous and could imply responder availability/assignment that the data does not establish.
- Keep the one-time device fix outside both cluster sources. It is device-local preview state, not an incident or responder report.
- Split fresh/aging and stale field-unit reports into separate render groups unless the UI can clearly communicate freshness composition for every cluster. Never render a mixed cluster as if all reports are live.
- Keep stable domain IDs in feature properties (incident ID; incident ID + user ID for field-unit reports). Never recover identity from a visible title or cluster count.
- Cluster circles/count labels and unclustered symbols should be separate style layers per source. Selected incident/field-unit state must remain visible and be re-applied after source updates/style reloads.
- Cluster taps should animate to `getClusterExpansionZoom`; if zoom cannot separate the cluster, open a bounded chooser backed by `getClusterLeaves`. Revalidate selected feature IDs against current app state before opening details.
- Routes remain a separate line source/layer or existing route annotation; they must not participate in point clustering.

### Migration and validation gates

- Do not combine clustering migration with the annotation-plugin migration. Legacy `Marker` APIs are deprecated from MapLibre 7.0, but replacing the renderer and changing interaction semantics simultaneously would enlarge regression risk.
- Build and style sources only after the map style has loaded; recreate source/layer registrations on style reload while retaining the current app-state feature data.
- Update GeoJSON source data rather than clearing/rebuilding all map annotations on every refresh.
- Before release, test zero/one/two points, dense same-type points, incident/responder coordinates that overlap, stale/fresh field units, selected marker changes, source refresh during a cluster tap, cluster leaf pagination, route visibility, style reload, offline tiles, and compact/foldable layouts.
- No cluster renderer has been implemented in this documentation commit. The current PR implementation remains incremental reconciliation of legacy annotations; actual cluster rollout requires a separate renderer implementation and exact-head validation.


## Renderer signature correctness follow-up — 2026-11-17

- Replaced route geometry hash invalidation with an exact ordered list of latitude/longitude pairs. A hash collision could otherwise leave an obsolete route displayed; exact value equality avoids that correctness risk.
- Removed icon-object hash from responder marker signatures. Freshness is already an explicit signature component and determines stale versus normal pin styling, so object hash was redundant and could cause needless updates if icon instances changed.
- Research confirmation: MapLibre Android 13.7.1 documents mutable Marker position/icon/title/snippet properties, updateMarker, and individual marker/polyline removal. These APIs validate the incremental reconciliation calls, while remaining deprecated legacy annotations. References: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.annotations/-marker/index.html and https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map/index.html.
- No build or runtime validation performed.
