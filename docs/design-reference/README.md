# TACResQ UI design research and reference pack

Status: **visual quality and functional correctness are equal product requirements.** The reference pack is being updated to reflect the map-first and conversation-first direction. The SVG is an original, locally stored concept board made for TACResQ. It is not a screenshot of the current app, not an implementation preview, and not proof of current functionality.

## Project priority

1. **First-class — functional correctness:** every visible control must perform a real, understandable action and the underlying workflow must work end-to-end.
2. **First-class — visual quality:** screens must have deliberate hierarchy, balanced spacing, consistent typography, accessible contrast, meaningful status colors, and polished interaction states. Do not postpone core UI quality until after features are finished.
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
- Map-first composition: a readable map canvas, compact floating controls, clear location recentering, and a draggable incident details sheet.
- Conversation-first messaging: compact incident identity header, differentiated message bubbles, readable sender/time context, and a persistent, keyboard-aware composer.
- Semantic state colors and low-noise surfaces; avoid disabled-looking outlines for active actions.
- Map controls, incident freshness, and location-permission fallbacks that are explicit and honest.
- Role-appropriate incident information and location privacy.
- Consistent typography, icon family, spacing, semantic colors, and accessible touch targets.

## Guardrails

- Do not copy a third-party UI wholesale.
- Do not treat illustrative pins, coordinates, status, or route lines as live data.
- Do not claim visual verification until real emulator/device screenshots have been inspected.
- Do not merge or apply a final style solely because the concept board looks attractive.
- Prefer working, observable functionality over final visual polish; retain enough UI clarity to make that functionality testable.
