# TACResQ — interactive wireframe preview

The original gallery has been revised into a **clickable, role-based mobile design prototype**. It covers the primary requester, responder/driver, and dispatcher paths, with a deliberately centered **GPS Map** destination and visible prototype-only/data-freshness warnings.

## Open the prototype

Open [`gallery.html`](gallery.html) in a modern browser served over HTTP. From the repository root, run `python3 -m http.server 8766` and visit `http://127.0.0.1:8766/docs/wireframes/gallery.html`. No package installation or API key is needed. An internet connection is required for Leaflet and OpenStreetMap street tiles; the rest of the prototype is local. The interface is responsive; at smaller widths, the role picker moves above the phone preview.

The dedicated [TACResQ app icon](../brand/tacresq-app-icon.png) is used in the mobile preview and is exported for Android and installable web-app sizes. It is a proposed prototype mark, not a verified official TACRU logo.

Use the left-side controls to switch user role or simulate network and GPS conditions. The same invented incident is shared across role views to demonstrate the requester → dispatcher assignment → responder progression. Use **Reset demo scenario** in the right panel to start over.

## Screenshots

Browse all **42 clean 2× mobile screenshots** in the [clickable visual gallery](screenshots/gallery.html), or use the [screen index](screenshots/README.md) for the descriptions. The set covers every role screen plus the main consent, confirmation, offline, GPS, and completed-response states.

## Should wireframes be black and white?

For early structure and flow reviews, **grayscale is a good low-fidelity option** because it keeps attention on hierarchy and navigation. This prototype now follows the colors already implemented in the repository’s dispatch console (warm off-white, deep charcoal, sage, and ember accents). The repository does not contain an approved TACRU brand guide, so this is a cross-product consistency choice—not a claim that these are TACRU’s official colors. The icon is also a proposed mark. Confirm both against an approved brand guide/logo before release. Keep color paired with text or icons and never make it the only status signal.

## What is covered

- Role-based sign-in preview and distinct requester, field-unit, and dispatcher navigation.
- Center-docked GPS Map icon control, positioned between two navigation destinations on either side. All bottom destinations are icon-only visually, with accessible names retained for assistive technology.
- Requester incident type/description, location sharing explanation and acknowledgement, separate SOS review and confirmation, duplicate-request prevention, request history, and cancellation confirmation.
- GPS permission-not-granted, low-accuracy, stale-fix, and unavailable states.
- Online, weak-signal, and offline behavior, with unsent/queued states clearly distinguished from server-confirmed actions.
- Dispatcher incident queue, available-unit visibility, explicit assignment confirmation, unit status versus GPS position, and an activity log.
- Responder current call, duty state, safe navigation handoff, “cannot safely respond” path, confirmed status progression, completion, and history.
- Role-specific, full-screen interactive OpenStreetMap views with pan, zoom, and a center-on-sample control; they hide the app header and bottom navigation. The basemap is real, but markers and incident coordinates are fixed sample data—not device GPS, live responder positions, routes, distance, or ETA.
- Privacy and safety disclosures, support-contact verification warning, and an explicit non-operational demo banner.

The detailed screen rules, walkthrough, edge cases, and implementation boundary are in [`DESIGN_SPEC.md`](DESIGN_SPEC.md). The older static PNG screenshots at the top level of this folder are retained as historic illustrations; use the HTML gallery and the new [`screenshots/`](screenshots/README.md) set for the revised prototype.

## Map source and use limits

The prototype uses Leaflet 1.9.4 with the standard OpenStreetMap raster tile endpoint and keeps the required `© OpenStreetMap contributors` attribution visible. The view is centered on fixed Tacloban demo coordinates; the app does not request phone location. Opening the map contacts the CDN for Leaflet and the tile server for the visible viewport, so map tiles will not load from a `file://` URL or while offline.

This is intentionally separate from the native Android SOS flow: the map keeps its fixed sample/demo marker (the selected map option), while the real Android app requests one current GPS fix only when the requester taps the system location button and transmits it only if the requester later confirms an SOS. The map itself does not start location access or tracking. See the root [Android setup and location privacy notes](../../README.md#one-time-location-privacy-and-android-17) and [`GPS_LOCATION_NOTES.md`](../GPS_LOCATION_NOTES.md).

This public tile service is best-effort and has no service-level guarantee. The prototype requests only tiles needed for the visible map and does not prefetch or offer offline downloads. Do not treat the public tile server as a production SLA: choose a provider whose terms and capacity fit the deployment, or self-host, before release. See the [OpenStreetMap Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/) and [Leaflet Quick Start](https://leafletjs.com/examples/quick-start/).

## Files

- [`gallery.html`](gallery.html) — prototype shell and role/simulation controls.
- [`tacru-wireframes.css`](tacru-wireframes.css) — responsive visual system and phone preview.
- [`tacru-prototype.js`](tacru-prototype.js) — interactive demo state, screens, and confirmation flows.
- [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — behavior requirements and production boundaries.

## Prototype-only warning

All identities, request details, statuses, times, and incident coordinates are fictional demo data. The street basemap is real OpenStreetMap data, but the marker is not a real call or the phone’s location. The prototype does not sign in, request device permissions, access GPS, transmit or persist SOS reports, assign actual units, send notifications, call phone numbers, or contact emergency services. Never use it to request or coordinate real emergency assistance.
