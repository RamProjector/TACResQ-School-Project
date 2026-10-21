# TACResQ UI research — comparative findings before choosing a final direction

Status: **research phase; do not treat the current SVG concept board as the final design decision.** This document deliberately keeps multiple viable patterns open until we compare them against TACResQ's actual requirements, code, device sizes, and screenshots.

Research checked: 21 October 2026.

## Research question

What proven Android navigation, location-sharing, and emergency-response patterns can be adapted to TACResQ without blindly copying a generic template or implying that a concept drawing is an actual app screen?

## Evidence matrix

| Reference | What it actually demonstrates | Useful for TACResQ | What not to copy blindly |
|---|---|---|---|
| Android Developers — Layout and navigation patterns | Primary navigation uses a bar for 3–5 same-level destinations; larger windows should use a more appropriate navigation pattern such as a rail. | Decide whether the five destinations are truly peers; define stable placement, labels, selected state, and adaptive behavior. | A custom center-emphasized Map action is not automatically the same as a standard navigation destination. |
| Android Developers — Adaptive navigation / Jetpack Compose | Navigation UI should respond to the app window's available size and posture, not just device model. | Replace arbitrary width checks with supported window-size/adaptive patterns where appropriate; test compact width and landscape/large windows. | Don't introduce adaptive dependencies before checking the existing project's Compose/library versions. |
| Material Components — Navigation bar | Navigation destinations are represented with icons and labels; the component includes selected indicators and optional badges. | Keep all five positions equal and labels consistently visible as the default option; avoid unexplained empty slots. | Do not invent a custom layout that looks like a broken standard navigation bar. |
| PulsePoint Respond — official feature descriptions | Emergency incidents are filtered by responder type; professional users have richer incident details and response maps; incident map can open into a full-screen response map. | Treat requester, public responder, and official dispatcher as different information-access roles; make map and incident details connected, task-oriented views. | PulsePoint depends on participating public-safety agency integration and its US operating context. It is not proof that TACResQ can dispatch real emergency services. |
| Google Maps — Location Sharing help | Location sharing is explicit, has recipient/visibility controls, and can be stopped; location updates and ETA are distinct from a static pin. | Make sharing state, recipient/authorization, freshness, and stop-sharing behavior clear. Separate current position from a submitted emergency location. | Do not expose live personal locations to every app user or assume map permission means background tracking is authorized. |
| Android Developers — Runtime location permissions | Users can grant approximate rather than precise location; request permissions in context and handle denial/degradation. | Explain why location is needed, handle approximate permission, denied permission, disabled location services, stale fixes, and retry/manual pin placement. | Never block the entire app simply because precise location is denied unless a specific action truly requires it. |

## Patterns to explore in parallel

### A. Navigation — keep three variants open

1. **Standard Material 3 navigation bar:** five equally weighted destinations, persistent icon + label, standard selected indicator. Lowest surprise and easiest to maintain.
2. **Standard bar plus a separate Map FAB/action:** the four or five primary destinations stay standard; Map is a clearly separate, prominent action only if product information architecture justifies making it an action rather than a peer destination. Must test that it doesn't collide with the bar or gesture inset.
3. **Custom center-emphasized Map destination:** retain the product's desired centered Map emphasis but prototype it against equal-width grid guides, with all five labels and no hidden items. Only keep if it reads as intentional and remains accessible at the narrowest supported width.

Do not hide inactive labels as the first-line fix for spacing. First measure available content width, item widths, icon bounds, text widths, safe drawing insets, and the center control's footprint.

### B. Map and location — compare three screen compositions

1. **Full-screen map + compact collapsed incident sheet:** strongest spatial awareness; sheet should expand for incident details and collapse without obscuring critical map controls.
2. **Map with a persistent top status/search strip + bottom details sheet:** gives the user a clear incident count/status and a stable entry point for map actions.
3. **Incident list first, map as a synchronized view:** easier scanning and accessibility for multiple incidents; map remains a deliberate destination rather than the default screen.

For each, show a distinct current-location marker, incident markers with text/icon distinctions (not color alone), recenter control, location accuracy/loading state, last-updated/freshness, and an understandable permission fallback. Never use illustrative mock coordinates or fake live status as if real.

### C. Home and incident detail

Compare a task-first Home (primary request-help action, active request summary, nearby incidents where permitted, preparedness shortcuts) with a status-first Home (active request/response state at the top). Keep the main emergency action obvious, but don't use a giant alert-colored panel for ordinary, non-urgent states.

Incident detail should make the status timeline and next allowed action easy to scan. Public users should see only authorized/safe information; dispatcher views can be denser when their operational role warrants it.

### D. Visual language

Evaluate a restrained light surface palette, strong text contrast, clear typography hierarchy, consistent icon family, compact but generous spacing, and color used semantically. Reserve red for actual urgent/error states; use amber for caution and neutral/blue/teal for normal activity. Add dark-theme support only after checking the app's existing theme architecture.

## Concrete evaluation checklist

Score each prototype (1–5) on:
- Navigation clarity and stable labels
- No unexplained gaps or unequal baselines
- Fit at narrowest supported device width
- One-handed reachability and minimum 48dp touch targets
- Readability at system font scaling
- System-bar/gesture inset correctness
- Emergency action clarity and status legibility
- Map usability without fake GPS assumptions
- Permission-denied / approximate / stale-location fallback
- Role-appropriate privacy and incident details
- Reuse of existing Compose components and maintainability

A design should not be selected by aesthetic preference alone. The scorecard should be completed against real Compose screenshots or a running emulator after the options have been implemented.

## Research-derived preliminary recommendation (not final)

Start with standard Material navigation as the baseline and compare it with the product's centered Map treatment. Keep all five labels visible in the first comparison. The full-screen map should be tested separately with an expandable incident sheet. Choose only after the actual `RoleShell.kt`, screen content, and device screenshots have been reviewed.

## Sources (selected, with purpose)

1. Android Developers — Layouts and navigation patterns: https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns
2. Android Developers — Build adaptive navigation: https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation
3. Google Codelabs — Build adaptive apps with Jetpack Compose: https://codelabs.developers.google.com/jetpack-compose-adaptability
4. Material Components for Android — Navigation bar anatomy and behavior: https://github.com/material-components/material-components-android/blob/master/docs/components/BottomNavigation.md
5. PulsePoint — Responder types and features: https://www.pulsepoint.org/responder-types-and-features
6. PulsePoint — Download / deployment context: https://www.pulsepoint.org/download
7. Google Maps Help — Share real-time location on Android: https://support.google.com/maps/answer/15437054?co=GENIE.Platform%3DAndroid&hl=en
8. Android Developers — Request location access at runtime: https://developer.android.com/develop/sensors-and-location/location/permissions/runtime

## Limitations

- This is desk research, not a usability study with TACResQ users.
- Feature descriptions are not a substitute for inspecting the actual production apps on a device.
- The current SVG is an original concept board, not a downloaded third-party screen, not the actual app, and not proof of implemented functionality.
- No final style, navigation layout, or map composition is locked by this document.
