# TACResQ Admin Provisioning Runbook

**Purpose:** trusted-operator-only provisioning and recovery for the Supabase-backed TACResQ Admin role.  
**Status:** documentation for use only after the Admin migration is reviewed, merged, and separately approved for application.  
**Important:** these procedures are not executed by the app or CI. Do not paste production SQL until the target identity and change authorization have been independently verified.

## Preconditions

- Confirm the reviewed Admin migration is applied to the intended Supabase project and that the schema includes `profiles.role = 'admin'` and `admin_audit_logs`.
- Verify the person through an approved out-of-band process; do not identify the account by matching a Laravel email address.
- Locate the existing Supabase Auth user UUID and its linked `public.profiles.auth_user_id`. Do not create credentials or use a shared/default password.
- Use a verified email-backed account for initial provisioning; the SQL below checks `auth.users.email_confirmed_at`.
- Record the operator identity and change/ticket reference. Run only one procedure at a time in a transaction.
- Use a dedicated, least-privilege project-owner/operator workflow; never expose a service-role key in the app, browser, repository, logs, or this runbook.

## Initial Admin provisioning

Replace the UUID, operator identity, and change reference before running. The zero UUID and `REPLACE_...` values intentionally fail validation if left unchanged.

```sql
BEGIN;
LOCK TABLE public.profiles IN SHARE ROW EXCLUSIVE MODE;

DO $provision_admin$
DECLARE
  target_auth_user_id uuid := '00000000-0000-0000-0000-000000000000';
  operator_identity text := 'REPLACE_WITH_OPERATOR_IDENTITY';
  change_reference text := 'REPLACE_WITH_APPROVED_CHANGE_REFERENCE';
  target_profile_id bigint;
  old_role text;
  changed_rows integer;
BEGIN
  IF target_auth_user_id = '00000000-0000-0000-0000-000000000000'
     OR operator_identity LIKE 'REPLACE_%'
     OR change_reference LIKE 'REPLACE_%' THEN
    RAISE EXCEPTION 'Replace the target UUID, operator identity, and approved change reference';
  END IF;

  IF EXISTS (SELECT 1 FROM public.profiles WHERE role = 'admin') THEN
    RAISE EXCEPTION 'An Admin already exists; use the reviewed Admin rotation procedure';
  END IF;

  SELECT p.id, p.role
    INTO target_profile_id, old_role
  FROM public.profiles p
  JOIN auth.users u ON u.id = p.auth_user_id
  WHERE p.auth_user_id = target_auth_user_id
    AND u.email_confirmed_at IS NOT NULL
  FOR UPDATE OF p;

  IF target_profile_id IS NULL THEN
    RAISE EXCEPTION 'Target must be an existing profile linked to a verified Supabase Auth user';
  END IF;

  IF old_role = 'admin' THEN
    RAISE EXCEPTION 'Target is already an Admin';
  END IF;

  UPDATE public.profiles
  SET role = 'admin', duty_status = NULL, updated_at = now()
  WHERE id = target_profile_id;
  GET DIAGNOSTICS changed_rows = ROW_COUNT;

  IF changed_rows <> 1 THEN
    RAISE EXCEPTION 'Expected exactly one profile to be provisioned; changed %', changed_rows;
  END IF;

  INSERT INTO public.admin_audit_logs (
    actor_user_id, actor_identity, target_user_id, action, old_role, new_role, reason
  ) VALUES (
    NULL, operator_identity, target_profile_id, 'user_role_changed', old_role, 'admin',
    format('Initial trusted-operator provisioning by %s; reference: %s',
           operator_identity, change_reference)
  );
END;
$provision_admin$;

COMMIT;
```

After provisioning, sign in through the Admin console and verify that the user list and audit log load. Do not grant Admin by editing user metadata or adding a public bootstrap endpoint.

## Admin rotation or demotion

The app's Admin RPC deliberately cannot promote, demote, or edit Admin accounts. For a planned rotation, first provision and verify a second Admin through the approved operator procedure. Only then demote the outgoing Admin.

