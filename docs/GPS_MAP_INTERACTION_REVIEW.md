## GPS map failure messaging — 2026-11-17

- Reviewed the existing MapLibre load-failure callback and connectivity monitor. The map already exposed a generic failure state and an Offline indicator, but the failure message did not distinguish an offline failure from a map-data failure while connectivity was validated.
- The failure listener now reads the latest validated-connectivity state (rather than a stale captured value) and explains when missing internet likely prevented map tiles/data from loading. Incident records remain available and retry is explicit.
- Successful map-load completion clears either corresponding transient failure message. The independent Offline indicator remains governed by Android's validated-network state; this does not claim that a validated connection guarantees the tile provider is healthy.
- No distinction is inferred between style and individual tile failures because the current listener reports map-load failure at the MapView level, not a reliable per-tile diagnostic. No build/tests/emulator/device validation run.

## GPS selection invalidation feedback — 2026-11-17

- Reviewed selection behavior during incident and responder refreshes.
- A selected field-unit report previously disappeared silently when its report expired or was removed. The map now clears that selection with an explicit status message explaining that the report is unavailable/expired.
- A selected incident previously cleared silently when it left the role-visible active set. The map now cancels pending route work, invalidates late route responses, clears the route preview, and explains that the incident is no longer active or visible.
- Routine refreshes still preserve selections while their records remain visible. No changes to responder tracking consent or emergency status rules.
- No build, tests, emulator, or device validation run.

## GPS style-retry stale-source recovery — 2026-11-17

- Found a retry-only lifecycle omission: the initial style-load path registered the stale-responder GeoJSON source, but the explicit retry path restored only incident and fresh/aging responder sources.
- Fixed retry to reattach the stale-responder source reference from the newly installed style as well. Without this, stale responder cluster taps could stop working after a manual map retry even though their layer/source had been recreated.
- No changes to freshness thresholds, stale-location presentation, or source contents. No build, tests, emulator, or device validation run.

## Dense-cluster feature lookup optimization — 2026-11-17

- Built remembered indexes for visible incidents and valid responder reports, keyed by incident ID and the incident/user pair respectively.
- Cluster leaf resolution, page loading, point-hit selection, and selection revalidation now use keyed lookups instead of repeatedly scanning the full Compose lists for every map feature. This changes the per-leaf record lookup from linear to constant-time after each source-list refresh; index construction remains linear.
- Kept the indexes tied to the current visible lists and exposed their latest versions to the long-lived MapLibre click listener, so refreshed records are still revalidated at interaction time.
- No map behavior, role visibility, freshness policy, or backend contract changed.
- No build, tests, emulator, device validation, or profiling run.

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


## Style reload and route-effect invalidation — 2026-11-17

- Found a two-layer invalidation gap: exact route comparison was downstream of a Compose effect still keyed by `geometry.hashCode()`, and style replacement did not invalidate the renderer's remembered map owner.
- The effect key now uses ordered coordinate pairs, matching the exact route signature used by reconciliation.
- Initial style completion and retry style completion invalidate the annotation owner; the next render clears stale references and recreates markers and route against the new style.
- Device verification is still required for style reload/retry and route restoration. No tests/build/device checks run.


## Structural marker signatures — 2026-11-17

- Marker reconciliation now compares typed immutable signatures (coordinates, explicit icon variant, title, snippet) instead of delimiter-joined strings.
- This removes ambiguity from incident notes containing separator characters and makes icon-state dependencies explicit without object hashes.
- No behavioral thresholds or marker identity rules changed. No tests/build/device checks run.


## Route request lifecycle guard — 2026-11-17

- Route requests now validate their generation and selected incident after asynchronous location capture and again after the routing provider returns.
- Late results cannot replace the selected route/status or move the camera. Posted camera work and exception messages are guarded too, covering non-cooperative cancellation and rapid incident switching.
- This is a source-level race guard; delayed-provider device verification remains outstanding. No tests/build/device checks run.


## MapView listener cleanup — 2026-11-17

