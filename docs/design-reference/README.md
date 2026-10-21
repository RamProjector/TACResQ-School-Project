# TACResQ UI design research and reference pack

Status: **secondary priority; visual design is intentionally provisional while functionality is the primary priority.** The SVG is an original, locally stored concept board made for TACResQ. It is not a screenshot of the current app, not an implementation preview, and not proof of current functionality.

## Project priority

1. **Primary — functional correctness:** revise, add, or remove features where appropriate; ensure every visible control has a real, understandable behavior and the underlying workflow works end-to-end. A control is not considered functional merely because it looks clickable.
2. **Secondary — visual design and refinement:** keep a coherent, readable baseline so features can be tested. Polish, animation, decorative details, and final styling may change later after workflows stabilize.
3. **Always required — clear, visible, testable affordances:** a feature may be visually basic, but its purpose, current state, and next action must be understandable. Loading, success, empty, failure, permission-denied, and retry states should be visible where relevant.

## Read this first

- `COMPARATIVE_RESEARCH.md` — research findings, competing design options, evaluation checklist, and limitations.
- `tacresq-ui-inspiration.svg` — original concept board with Home, Live Map, and Incident Detail directions. Inspiration only; not a committed final design.
- `design-tokens.json` — draft tokens, not wired into the app theme.
- `SOURCE_NOTES.md` — selected sources and why they matter.

## Implementation rule

Functionality comes first, but not at the expense of testability. When a feature is implemented, users and testers must be able to see where it is, understand what it does, invoke it, and observe the result or error. Use clear labels and inline feedback; do not leave dead buttons, placeholder actions disguised as complete features, fake live data, or silent failures.

## Design principles under investigation

- Clear emergency task hierarchy, without making every normal state look like an emergency.
- Stable, readable navigation labels and no unexplained spacing.
- Adaptive layouts based on actual available window size.
- Map controls, incident freshness, and location-permission fallbacks that are explicit and honest.
- Role-appropriate incident information and location privacy.
- Consistent typography, icon family, spacing, semantic colors, and accessible touch targets.

## Guardrails

- Do not copy a third-party UI wholesale.
- Do not treat illustrative pins, coordinates, status, or route lines as live data.
- Do not claim visual verification until real emulator/device screenshots have been inspected.
- Do not merge or apply a final style solely because the concept board looks attractive.
- Prefer working, observable functionality over final visual polish; retain enough UI clarity to make that functionality testable.
