# Admin feature consolidation

## Structure

- **Overview** remains the operational entry point for account summaries and on-demand dependency reachability.
- **User management** remains separate because role and account-lifecycle changes are privileged, reasoned actions.
- **Security** is the only home for authenticator enrollment, AAL2/session assurance, and security review. Remove the duplicate, informational Security & sessions entry from Settings.
- **Reports** remains aggregate, date-bounded incident metrics and CSV export.
- **Activity log** remains the searchable, source-bounded event investigation feed. Do not merge it into Reports: aggregate reporting and event-level investigation answer different questions and have different data boundaries.
- **Data & continuity** combines Backup & recovery and Attachment safeguards as two internal views. Both are evidence/readiness workspaces; neither is a live backup controller or media browser.
- **Settings** remains browser-local display/accessibility preferences plus informational notification and privacy guidance.
- **Help & about** remains searchable operator guidance.

## Consolidation rules

1. Keep one top-level navigation item for related evidence/readiness functions; use compact internal tabs for their distinct views.
2. Preserve existing page content, permission boundaries, read-only warnings, and source limitations.
3. Preserve legacy deep links when a section moves. The previous `adminPage=attachments` route opens the Attachment safeguards view inside Data & continuity.
4. Do not create a UI control for a backend capability that is not implemented and verified.
5. Keep Reports and Activity Log separate; do not mix current-status aggregates with event history or imply that audit coverage is complete.
6. Keep privileged user lifecycle and security actions in their dedicated workspaces.
7. Use consistent navigation, focus handling, loading/error states, responsive overflow, and reduced-motion behavior.

## Follow-on review

- Review Overview cards and shortcuts for duplicate information already present in the destination workspaces.
- Standardized refresh controls and freshness labels across dependency diagnostics, Reports, Security authentication preview, and both Activity Log feeds. Each control remains scoped to its own dataset; loading, unavailable, and last-retrieved states are explicit.
- Review each page's loading, error, empty, and stale-data states.
- Verify navigation and legacy URLs with browser Back/Forward, keyboard-only operation, and narrow viewport widths.
- Reconcile Help text and operator documentation with the consolidated navigation.
- Run frontend type/build checks and the existing Admin regression/security checks before merge.

## Guardrails

No database schema, RPC authorization, production data, Storage policy, backup job, or notification-delivery behavior is changed by this UI consolidation. Do not deploy or merge without explicit approval.
