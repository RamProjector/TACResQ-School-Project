# Admin Directory Data Exposure and Privacy Review

**Review date:** 2026-11-22  
**Scope:** Admin user directory and analytics response in the current Admin Console branch  
**Status:** Documentation-only review; no schema or production changes

## Executive finding

The Admin Console's user directory is appropriately bounded by server-side pagination and administrator authorization, but each returned row still exposes direct contact information for every profile in the current result page. The current page renders each user's full email address and phone number, while the role-change workflow needs the profile identity, current role, and a reason for the change. The repository does not document why both contact fields must be visible to every administrator for routine role administration.

This is a data-minimization question, not evidence of an authorization bypass. The current paginated RPC checks that the caller's profile has the `admin` role, applies bounded paging and validated filters, and returns only a selected set of profile fields. A later migration wraps the Admin RPCs with an AAL2/TOTP gate. These controls reduce exposure to unauthorized callers, but they do not by themselves establish that every field returned to an authorized administrator is necessary.

## What the current code exposes

The current directory RPC returns, per profile:

- Numeric profile ID
- Full name
- Email address
- Phone number
- Application role
- Field-unit duty status, where applicable
- Profile creation timestamp

The UI displays the full name, email, optional phone number, profile ID, role, duty status, and creation date. Search accepts name, email, phone, and profile ID. The role-change dialog repeats the target's email address.

The directory is global across profiles; the role filter narrows it by role but does not scope it to a region, incident, or organization. Page size in the current UI is 25, while the RPC permits a maximum of 100.

## Purpose and necessity assessment

| Field | Current use | Review recommendation |
|---|---|---|
| Profile ID | Stable target identifier; role-change action | Keep available to administrators; useful for unambiguous selection and investigation |
| Name | Human-readable directory identity and confirmation | Keep |
| Email | Search and role-change confirmation | Retain only if account-support policy requires it; otherwise mask in the list and avoid repeating it in confirmation |
| Phone | Search and visible directory contact detail | Remove from the default directory response/UI unless a documented support or emergency workflow requires administrator access |
| Role | Filter, display, role-change decision | Keep |
| Duty status | Distinguishes responder/driver operational availability | Keep only for field-unit roles; never treat it as account-enabled/suspended status |
| Created timestamp | Account-age/context display | Keep only if administrators use it for a defined governance task; otherwise move to an optional detail view |

The role-change operation itself uses the target profile ID, requested role, and required reason. The current confirmation copy uses name and email, but that UI choice does not prove that the backend operation requires email or phone.

## Analytics data boundary

The Admin analytics RPC returns aggregate incident counts, daily request buckets, and assignment-time aggregates. Its response does not include requester identities, incident narratives, or precise coordinates. Preserve that aggregate-only boundary. Do not add names, phone numbers, emails, incident descriptions, chat content, or exact GPS coordinates to analytics or downloadable aggregate reports.

The status breakdown is a snapshot of each incident's current status at query time, not a historical count of every status transition. Keep that distinction visible in future reports.

## Audit-history privacy and integrity

The current application audit table records role changes only. Its actor identity is currently stored as a generated label based on the actor's numeric profile ID, not a verified human-readable identity snapshot. Do not mistake this for complete administrator activity history or authentication history.

When broader event coverage is added, record the actor, action, target reference, outcome, timestamp, and a concise reason/context where appropriate. Avoid copying email addresses, phone numbers, incident narratives, coordinates, authentication secrets, access tokens, or full request payloads into audit records. For sensitive-data reads, consider recording that a protected resource was accessed without duplicating its contents.

## Recommended sequence

1. **Define an Admin data-access purpose matrix.** For each field and action, document the administrator task that requires it, the minimum role that needs it, and whether access should be default, masked, or explicit.
2. **Reduce default contact exposure.** Unless a documented support workflow requires otherwise, remove phone from the directory RPC and UI. Prefer masked email or a controlled reveal only if account identification genuinely requires it.
3. **Keep search aligned with visible/authorized data.** If phone is removed from the response, remove phone search too; do not retain a hidden contact-discovery capability without a defined purpose.
4. **Separate directory from sensitive account support.** If future support needs require contact details, use a narrowly scoped detail action with an explicit reason and an access event rather than exposing all contact fields in every list row.
5. **Preserve aggregate analytics.** Continue excluding identity, narrative, chat, and precise location fields.
6. **Audit access to sensitive details and exports.** Record who accessed or exported which category of data, when, and why, without copying the sensitive data into the audit record.
7. **Set retention and review rules.** Define retention for audit/security events and periodically review which Admin accounts still need directory and sensitive-detail access.

## Do not implement blindly

Do not remove all email or phone fields from the database, profile model, or non-admin workflows as part of this review. This recommendation concerns the Admin Console's default exposure only. Before changing the RPC contract, inspect all consumers and confirm the operational support policy. Do not add an account suspension flag, contact-detail reveal permission, export feature, or new administrator role without a separate policy and cross-layer authorization design.

## External guidance

- OWASP Authorization Cheat Sheet: least privilege applies to data attributes as well as functions; authorized access should be limited to what the task requires.
- OWASP Logging Cheat Sheet: logs should avoid unnecessary personal information; identifiers such as email and phone may require masking, sanitization, or pseudonymization, and access to logs should be restricted and reviewed.

## Repository evidence reviewed

- `backend/web/src/pages/AdminConsole.tsx`: directory columns, search placeholder, and role-change confirmation.
- `supabase/migrations/20261112215712_admin_server_side_user_pagination.sql`: paginated directory response fields and server-side role check.
- `supabase/migrations/20261122050858_require_totp_for_admin_rpcs.sql`: privileged RPC assurance wrapper and internal function boundary.
- `supabase/migrations/20261122050856_admin_analytics_summary.sql`: aggregate-only analytics response.
- `supabase/migrations/20261112215653_admin_role_audit_foundation.sql`: role-change audit schema and event scope.

This review is based on repository source at the time of writing. It does not establish the state of a live Supabase project, production data, deployed migrations, or operational policy.
