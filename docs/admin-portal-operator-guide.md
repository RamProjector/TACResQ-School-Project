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

## Account directory pagination

The User Management directory defaults to 25 accounts per page. Administrators can choose 10, 25, 50, or 100 rows to suit the screen size and review task. Changing the page size returns the directory to page one so the displayed range remains valid. The selected page size is a view preference for the current console session; it does not change server-side retention or account records.

The directory still applies search and role filters on the server, and pagination uses the same bounded directory endpoint. The control does not expose account suspension, administrator provisioning, or any additional account mutation.

## Overview workspace shortcuts

The Overview page provides direct shortcuts to User Management, Reports, and Activity Log. These use the same section-navigation handler as the sidebar, so the selected workspace and `adminPage` URL parameter remain synchronized and browser Back/Forward navigation continues to work. The shortcuts do not introduce new privileges or data access; each destination retains its existing authorization and loading behavior.


## Security workspace layout

The Security page heading explains the current administrator-session and authenticator scope, while Reports describes the aggregate measures and reporting period available there. Long authenticator names wrap within the factor inventory on narrow screens; factor-management actions move below the factor details on small viewports to avoid horizontal overflow. The interface remains read-only for account suspension/reactivation until the documented security release gates pass.

## Mobile account directory navigation

On narrow screens, the account directory remains horizontally scrollable so all five data columns stay available without compressing names, email addresses, and role information into unreadable cells. The first Account column stays pinned while the other columns scroll, preserving the identity context for each row. The table's scroll region remains keyboard-focusable; use Tab to reach it and the horizontal scroll controls provided by the browser or trackpad. This is a presentation-only change and does not alter directory queries or account data.


## Settings workspace — personal preferences

The Admin navigation includes a Settings workspace for preferences scoped to the current browser. Interface density (comfortable or compact), reduced motion, high contrast, and contextual-help visibility preview immediately. Select **Save preferences** to persist them in this browser's local storage; the page indicates when edits are unsaved. **Reset defaults** restores the default presentation values. These preferences do not alter account permissions, organization-wide policy, emergency notification delivery, or other users' settings. If browser storage is unavailable, the page reports that the preferences could not be saved.

The Settings page also explains current capability boundaries:
- Privacy notice and data-rights request workflows are not published or available in this Admin workspace. The page does not invent a controller contact, legal retention period, or deletion action.
- Emergency notification preferences are not configurable here. No quiet-hours or suppression control is shown because a verified preference-enforcement service is not connected to the Admin portal.

## Help & About workspace

The Help & About section explains the purpose and limitations of Overview, User Management, Security, Reports, and Activity Log, with direct links to each workspace. It identifies the Admin portal as a privileged administration interface rather than a dispatch console and does not claim an unconfigured support contact or release version.

Contextual question-mark disclosures in Settings work with pointer, keyboard, and touch interaction through native disclosure controls. Their explanatory content is optional and can be hidden using the contextual-help preference. Important capability limitations remain visible in the page content and are not tooltip-only.


### Settings subpages

Settings is divided into URL-backed subpages: **General**, **Accessibility**, **Notifications**, **Security & sessions**, and **Privacy & data**. The selected subpage is represented by the `adminSettings` query parameter and is restored by browser Back/Forward navigation. General and Accessibility expose the working local display preferences. Notifications, Security & sessions, and Privacy & data explain the currently unavailable controls and link to existing verified capabilities where applicable; they do not present inert toggles or promise unsupported data-rights operations.


## Contextual help across core workspaces

Overview dependency reachability, User Management, Reports, and Security include question-mark help disclosures beside their section headings. The disclosures explain important measurement, permission, and coverage boundaries. They use native disclosure behavior, so the same information can be opened by pointer, keyboard, or touch; they are not hover-only tooltips.


## Reviewing an account from User Management

- Use **View details** on a directory row to expand a read-only summary without entering a privileged role-change flow.
- The summary displays only fields currently returned by the protected Admin directory: profile identifier, account role, operational duty status, and account creation time.
- Duty status describes field availability/progress; it is not an account-enabled, sign-in, or suspension state.
- The directory does not currently provide a complete authentication history or account activity timeline. Use Activity Log only for the event sources and coverage explicitly shown there; do not infer that missing events did not occur.
- Use **Change role** separately when a role correction is necessary. The existing confirmation, fresh account read, database authorization, and audit-reason requirements remain in force.


## Activity Log date-range shortcuts

The Activity Log includes **7 days**, **30 days**, and **90 days** quick ranges. Each preset includes the current Manila calendar date and the preceding calendar days, and updates the existing server-side date filters. The selected range is exposed with an accessible pressed state. Manual From/To dates remain available, and Clear filters removes the date range along with the other active filters. These shortcuts do not change the event sources or expand audit coverage.


## Reports refresh and export feedback

The Reports workspace shows when the currently displayed aggregate was retrieved, formatted in Asia/Manila time. Changing the reporting period or refreshing clears the previous retrieval timestamp and export notice while the next response loads, preventing stale metadata from being presented as current. CSV export reports the prepared filename through an announced status message; if the browser cannot prepare the file, the page displays a recoverable error. The export continues to contain only the aggregate data already returned to the page. Browser download completion itself is not verified by the application.


