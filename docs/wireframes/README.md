# TACRU TACResQ — interactive wireframe preview

The original gallery has been revised into a **clickable, role-based mobile design prototype**. It covers the primary requester, responder/driver, and dispatcher paths, with a deliberately centered **GPS Map** destination and visible prototype-only/data-freshness warnings.

## Open the prototype

Open [`gallery.html`](gallery.html) in a modern browser. It runs locally with no package installation, API key, external service, or network connection. The interface is responsive; at smaller widths, the role picker moves above the phone preview.

Use the left-side controls to switch user role or simulate network and GPS conditions. The same invented incident is shared across role views to demonstrate the requester → dispatcher assignment → responder progression. Use **Reset demo scenario** in the right panel to start over.

## What is covered

- Role-based sign-in preview and distinct requester, field-unit, and dispatcher navigation.
- Center-docked, labeled **GPS Map** control, positioned between two navigation destinations on either side.
- Requester incident type/description, location sharing explanation and acknowledgement, separate SOS review and confirmation, duplicate-request prevention, request history, and cancellation confirmation.
- GPS permission-not-granted, low-accuracy, stale-fix, and unavailable states.
- Online, weak-signal, and offline behavior, with unsent/queued states clearly distinguished from server-confirmed actions.
- Dispatcher incident queue, available-unit visibility, explicit assignment confirmation, unit status versus GPS position, and an activity log.
- Responder current call, duty state, safe navigation handoff, “cannot safely respond” path, confirmed status progression, completion, and history.
- Role-specific map screens that do not fabricate live responder/requester positions, routes, distance, or ETA.
- Privacy and safety disclosures, support-contact verification warning, and an explicit non-operational demo banner.

The detailed screen rules, walkthrough, edge cases, and implementation boundary are in [`DESIGN_SPEC.md`](DESIGN_SPEC.md). The earlier static PNG screenshots in this folder are retained as historic illustrations and do **not** represent the revised prototype; use the HTML gallery instead.

## Files

- [`gallery.html`](gallery.html) — prototype shell and role/simulation controls.
- [`tacru-wireframes.css`](tacru-wireframes.css) — responsive visual system and phone preview.
- [`tacru-prototype.js`](tacru-prototype.js) — interactive demo state, screens, and confirmation flows.
- [`DESIGN_SPEC.md`](DESIGN_SPEC.md) — behavior requirements and production boundaries.

## Prototype-only warning

All identities, request details, statuses, times, and plotted points are fictional demo data. The map is a schematic illustration, not a real Tacloban basemap. The prototype does not sign in, request device permissions, access GPS, transmit or persist SOS reports, assign actual units, send notifications, call phone numbers, or contact emergency services. Never use it to request or coordinate real emergency assistance.