- Map load success/failure listeners are now retained and explicitly removed when the GPS screen disposes.
- Listener callbacks ignore events after disposal; the asynchronous `getMapAsync` callback also exits if the screen was already disposed.
- This closes a lifecycle cleanup gap. Repeated navigation and delayed initialization still require device verification. No tests/build checks run.


## Unified MapView lifecycle ownership — 2026-11-17

- MapView setup, load listeners, lifecycle observer, and destruction now share one Compose effect rather than relying on disposal ordering between two effects.
- Teardown order is explicit: mark disposed, unregister map listeners, remove lifecycle observer, pause/stop active states, then destroy MapView. The map-ready callback checks the same effect-scoped disposed flag.
- No build/test/device checks run; lifecycle transition testing remains required.


## Long-lived marker callback freshness — 2026-11-17

- The MapLibre marker listener now calls the latest Compose marker-selection callback via `rememberUpdatedState` rather than retaining the callback created during initial map setup.
- This ensures marker taps validate against current visible incidents, responder reports, device preview, and freshness state after recomposition/data refresh.
- Device verification remains required; no tests/build checks run.


## Route identity, camera restoration, and memory pressure — 2026-11-17

- Route requests now carry both request generation and selection generation. This closes the A → B → A stale-result gap that incident-ID comparison alone cannot detect. The guard covers asynchronous settings checks, location capture, routing responses, status/error publication, and queued camera updates.
- Camera target, zoom, bearing, and tilt are saved when MapLibre becomes idle and restored when the screen/map is recreated. Invalid saved targets fall back to the established Tacloban default. Listener cleanup is explicit.
- MapView now receives Android low-memory and running-low trim callbacks through a lifecycle-scoped ComponentCallbacks2 registration, dispatched on the main thread and unregistered at disposal.
- MapLibre 13.7.1 documents `MapView.onLowMemory()` as required from the host Activity/Fragment low-memory callback; `MapLibreMap` provides add/remove camera-idle listeners. References: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-view/on-low-memory.html ; https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map/add-on-camera-idle-listener.html ; https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map/remove-on-camera-idle-listener.html.
- No tests/build/device checks run; verify rapid selection changes, process recreation, camera gestures, and memory-pressure transitions on device.


## Camera restoration and route failure follow-up — 2026-11-17

- Initial automatic fit-to-pins now runs only when there is no saved camera target, preventing restored pan/zoom from being immediately overwritten by the initial-data framing effect.
- Route exception publication checks both request and selection identity, closing the remaining stale-error path. Latest-request loading cleanup remains keyed to request generation so an older request cannot clear a newer request's spinner.
- No tests/build/device checks run; confirm on device after rotation/process recreation and rapid incident switching.


## Native GeoJSON clustering — 2026-11-17

- **Implemented on PR #334:** Replaced independent incident and responder marker annotations with two native MapLibre GeoJSON cluster sources. Each source has its own cluster circles, count labels, and unclustered point layer. Fresh/aging responder points and stale last-known points have separate visual treatments; device preview and route rendering remain outside the clusters.
- **Interaction:** Tapping a cluster requests MapLibre's expansion zoom. Tapping an individual point resolves its stable domain ID against the latest Compose data and opens the existing incident/field-unit selection flow. Cluster lookups catch runtime failures caused by stale cluster features during source refresh.
- **Refresh/style lifecycle:** Cluster source data is updated from the current validated visible incident and responder sets. Both sources and layers are recreated after initial style load and explicit style retry.
- **Research:** MapLibre Android's official example uses `GeoJsonSource` with `GeoJsonOptions.withCluster(true)`, `point_count` filters, and `SymbolLayer` count labels. Android clustering is supported from MapLibre Native 4.2.0; this app uses 13.7.1. References: https://maplibre.org/maplibre-native/android/examples/styling/circle-layer/ ; https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.style.sources/-geo-json-source/get-cluster-expansion-zoom.html ; https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map/query-rendered-features.html.
- **Known limitations:** Native cluster bubbles currently use category-specific colors and counts; individual pin artwork is replaced by colored circles. A bounded leaf chooser for clusters that cannot expand further is outstanding. Verify density, tap targeting, style reload, and source-update races on devices before calling this complete.
- **Validation:** No Gradle build, JVM tests, emulator/device run, or profiling performed, in accordance with the instruction not to run checks after each commit.