Before running a demotion, confirm there are **at least two** Admin profiles and replace the placeholders below. The procedure locks the profile table, refuses to demote the last Admin, refuses an unverified/non-Admin target, refuses active incident assignments, and writes a corresponding audit event in the same transaction.

```sql
BEGIN;
LOCK TABLE public.profiles IN SHARE ROW EXCLUSIVE MODE;

DO $demote_admin$
DECLARE
  target_auth_user_id uuid := '00000000-0000-0000-0000-000000000000';
  target_role text := 'dispatcher';
  operator_identity text := 'REPLACE_WITH_OPERATOR_IDENTITY';
  change_reference text := 'REPLACE_WITH_APPROVED_CHANGE_REFERENCE';
  target_profile_id bigint;
  old_role text;
  admin_count integer;
  changed_rows integer;
BEGIN
  IF target_auth_user_id = '00000000-0000-0000-0000-000000000000'
     OR operator_identity LIKE 'REPLACE_%'
     OR change_reference LIKE 'REPLACE_%' THEN
    RAISE EXCEPTION 'Replace the target UUID, operator identity, and approved change reference';
  END IF;
  IF target_role NOT IN ('requester', 'responder', 'driver', 'dispatcher') THEN
    RAISE EXCEPTION 'Target role must be an ordinary TACResQ role';
  END IF;

  SELECT count(*) INTO admin_count FROM public.profiles WHERE role = 'admin';
  IF admin_count <= 1 THEN
    RAISE EXCEPTION 'Refusing to demote the last Admin';
  END IF;

  SELECT p.id, p.role INTO target_profile_id, old_role
  FROM public.profiles p
  JOIN auth.users u ON u.id = p.auth_user_id
  WHERE p.auth_user_id = target_auth_user_id
    AND u.email_confirmed_at IS NOT NULL
  FOR UPDATE OF p;

  IF target_profile_id IS NULL OR old_role <> 'admin' THEN
    RAISE EXCEPTION 'Target must be a verified existing Admin profile';
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.incidents i
    WHERE i.assigned_to = target_profile_id
      AND i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) OR EXISTS (
    SELECT 1
    FROM public.incident_assignments ia
    JOIN public.incidents i ON i.id = ia.incident_id
    WHERE ia.user_id = target_profile_id
      AND i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) THEN
    RAISE EXCEPTION 'Resolve or reassign active incident work before demotion';
  END IF;

  UPDATE public.profiles
  SET role = target_role,
      duty_status = CASE WHEN target_role IN ('responder', 'driver') THEN 'off_duty' ELSE NULL END,
      updated_at = now()
  WHERE id = target_profile_id AND role = 'admin';
  GET DIAGNOSTICS changed_rows = ROW_COUNT;

  IF changed_rows <> 1 THEN
    RAISE EXCEPTION 'Expected exactly one Admin profile to be demoted; changed %', changed_rows;
  END IF;

  INSERT INTO public.admin_audit_logs (
    actor_user_id, actor_identity, target_user_id, action, old_role, new_role, reason
  ) VALUES (
    NULL, operator_identity, target_profile_id, 'user_role_changed', 'admin', target_role,
    format('Trusted-operator Admin rotation by %s; reference: %s',
           operator_identity, change_reference)
  );
END;
$demote_admin$;

COMMIT;
```

## Verification and rollback

- Verify the target profile role and duty status after the transaction.
- Verify the matching audit row exists with the expected old/new roles and operator/change reference.
- Confirm a non-Admin account is denied by `api_admin_list_users()` and `api_admin_list_audit_logs(100)`.
- If a statement fails, do not continue in an aborted transaction. Roll back, investigate the error, and repeat only after review.
- Do not delete the audit event to hide an incorrect change. Correct the role through a separately approved transaction and record the correction.
- This runbook does not provision Laravel Sanctum roles; the Laravel and Supabase identity stores remain separate.
