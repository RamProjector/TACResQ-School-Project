# TACRU mobile GPS map entry point

## Recommendation

Use a **prominent, center-docked “GPS Map” button** above the bottom navigation bar. It should open a dedicated, full-screen map destination and remain in the same place for requester, responder/driver, and dispatcher accounts. Keep the existing role-specific navigation items to either side of it.

Treat the map as a top-level destination, not as a generic action menu. Apple's Human Interface Guidelines describe tab bars as navigation between top-level app sections and recommend using toolbars for actions on the current view [1]. Material Design 3 describes a floating action button as a way to expose a screen's primary action [2]. For TACRU, a raised center button is a useful visual shortcut to the map, while its persistent placement and clear “GPS Map” label communicate that it opens a destination.

## Placement and visual hierarchy

- Center the button on the device's horizontal axis and dock it just above or into a bottom app bar, with a visible gap from scrollable content and Android gesture/navigation insets.
- Use an icon plus the explicit label **GPS Map**. Do not rely on a location-pin glyph alone; emergency users should not need to infer what the icon does.
- Give it a larger visual weight than the ordinary navigation items: a filled high-contrast surface, raised/elevated treatment, and a selected state while the map is open. Keep the treatment consistent across roles.
- Preserve touch accessibility with a minimum 48 dp target, a descriptive accessibility label such as “Open full-screen GPS map,” visible keyboard/focus state, and sufficient color contrast.
- Keep it fixed while lists scroll. Do not animate or pulse continuously; reserve a brief attention cue for meaningful changes such as a new assignment or GPS unavailable state.
- Make the map screen genuinely full-screen within the app, with a clear back/close control, role-appropriate title, and enough inset for system status/navigation bars.

A center-docked FAB is a recognized mobile pattern, but it should not obscure content or be confused with the SOS action. TACRU should keep **Review and send SOS** as the distinct emergency action on the requester home screen; the centered map button only opens the map.

## Navigation layout by role

Keep the same center control and adapt the surrounding destinations to the user's role:

- **Requester:** Home | My Requests | **GPS Map** | Profile. The map should focus on the requester's current location when permission is granted, show the active incident and response status when available, and make location-sharing state and its last update explicit. Do not imply continuous sharing until that feature is implemented.
- **Responder/driver:** Current Call | History | **GPS Map** | Profile. The map should focus on the assigned incident and responder location only when each coordinate is actually available. Keep “Open navigation” as an external turn-by-turn fallback. Clearly mark unavailable or stale responder GPS rather than showing a fabricated live position.
- **Dispatcher:** Queue | Field Units | **GPS Map** | Activity. Show incident markers from available incident coordinates, unit markers only when live unit coordinates exist, and visible freshness/offline indicators. Retain assignment confirmation in the queue rather than moving that action onto the map button.

On narrow screens, the center control can overlap the bar's top edge, but labels for the surrounding destinations must remain visible and tappable. On wider layouts, keep “GPS Map” as a normal, clearly selected navigation item or a center-docked control aligned with the navigation rail/content; avoid a floating center button detached from the navigation structure.

## Implementation boundary in this repository

The checked-in project is an Android source pack, not a complete Gradle application. Its existing requester, responder, and dispatcher screens are single scrollable screens and have no tab shell or embedded map. It currently captures one GPS fix with a requester SOS and opens Google Maps for responder navigation. Its README explicitly defers Maps SDK screens, continuous/background tracking, and dispatcher fleet visualization.

The shared center-docked navigation shell and role-aware full-screen GPS Map destination are now implemented in the Android source pack. The current map surface deliberately shows confirmed incident coordinates and recorded timestamps only; it does not imply continuous requester or responder tracking that the app/backend do not yet provide. The remaining production stages are:

1. Replace the coordinate surface with an in-app map provider while preserving confirmed-coordinate and freshness metadata.
2. Add the requester and responder location-update lifecycle, authorization/consent, and stale/offline handling before presenting live tracking.
3. Add dispatcher unit markers only after the backend returns responder location data with a timestamp.

## Research references

[1]: https://developer.apple.com/design/human-interface-guidelines/tab-bars "Apple Human Interface Guidelines: Tab bars"
[2]: https://m3.material.io/components/floating-action-button/guidelines "Material Design 3: Floating action button guidelines"