## Cluster interaction and lifecycle follow-up — 2026-11-17

- Moved the map click listener effect below MapView and cluster-source state declarations so it is owned only after its dependencies exist; listener registration remains paired with removal.
- Cluster taps now zoom to MapLibre's expansion level when that level is available. At maximum expansion, the tap retrieves up to 50 leaves and builds a chooser from only currently visible incidents and still-valid responder reports. Stale responder entries remain explicitly marked last-known.
- Expired cluster features and reports are handled as recoverable refresh states rather than selecting stale records. Native cluster API runtime errors are caught because cluster IDs can expire during source replacement.
- The chooser is bounded to 50 records to avoid unbounded synchronous work on the UI thread; when records have expired, the user is told that only currently available reports are shown.
- No build, tests, emulator, device validation, or performance profiling run.



## Cluster source invalidation correction — 2026-11-17

- Added both GeoJSON source references to the annotation/source update effect keys. This ensures the first render and a style reload publish current features after the asynchronous style callback creates the sources; previously a source becoming available alone would not retrigger that effect.
- Explicit map retry now clears old source references and recreates both cluster sources/layers on the replacement style, matching initial style setup.
- This is an important lifecycle correction for the new clustering path. No tests/build/device checks run.



## Cluster point hit-target and overlap disambiguation — 2026-11-17

- Individual clustered-source points now use a 48dp screen-space query area instead of requiring taps to land inside the small painted circle.
- When multiple incident points or responder points occupy that touch area, the map presents the existing nearest-first location chooser rather than silently selecting whichever feature is returned first.
- Chooser targets are revalidated against the latest visible incidents and valid responder reports. Stale responder locations retain their last-known warning.
- Incident and responder point queries remain separate so a mixed set cannot be mistaken for a single type of cluster.
- No build, tests, emulator, device validation, or performance profiling run.



## Separate stale responder clustering — 2026-11-17

- Split responder map data into independent fresh/aging and stale GeoJSON cluster sources. Stale last-known reports now form grey clusters with their own counts instead of being mixed into the teal live/aging cluster count.
- Individual stale points remain grey and are only queried from the stale point layer. Fresh/aging responder points retain their separate layer and cluster source.
- Cluster tap handling supports both responder sources and still resolves individual leaves against the latest validated responder list before opening details.
- This avoids a potentially misleading cluster that visually implied all grouped responder locations were current. No build/tests/device checks run.



## Restore emergency pin artwork on clustered points — 2026-11-17

- Reused the app's existing incident, selected-incident, responder, and stale-responder drawable artwork as MapLibre style images, instead of leaving individual points as generic circles after clustering was introduced.
- Individual incident pins now have a distinct selected state; responder pins retain their own artwork, and stale responder artwork is faded to reinforce last-known status. Cluster bubbles remain count-based and category-colored.
- The shared bitmap helper now supplies both legacy device-preview annotation icons and style-backed symbol images, keeping pin-tip anchoring consistent.
- No build/tests/device checks run. Verify sprite registration, pin anchor, symbol scaling, selected state, and style recreation on supported screen densities.



## Cross-category GPS overlap handling — 2026-11-17

- Fixed an interaction gap where an incident cluster could consume taps intended for a responder cluster at the same screen position. When incident, fresh/aging responder, and stale responder clusters overlap, the map now builds one explicitly typed chooser from the current validated leaves rather than prioritizing one category.
- Individual incident and responder pins now share the same 48dp hit-area chooser. A responder marker can no longer become inaccessible merely because an incident pin occupies the same touch area (or vice versa).
- Cluster leaves and point features are revalidated against the latest visible incident and responder collections before presentation. Stale units remain labelled as last known.
- No build/tests/device checks run.



## Cluster style retry and pin anchor correction — 2026-11-17

