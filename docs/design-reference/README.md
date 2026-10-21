# TACResQ UI design research and reference pack

Status: **research and exploration; final design is not selected.** The SVG is an original, locally stored concept board made for TACResQ. It is not a screenshot of the current app, not an implementation preview, and not proof of current functionality.

## Read this first

- `COMPARATIVE_RESEARCH.md` — the main research document. It compares Android navigation, adaptive layouts, emergency response patterns, live location sharing, and location permission handling; keeps multiple design options open; and provides an evaluation checklist.
- `tacresq-ui-inspiration.svg` — an original concept board with Home, Live Map, and Incident Detail directions. Use it as a starting hypothesis, not the final visual target.
- `design-tokens.json` — draft tokens to discuss; these are not wired into the app theme.
- `SOURCE_NOTES.md` — selected source notes and why the sources are relevant.

## Research posture

Do not narrow the final design based on the concept board alone. Compare:
1. Standard Material navigation with five persistent labels.
2. A standard navigation bar plus a distinct Map action.
3. The custom center-emphasized Map destination, retaining all five labels.

Also compare full-screen map with an expandable incident sheet, map with a compact status strip, and synchronized incident-list/map presentation. Use actual project code and device/emulator screenshots to judge these options.

## Design principles under investigation

- Clear emergency task hierarchy, without making every normal state look like an emergency.
- Stable, readable navigation labels and no unexplained spacing.
- Adaptive layouts based on actual available window size.
- Map controls, incident freshness, and location-permission fallbacks that are explicit and honest.
- Role-appropriate incident information and location privacy.
- Consistent type scale, icon family, spacing, semantic colors, and accessible touch targets.

## Guardrails

- Do not copy a third-party UI wholesale.
- Do not treat illustrative pins, coordinates, status, or route lines as live data.
- Do not claim visual verification until real emulator/device screenshots have been inspected.
- Do not merge or apply a final style solely because the concept board looks attractive.