## Security status refresh behavior

Starting a manual or automatic MFA status refresh clears the previously displayed session/factor snapshot and its retrieval timestamp. The workspace shows a checking state until the new Supabase Auth read succeeds, or an explicit unavailable error if it fails. This prevents an older assurance level or factor inventory from remaining visible as though it were the result of the in-progress refresh.

## Security status freshness

The live Security workspace displays the time its Supabase Auth MFA session and authenticator-factor snapshot was successfully retrieved, formatted in Asia/Manila time. Use **Refresh status** before reviewing factor inventory or interpreting the displayed assurance level. A failed refresh clears the prior snapshot and timestamp rather than presenting stale security state as current. Privileged factor actions continue to re-read and verify the current TOTP/AAL2 state immediately before acting.


## Activity Log refresh timestamp

The Activity Log refresh timestamp is cleared when a new filter query begins, when the date range is invalid, or when the current query fails. It is set again only after the active investigation-history query succeeds. This avoids showing the previous query’s retrieval time as though it applied to newly selected filters. The independently paginated account-lifecycle history retains its own loading and error state.


## Overview dependency-check freshness

When an Overview dependency check starts, the previous three-probe snapshot and any prior diagnostic error are cleared. The page shows a checking state until the current probe completes; a failed probe shows an unavailable message instead of retaining an older reachable/unavailable result as though it were current. The timestamp shown beside the dependency summary belongs to the successful probe response. This remains a reachability check only and does not establish emergency-service readiness.


## Mobile Admin navigation

The section navigation remains available on narrow screens as a horizontally scrollable row beneath the Admin brand. Swipe or horizontally scroll the navigation row to reach sections that do not fit on screen, then select the section. The active section is identified visually and with `aria-current="page"`. The mobile layout hides the desktop-only sidebar footer, not the section controls.


## User directory retrieval freshness

When the account directory starts loading a new page or applies a new search/role filter, its previous successful-retrieval timestamp is cleared. The timestamp returns only after the active directory request succeeds, so it is not presented as the retrieval time for a different page or filter. Any expanded read-only account detail panel is also closed when a new directory request begins; review the returned row again after the request completes.


## Account lifecycle history retry

The Activity Log's account suspension/reactivation history is loaded independently from the main investigation feed. If that source fails, use **Retry lifecycle history** in the lifecycle section to request that history again without resetting the main feed's search, date range, category, source, or page. The retry does not create or modify account-status records; it only re-reads the existing protected history endpoint.


## Account lifecycle history refresh behavior

When the lifecycle history page is changed or retried, the previous page's rows and count are cleared while the new protected read is in progress. A successful response repopulates the requested page and its timestamp; a failed response leaves an explicit unavailable state rather than showing rows from the prior request.

## Account lifecycle history freshness

The suspension/reactivation history displays its own last-successful-retrieval time in Asia/Manila. That timestamp is cleared whenever a new lifecycle page load or retry starts, and is restored only after the requested page succeeds. It is independent of the main investigation feed's retrieval timestamp. If the requested page becomes invalid because records were removed, the interface moves to the last valid page and retrieves that page before showing a fresh timestamp.


## Account directory refresh visibility

When a new account-directory request starts, the previous page's rows and total are cleared along with its retrieval timestamp. The directory shows its loading state until the active protected request succeeds or reports an error. This prevents accounts matching an earlier page or filter from being mistaken for the results of the current selection.


## Activity Log filter transition behavior

When an Activity Log filter or page changes, the previous event rows, count, expanded detail, and successful-retrieval timestamp are cleared immediately, before the search debounce and protected request. The loading state covers that transition. This avoids presenting records from the previous filter as though they match the newly selected criteria.


## Security status refresh behavior

Refreshing the current MFA/factor snapshot clears prior action notices and errors and closes any outstanding factor-removal or global-sign-out confirmation prompt before the new Supabase Auth reads begin. Review the refreshed factor inventory and session assurance state before starting a privileged factor action. An in-progress backup enrollment is not silently cancelled by refresh; use its explicit cancellation control when abandoning enrollment.


## Audit reference copy feedback

The copied-reference confirmation and clipboard error message are cleared whenever the investigation query, filters, date range, or page changes. This keeps copy feedback tied to the result set currently displayed rather than implying that an item on a previous page or filter is still available. If clipboard access is blocked, the technical reference remains selectable for manual copying.


## Analytics period changes and refresh

Changing the reporting period or selecting Refresh report immediately clears the previous aggregate values, retrieval timestamp, and CSV export feedback while the new request is pending. Export remains unavailable until the selected period has returned successfully. This avoids presenting the previous period's figures or export notice as belonging to the newly selected reporting window.


## Security action completion feedback

After a backup authenticator is verified, a factor is removed, or a pending enrollment is cancelled, the Security workspace refreshes its Supabase Auth snapshot first and then displays the success notice. The refresh clears older notices by design, so the completion message is intentionally published only after that refresh finishes. This is UI feedback for the completed action; the refreshed snapshot remains the source for current factor status.
