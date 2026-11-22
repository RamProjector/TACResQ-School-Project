# Account registration and profile authorization review

**Scope:** Source review of Auth signup profile creation, profile RLS/update guard, Admin role changes, and role-resolution helper. No live Auth configuration or database catalog was queried.

## Registration boundary

- The Auth-user insert trigger creates a profile from Auth-owned `NEW.id` and `NEW.email`; the profile's `auth_user_id` is not taken from user metadata.
- Public signup always inserts role `requester` and null duty status. A client-supplied role in raw user metadata is ignored.
- Name and phone are required and bounded; phone input is constrained to common ASCII separators/digits and 7–15 digits after normalization.
- The trigger is SECURITY DEFINER with `search_path = pg_catalog`, schema-qualified table access, and no client EXECUTE grant. It runs as a trigger, not a client-callable RPC.
- The insert uses `ON CONFLICT (auth_user_id) DO NOTHING`, avoiding duplicate profile creation for an existing Auth identity.

## Profile update boundary

- The direct profile UPDATE policy is limited to the caller's own row and only while the existing role is responder or driver. Requesters cannot directly update profile rows through this policy.
- The profile guard rejects changes to profile ID, Auth user ID, creation timestamp, name, email, and phone through the application API.
- Direct role changes are rejected unless the actor is an Admin; even Admin cannot promote or demote an Admin account through the application API.
- Duty status is constrained to the allowed field-unit values and role-compatible nullability. Role/duty changes are blocked while the target is attached to an active incident.
- The existing profile SELECT policy exposes a caller's own profile and dispatcher-visible profile rows. Admin directory access is through a separate Admin RPC.

## Admin role changes

- The Admin role is not granted through public signup.
- `api_admin_set_user_role` accepts only requester, responder, driver, or dispatcher. Admin creation/promotion/demotion remains trusted-operator provisioning.
- The target row is locked before the role change. Active incident assignments block role changes. A reason is required and the role change plus audit insert occur in the same transaction.
- The Admin directory RPC returns name, email, phone, role, duty status, and creation time; it does not return Auth tokens, password hashes, or raw metadata. Phone/email are personal data and should remain limited to the authorized Admin use case; avoid adding them to exports or general analytics.

## Hardening change

- The role-resolution helper `public.tacresq_actor_role()` is SECURITY DEFINER and is used by RLS policy expressions. Its original search path included `public, auth, pg_temp`, despite schema-qualified references to `public.profiles` and `auth.uid()`.
- A forward migration now pins its search path to `pg_catalog`, minimizing object-resolution risk without changing its result, signature, or authenticated execution grant.
- The change is source-only until the migration is applied through the approved deployment process.

## Remaining review items

1. Verify live grants, RLS policies, Auth trigger attachment, and function definitions against the migration history before deployment.
2. Verify all Auth providers and signup settings are intentional; the source trigger requires email and profile name/phone, but provider-side confirmation and rate-limit settings are not established by this review.
3. Decide whether Admin user management genuinely needs phone numbers on the default list. If retained, keep access Admin-only and avoid CSV/export by default.
4. Add negative integration cases for forged role metadata, profile ID/Auth ID edits, requester profile updates, cross-user role changes, Admin self-promotion/demotion, and role changes during active assignments.
5. Review account suspension as a separate lifecycle capability. Duty status is not an enabled/disabled account flag; do not repurpose it.

## Evidence boundaries

Reviewed migrations: Admin role/audit foundation, requester signup contact validation and length bounds, profile identity metadata guard, and the original profile RLS definitions. No Auth user data, secrets, profile data, or live database records were accessed. No SQL was executed and no migration was applied.
