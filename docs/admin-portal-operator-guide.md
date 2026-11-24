# TACResQ Admin Portal

## Available sections

- **Overview:** paginated account summary and reachability checks for the configured Laravel, Supabase Auth, and database probes. Reachability is not an end-to-end emergency-readiness check.
- **User management:** server-side account search and role filtering, paginated results, and reason-required role changes. Admin accounts remain operator-managed and cannot be promoted or demoted from this page.
- **Security:** administrator TOTP/AAL2 controls and security guidance. A branch implementation is not proof that a production migration or setting has been deployed.
- **Reports:** aggregate incident request counts, current status breakdown, daily request volume, and average/median time to first assignment for 7-, 30-, or 90-day windows.
- **Activity log:** read-only, paginated investigation history plus a separate bounded account lifecycle history section.

## Analytics CSV export

The Reports section can export the currently loaded aggregate report as CSV. The export includes:
- Reporting timezone and period boundaries
- Total, pending, active, resolved, and cancelled request counts
- Average and median time to first assignment
- Count of requests with a valid assignment time
- Daily request totals

The export intentionally excludes requester names, phone numbers, descriptions, incident coordinates, responder identities, and raw audit details. It exports only the same aggregate summary already returned to the Reports page; it does not issue a second query or expand the caller's data access.

## Account lifecycle safety boundary

The database transition function for suspension/reactivation remains revoked from authenticated, anon, and PUBLIC until the remaining callable, Storage, Edge Function, Laravel bridge, and same-valid-JWT regression coverage is complete. The Admin UI must not offer Suspend/Reactivate actions before that release gate is satisfied. The read-only lifecycle audit history is not evidence that account transitions are enabled.

## Operational verification

- Configure the existing Laravel runtime or the web app's VITE_SUPABASE_URL and VITE_SUPABASE_PUBLISHABLE_KEY values.
- Sign in with a linked TACResQ Admin profile and complete the required authenticator/TOTP step.
- Verify each page against a non-production environment with the matching database migrations applied.
- Confirm the Admin reports page displays an explicit unavailable state if the aggregate RPC is missing or inaccessible; never substitute sample data.
- Run the web build and Python source-contract tests in CI. Browser layout, keyboard, and screen-reader review remain separate checks.

## Audit-history filter safeguards

- Date filters use inclusive calendar dates in Asia/Manila (UTC+08:00).
- The client validates both date syntax and actual calendar validity before requesting records.
- A start date later than the end date is rejected locally; the audit query is not sent and the page shows the validation error.
- Clearing filters resets the date boundaries along with category, source, and search terms.
- The activity feed remains read-only. Filtering does not widen the protected RPC's authorization scope.

## Accessibility refinements

- Keyboard users receive a visible, high-contrast focus outline across Admin links, buttons, and form controls.
- Reduced-motion preferences suppress decorative animation and transition effects in the Admin shell.
- These CSS safeguards complement, but do not replace, manual keyboard and assistive-technology review.


## Security and reporting interface refinements

- The backup-authenticator manual setup key is masked by default. Reveal it only when needed, keep it private, and close the enrollment flow when finished.
- The backup authenticator enrollment screen still requires a six-digit verification code before the factor is considered verified.
- The daily request chart now has an expandable data table with exact date and count values for users who need precise figures or cannot use the visual chart.
- The Security and Reports sections continue to use the existing Supabase Auth and protected aggregate-report interfaces; these UI refinements do not establish deployment health or broaden data access.