- Corrected the explicit map-retry style callback to pass the required Android context when rebuilding cluster sources and style images. This keeps retry behavior aligned with initial style installation.
- Separated legacy annotation bitmap padding from GeoJSON style-symbol bitmaps. Style symbols are now created at the drawable's actual bounds, so `icon-anchor=bottom` anchors the rendered pin tip to its geographic coordinate rather than to an invisible padded region.
- Retained the existing padded bitmap helper for legacy device-location and annotation markers to avoid changing their established anchor behavior.
- MapLibre's style specification defines bottom anchoring as placing the image's bottom edge at the feature anchor; see the official Android/style API documentation.
- No build, tests, emulator, device validation, or visual pin-alignment check run.



## Dense cluster pagination — 2026-11-17

- Added paged cluster-leaf retrieval for dense incident and responder clusters. The chooser initially loads up to 50 leaves per source and now exposes a clear “Load more locations” action while more leaves remain.
- Pagination tracks each overlapping cluster source independently, so a mixed incident/responder overlap can page both without merging their source semantics. The displayed count reflects the underlying cluster counts.
- Every loaded page is resolved against the latest visible incident and valid responder records; stale responder details remain labelled as last known. Expired cluster IDs are caught and the chooser asks the user to tap the refreshed cluster again.
- Selecting or dismissing a choice clears paging cursors so stale cluster references are not reused.
- No build, tests, emulator, device validation, or profiling run.



## Cluster chooser consistency and pagination follow-up — 2026-11-17

- Updated the older annotation-overlap chooser to initialize the same count and pagination state as the new style-layer chooser, preventing a misleading “0 of N” count.
- Gave nested page iteration an explicit leaf label so invalid records skip only the current leaf, not the enclosing source page.
- Cluster chooser copy now distinguishes a dense cluster from ordinary overlapping pins and gives a concise next action.
- No build, tests, emulator, device validation, or profiling run.



## Role-screen information hierarchy pass — 2026-11-17

- **Responder:** moved the shared explanation of individual responder status versus combined incident status out of every active-response card. The arrival-versus-GPS-sharing safety reminder now appears once when at least one response is en route, rather than being repeated in every location-sharing panel.
- **Dispatcher:** moved the combined-team-progress explanation into the Active incidents section heading. When pending incidents exist but no units are available, the availability constraint is shown once above the queue instead of repeating on every incident card.
- **Behavior preserved:** no assignment, claimability, duty, status-transition, location-sharing, routing, API, or database logic changed. The dispatcher still only offers team assignment when available units exist.
- **Rationale:** reduce repeated card content and keep guidance at the section level while leaving incident-specific actions and status visible at the point of use.
- **Validation:** source review only. No Gradle build, tests, emulator, or device checks run.


## Accurate mixed-cluster totals — 2026-11-18

- The map chooser now totals all overlapping cluster sources using each hit feature's point_count. Previously, the displayed total could omit a small overlapping cluster when another source required pagination.
- Single-source cluster totals now use the source feature count directly; pagination remains limited to sources above the existing 50-leaf page size.
- Added a focused source contract and wired it into CI.
- No map selection, source validation, paging size, API, or database behavior changed.
- Static source inspection only. Contract test, Android build, emulator, and physical-device validation were not run.


## GPS camera-state restoration bounds — 2026-11-18

- Sanitized persisted MapLibre camera values before restoring them: zoom must be finite and within 0–22, bearing is normalized to 0–360, and tilt is clamped to 0–60 degrees. Invalid zoom/tilt/bearing values fall back to safe defaults.
- This protects map reopening from stale or malformed saved camera state without changing valid user camera positions, map data, location permissions, or incident coordinates.
- Added a focused source contract and CI step.
- Validation: source-level change only; contract execution, Gradle build, emulator, and physical-device camera checks have not yet been run.


## Stale GPS cluster-page feedback — 2026-11-18

- Cluster paging now treats an empty page before the recorded cluster total as a changed map source and asks the user to tap the current cluster again, rather than silently ending pagination.
- When a returned page contains records whose incident/responder location is no longer available, the chooser reports that some records are stale while retaining valid locations already loaded.
- Added a focused source contract and CI step. No API, backend, database, assignment, or location-sharing behavior changed.
- Validation: source-level change only; contract execution, Gradle build, emulator, and physical-device checks have not yet been run.
