# Admin Website Usability Plan

## Goal
Improve the TACResQ administrator website's information density, audit visibility, navigation behavior, and interaction feedback without changing authorization or backend contracts.

## Findings and scope
- Documentation links in operational guidance need concise context before users leave the portal.
- Audit coverage explanations are useful but should not dominate the page.
- Notification-read activity should be visible without presenting a page-local count as a global total.
- The desktop sidebar should minimize to an icon rail and restore smoothly.
- Page headings and helper copy should use space only when they add decision-making value.

## Implementation
1. Replace bare operational documentation links with compact, keyboard-accessible expandable disclosures containing a short explanation and the destination link.
2. Show notification-read event counts for the currently displayed audit page only. Keep the scope explicit; do not label it as a date-range or lifetime total.
3. Move detailed audit coverage and limitations into a closed-by-default disclosure while retaining access to the full explanation.
4. Reduce redundant audit heading copy and keep the page title concise.
5. Animate desktop sidebar minimization and restoration, retaining icon navigation and accessible labels/tooltips in the minimized state.
6. Respect `prefers-reduced-motion`; avoid motion that delays access to controls.
7. Keep loading, error, empty, and refresh states understandable and avoid nested panels or oversized explanatory banners.

## Acceptance criteria
- Operational references can be understood before opening their repository documents.
- Notification counts are derived from returned audit events and clearly labeled as current-page counts.
- Detailed audit coverage remains available but is collapsed by default.
- Sidebar collapse/expand is reversible, keyboard accessible, and does not hide navigation controls.
- Reduced-motion preferences disable decorative transitions.
- No database schema, RPC authorization, role, or production data changes are introduced.
- Existing frontend, security, migration, and regression checks remain enabled.

## Validation
- Run frontend type/build checks and repository CI.
- Review the rendered audit page and admin navigation at desktop and mobile widths.
- Verify the count against the page's actual event rows and confirm pagination changes the page-local count.
- Verify keyboard operation, focus visibility, and reduced-motion behavior.
- Review the final diff for regressions and unrelated changes.
