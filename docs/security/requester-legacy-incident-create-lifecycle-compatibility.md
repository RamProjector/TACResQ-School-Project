# Legacy requester incident-create RPC execution boundary

**Scope:** Current-branch Android/Laravel call-site review plus a read-only live PostgreSQL privilege snapshot. The proposed forward migration is branch-only; it has not been applied to the connected Supabase project.

## Signatures and current callers

The historical migrations define three invoker functions:

- `public.api_create_incident(text,text,double precision,double precision,real)`
- `public.api_create_incident_v2(text,text,double precision,double precision,real,text,timestamptz)`
- `public.api_create_incident_v3(text,text,double precision,double precision,real,text,timestamptz,text)`

The current Android Retrofit API (`SupabaseTacApi.kt`) and Laravel bridge allowlist call `api_create_incident_v4` only. Repository search found no current Android or Laravel client call to v1/v2/v3. The current v4 function is SECURITY DEFINER, requires an active requester before idempotency lookup, and delegates the validated insert to v3 under its owner context.

## Why the old grants are not compatibility

The connected read-only catalog reported authenticated and service_role had EXECUTE on v1/v2/v3. However, authenticated had no direct INSERT privilege on `public.incidents`. Since the three legacy functions are SECURITY INVOKER and directly INSERT into that table, their authenticated EXECUTE grants did not provide a functioning client write path. Function EXECUTE and table INSERT are independent privilege gates.

The older functions also predate v4's explicit active-account check. The incident write trigger provides a further active-account enforcement layer, but it does not make an invoker function usable when its caller lacks table INSERT.

## Branch change

Forward migration `20261127010000_revoke_legacy_incident_create_rpc_execute.sql`:

- Revokes all function privileges from PUBLIC, anon, authenticated, and service_role on v1/v2/v3.
- Retains all three signatures; no function is dropped or rewritten.
- Preserves the function owner's implicit EXECUTE privilege.
- Keeps v3 callable internally by SECURITY DEFINER v4; v1 and v2 have no current in-repository call sites.
- Leaves the current v4 grants and behavior unchanged.

The service_role grant is also removed because the current Laravel bridge allowlist exposes v4 only and v4 calls v3 as its owner. Any future server-side use of the legacy signatures must be introduced through a separately reviewed, narrowly scoped API rather than by restoring broad EXECUTE.

## Runtime and source contracts

The clean PostgreSQL replay test verifies:

1. The three historical signatures still exist.
2. anon, authenticated, service_role, and PUBLIC do not have EXECUTE on them.
3. postgres (the function owner in the replay fixture) retains EXECUTE on v3.
4. v4 remains SECURITY DEFINER, remains callable by authenticated, and its body still delegates to v3.
5. Android and Laravel client source continues to call v4 and does not name the legacy signatures.

The existing v4 lifecycle/idempotency tests remain responsible for active-requester, retry, and one-active-incident behavior. These grant tests do not replace a full Android-device emergency workflow.

## Deployment and compatibility boundary

This is a forward-only repository change. No hosted Supabase ACL, migration ledger, user data, Render configuration, or deployment was changed. The connected project remains on the pre-migration grants until the normal approved migration process is run.

The source review establishes the current checked-in client contract, not the exact minimum supported APK version or every historical APK distributed to users. However, the old invoker RPCs already lack the direct authenticated INSERT privilege required to perform their insert. The migration removes misleading callable grants; it does not remove a currently working authenticated write path. Preserve the function signatures and do not drop them as part of this grant cleanup.
