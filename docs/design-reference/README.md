# TACResQ UI design reference pack

Status: **design inspiration only**. The SVG in this folder is an original, locally stored concept board made for TACResQ. It is not a screenshot of the current app, not an implementation preview, and not a claim that the current screens already match it.

## Selected direction

Use a calm, high-contrast emergency-response interface: deep navy for structure, a restrained teal/blue primary color, warm amber for caution, and red only for urgent/high-severity states. Keep the visual language consistent across requester, responder, and dispatcher flows.

### Reference choices and why they fit

1. **Material 3 navigation bar** — the official Android guidance supports 3–5 top-level destinations. TACResQ has five, so keep all five positions stable and recognizable. Do not hide labels merely to conceal a spacing defect. If a custom center Map action is retained, make its hit area, alignment, and selected state deliberate and consistent.
2. **Jetpack Compose adaptive layouts** — adapt to the actual available window size, not device-brand guesses. On compact phones use a bottom navigation bar; on larger windows consider a navigation rail. Re-check insets, touch targets, text wrapping, and the smallest supported width.
3. **Emergency-response task hierarchy** — the most important screen action should be immediately apparent: request help, respond, view a live incident, or update response status. Use status labels and supporting text; never rely on color alone.
4. **Map-first incident tracking** — show the map as the main working surface with a compact, dismissible/expandable incident sheet, readable recenter control, clear current-location affordance, and a persistent route back to the app's destinations. The concept board uses a schematic vector map, not live map tiles or GPS data.

## Files

- `tacresq-ui-inspiration.svg` — original editable vector concept board with Home, Live Map, and Incident Detail screen directions.
- `design-tokens.json` — starter visual tokens for the design direction; not yet wired into the app theme.
- `SOURCE_NOTES.md` — source selection notes and attribution.

## Implementation guardrails

- Treat the board as inspiration, not a mandate to copy every card or decoration.
- Inspect current Compose components before changing them; reuse shared components where appropriate.
- Keep labels and accessibility descriptions meaningful.
- Preserve the five destinations and the emphasized center Map action only if the layout can be made balanced without artificial gaps.
- Verify at compact widths, larger phones, landscape, and with system gesture/navigation insets.
- Do not call a UI change visually verified until real emulator/device screenshots have been inspected.
