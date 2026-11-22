# Admin account suspension: trigger-mediated authorization review (batch 11)

## Scope

Suspension cannot be reviewed only at callable RPC boundaries. PostgreSQL triggers execute implicitly as part of writes and may create records, alter state, invoke privileged helpers, or send realtime/notification signals. This batch maps known migration-defined trigger behavior and adds an exact post-replay trigger/function inventory.

## Known trigger groups and implications

| Trigger path | Side effect / invariant | Suspension review requirement |
|---|---|---|
| Auth user insert → profile creation | Creates a requester profile from Auth-owned identity; ignores client-supplied privileged role metadata | Keep signup behavior unchanged. Do not let public metadata create or reactivate a suspended profile. Profile creation must not overwrite an existing account state during future auth lifecycle events. |
| Profile update guard | Protects role, identity, and duty-status fields | Account-state updates must use a distinct trusted Admin transaction; ordinary self-update must not change suspension state. |
| Incident insert → single-active guard | Serializes/blocks multiple active requester incidents | Preserve this operational invariant. Suspended requesters must be denied by the entry-point authorization path; trigger behavior must not create an incident on their behalf. |
| Incident insert/update → location validation | Requires valid location fields and coordinate/source consistency | Preserve validation; do not confuse location validity with account authorization. |
| Incident insert/update → status audit | Writes incident status history | A suspended actor must not reach the protected write; the audit trigger should record only committed valid transitions and must not be bypassed by a direct table mutation. |
| Incident status transition → claimability cleanup/history | Clears claimability on progress and records status transitions | Do not automatically clear, cancel, or reassign an active incident solely because an account is suspended. Preserve the existing incident lifecycle semantics. |
| Assignment insert → per-responder status seed | Creates responder-specific assignment status | Suspension must prevent assigning a suspended target before the INSERT. The trigger should not silently create operational state for a suspended responder. |
| Assignment insert/delete → membership event | Records assignment membership history | Keep historical audit rows. Do not erase or rewrite prior membership events when suspending a user. |
| Assignment/status/edit/cancellation events → notification inbox | Creates durable notifications in the same transaction | Prevent suspended actors from initiating protected events. Also review recipient filtering so suspended recipients do not receive operational notifications, without deleting their historical inbox rows. |
| Incident message insert → notification/realtime helpers | Emits generic participant notification/broadcast side effects | Guard the message write at its authorized RPC boundary and inspect each trigger helper for caller/recipient state assumptions. A trigger running with elevated rights must not treat the trigger owner as the original actor. |
| Auth profile and audit triggers | Some trigger functions are SECURITY DEFINER and intentionally not client-callable | Do not require client EXECUTE grants. Review their source, fixed search path, data writes, and how actor identity is obtained. |

## Important distinction: trigger actor identity

A trigger function's current_user may reflect the function owner when SECURITY DEFINER is used. It is not a reliable substitute for the initiating authenticated user. Where a trigger needs actor attribution, it must use a trustworthy transaction/session identity established by the trusted entry point and must not accept a client-supplied arbitrary actor ID.

For suspension enforcement, deny the initiating caller before protected mutation. A trigger can be a secondary invariant guard, but should not be the only authorization check for a client-callable write.

## Inventory added

The authorization inventory SQL now emits every non-internal trigger on public tables, including owning table, trigger name, enabled mode, trigger function signature and SECURITY DEFINER flag, function settings, trigger definition, and function source.

The next clean migration replay must confirm the query executes. Review the emitted inventory against this batch and expand the map for any trigger not listed here. Auth-schema triggers and extension-owned triggers are outside the public-table query and require separate treatment if they affect account lifecycle or protected data.

## Required follow-up

1. Classify every trigger function from the clean-replay output, including private-schema functions and SECURITY DEFINER functions.
2. Trace nested function calls and tables modified by each trigger.
3. Verify that direct table writes cannot bypass caller-state checks and invoke side effects as a privileged owner.
4. Add tests proving a suspended user with a still-valid JWT cannot cause incident, assignment, message, notification, location, or profile side effects.
5. Add recipient-side tests so suspension does not result in new operational notification/push delivery to that account.
6. Verify rollback behavior: if a trigger fails, the initiating transaction and its audit/notification side effects roll back together.
7. Preserve historical incident, assignment, status, location, message, and audit records; suspension is not a deletion workflow.

## Validation boundary

This is a source review and inventory enhancement. The trigger catalog output has not yet been observed from a new exact-head CI replay. No trigger, function, table, policy, connected Supabase project, or production record was changed by this review.

## Auth-schema lifecycle triggers (added follow-up)

The clean-replay inventory now separately emits non-internal triggers attached to tables in the `auth` schema. This is intentionally separate from the public-table query: Auth lifecycle hooks may create or mutate application profiles, but they are not ordinary public-table triggers and must not be omitted from the account-state review.

For each Auth trigger, inspect its table, enabled mode, function signature, SECURITY DEFINER flag, function settings/search path, trigger definition, and source. In particular, verify that signup/profile creation cannot overwrite an existing suspension state and that any Auth lifecycle hook does not implicitly reactivate an account. Auth trigger execution must preserve the distinction between the Auth event subject and `current_user` inside a SECURITY DEFINER function.

The new Auth-trigger query is still awaiting an exact-head clean migration replay. It is an inventory addition, not evidence that the Auth trigger paths are safe.
