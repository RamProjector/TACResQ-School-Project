#!/usr/bin/env bash
set -euo pipefail

: "${PGHOST:?PGHOST is required}"
: "${PGPORT:?PGPORT is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${PGDATABASE:?PGDATABASE is required}"

psql -v ON_ERROR_STOP=1 <<'SQL'
CREATE ROLE anon NOLOGIN;
CREATE ROLE authenticated NOLOGIN;
CREATE ROLE service_role NOLOGIN BYPASSRLS;
CREATE SCHEMA auth;
CREATE TABLE auth.users (
  id uuid PRIMARY KEY,
  email text,
  phone text,
  raw_user_meta_data jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION auth.uid()
RETURNS uuid
LANGUAGE sql
STABLE
AS $auth_uid$
  SELECT nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$auth_uid$;
SQL

for migration in supabase/migrations/*.sql; do
  echo "Applying ${migration}"
  psql -v ON_ERROR_STOP=1 -f "${migration}"
done

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'incidents'
      AND column_name = 'location_source'
      AND is_nullable = 'NO'
  ) THEN
    RAISE EXCEPTION 'location_source column missing or nullable after replay';
  END IF;

  IF to_regclass('public.fcm_tokens') IS NULL THEN
    RAISE EXCEPTION 'fcm_tokens table missing after replay';
  END IF;

  IF to_regprocedure('public.api_create_incident_v3(text,text,double precision,double precision,real,text,timestamp with time zone,text)') IS NULL THEN
    RAISE EXCEPTION 'api_create_incident_v3 missing after replay';
  END IF;

  IF to_regprocedure('public.api_register_fcm_token(text)') IS NULL
     OR to_regprocedure('public.api_unregister_fcm_token(text)') IS NULL THEN
    RAISE EXCEPTION 'FCM token RPCs missing after replay';
  END IF;

  IF to_regclass('public.incident_assignments') IS NULL THEN
    RAISE EXCEPTION 'incident_assignments table missing after replay';
  END IF;
  IF to_regprocedure('public.api_assign_incident_team(bigint,bigint[])') IS NULL THEN
    RAISE EXCEPTION 'team assignment RPC missing after replay';
  END IF;
END;
$$;


DO $dispatch_test$
DECLARE
  public_rpc_definer boolean;
  private_rpc_definer boolean;
  private_rpc_settings text[];
BEGIN
  SELECT prosecdef INTO public_rpc_definer
    FROM pg_proc WHERE oid = to_regprocedure('public.api_assign_incident_team(bigint,bigint[])');
  SELECT prosecdef, proconfig INTO private_rpc_definer, private_rpc_settings
    FROM pg_proc WHERE oid = to_regprocedure('private.tacresq_assign_incident_team(bigint,bigint[])');
  IF public_rpc_definer IS DISTINCT FROM false THEN
    RAISE EXCEPTION 'Public team-assignment RPC must be SECURITY INVOKER';
  END IF;
  IF private_rpc_definer IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Private team-assignment implementation must retain SECURITY DEFINER';
  END IF;
  IF NOT (coalesce(private_rpc_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Private team-assignment implementation must pin search_path to pg_catalog';
  END IF;
END;
$dispatch_test$;

-- Regression test for the failure seen in the Android recording:
-- creating an incident must also create its first status log without an FK error.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000123',
  'sos-regression@example.test',
  '{"name":"SOS Regression","role":"requester"}'::jsonb
);

DO $$
DECLARE
  requester_profile_id bigint;
  created_incident_id bigint;
  status_log_count integer;
BEGIN
  SELECT id INTO requester_profile_id
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000123';

  IF requester_profile_id IS NULL THEN
    RAISE EXCEPTION 'Signup trigger did not create the regression requester profile';
  END IF;

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_notes, location_recorded_at, location_source
  ) VALUES (
    requester_profile_id, 'medical', 'Automated SOS audit regression',
    11.244, 125.003, 'Synthetic CI location', now(), 'manual'
  )
  RETURNING id INTO created_incident_id;

  SELECT count(*) INTO status_log_count
    FROM public.incident_status_logs
   WHERE incident_id = created_incident_id
     AND status = 'pending'
     AND changed_by = requester_profile_id;

  IF status_log_count <> 1 THEN
    RAISE EXCEPTION 'Expected exactly one initial status log for incident %, found %',
      created_incident_id, status_log_count;
  END IF;
END;
$$;


-- End-to-end SQL regression: team members advance independently, the aggregate
-- stays en_route until all units arrive, and GPS is rejected for an arrived unit.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000124', 'dispatch-regression@example.test', '{"name":"Dispatch Regression"}'),
  ('00000000-0000-4000-8000-000000000127', 'per-responder-requester@example.test', '{"name":"Per Responder Requester"}'),
  ('00000000-0000-4000-8000-000000000125', 'responder-one@example.test', '{"name":"Responder One"}'),
  ('00000000-0000-4000-8000-000000000126', 'responder-two@example.test', '{"name":"Responder Two"}');

DO $per_responder_test$
DECLARE
  requester_id bigint;
  dispatcher_id bigint;
  responder_one_id bigint;
  responder_two_id bigint;
  incident_id bigint;
  response jsonb;
  other_status text;
  aggregate_status text;
BEGIN
  SELECT id INTO requester_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000127';
  SELECT id INTO dispatcher_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000124';
  SELECT id INTO responder_one_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000125';
  SELECT id INTO responder_two_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000126';

  UPDATE public.profiles SET role = 'dispatcher', duty_status = NULL WHERE id = dispatcher_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available' WHERE id = responder_one_id;
  UPDATE public.profiles SET role = 'driver', duty_status = 'available' WHERE id = responder_two_id;

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude, location_recorded_at, location_source
  )
  VALUES (requester_id, 'medical', 'Synthetic per-responder status test', 11.244, 125.003, now(), 'manual')
  RETURNING id INTO incident_id;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000124', true);
  PERFORM public.api_assign_incident_team(incident_id, ARRAY[responder_one_id, responder_two_id]);

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000125', true);
  response := public.api_update_my_assignment_status(incident_id, 'en_route');
  IF response->>'status' <> 'en_route' THEN
    RAISE EXCEPTION 'Expected incident aggregate to become en_route after first unit departs';
  END IF;

  response := public.api_update_my_assignment_status(incident_id, 'on_scene');
  IF response->>'status' <> 'en_route' THEN
    RAISE EXCEPTION 'Incident must remain en_route while another assigned unit has not arrived';
  END IF;
  SELECT item->>'status' INTO other_status
  FROM jsonb_array_elements(response->'assigned_user_statuses') item
  WHERE (item->>'user_id')::bigint = responder_two_id;
  IF other_status <> 'assigned' THEN
    RAISE EXCEPTION 'One responder arrival must not overwrite another unit status; found %', other_status;
  END IF;

  BEGIN
    PERFORM public.api_record_location_v2(incident_id, 11.244, 125.003, 10, now());
    RAISE EXCEPTION 'Expected GPS update to be rejected after this unit arrived';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000126', true);
  PERFORM public.api_update_incident_status(incident_id, 'en_route');
  response := public.api_update_my_assignment_status(incident_id, 'on_scene');
  IF response->>'status' <> 'on_scene' THEN
    RAISE EXCEPTION 'Incident should move to on_scene once every assigned unit has arrived';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000125', true);
  response := public.api_update_my_assignment_status(incident_id, 'transporting');
  IF response->>'status' <> 'transporting' THEN
    RAISE EXCEPTION 'Incident should reflect transporting once all units arrived and one transports';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000126', true);
  PERFORM public.api_update_my_assignment_status(incident_id, 'transporting');
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000125', true);
  PERFORM public.api_update_my_assignment_status(incident_id, 'resolved');
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000126', true);
  response := public.api_update_my_assignment_status(incident_id, 'resolved');
  IF response->>'status' <> 'resolved' THEN
    RAISE EXCEPTION 'Incident should resolve only after every assigned unit resolves';
  END IF;

  SELECT status INTO aggregate_status FROM public.incidents WHERE id = incident_id;
  IF aggregate_status <> 'resolved' THEN
    RAISE EXCEPTION 'Expected final incident status resolved, found %', aggregate_status;
  END IF;
END;
$per_responder_test$;

-- Admin role/audit regression tests run only in this disposable clean database.
-- Public signup metadata must never grant the privileged role.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000128', 'admin-spoof@example.test', '{"name":"Admin Spoof Attempt","role":"admin"}'),
  ('00000000-0000-4000-8000-000000000129', 'admin-role-target@example.test', '{"name":"Admin Role Target","role":"requester"}'),
  ('00000000-0000-4000-8000-000000000130', 'admin-escalation-attempt@example.test', '{"name":"Admin Escalation Attempt","role":"requester"}');

DO $admin_provision_fixture$
DECLARE
  admin_profile_id bigint;
  signup_role text;
BEGIN
  SELECT id, role INTO admin_profile_id, signup_role
  FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000128';

  IF admin_profile_id IS NULL OR signup_role <> 'requester' THEN
    RAISE EXCEPTION 'Public signup metadata must be forced to requester, including forged role=admin';
  END IF;

  -- Simulate the trusted operator's out-of-band first-admin provisioning.
  UPDATE public.profiles
  SET role = 'admin', duty_status = NULL
  WHERE id = admin_profile_id;

  IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE id = admin_profile_id AND role = 'admin') THEN
    RAISE EXCEPTION 'Trusted operator fixture could not provision the test administrator';
  END IF;
END;
$admin_provision_fixture$;

DO $admin_authorization_test$
DECLARE
  admin_profile_id bigint;
  target_profile_id bigint;
  attacker_profile_id bigint;
  changed jsonb;
  role_after_attempt text;
  audit_count integer;
  denied boolean;
BEGIN
  SELECT id INTO admin_profile_id FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000128';
  SELECT id INTO target_profile_id FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000129';
  SELECT id INTO attacker_profile_id FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000130';

  IF has_function_privilege('anon', 'public.api_admin_list_users()', 'EXECUTE') THEN
    RAISE EXCEPTION 'Anonymous role must not execute the Admin user-list RPC';
  END IF;
  IF NOT has_function_privilege('authenticated', 'public.api_admin_list_users()', 'EXECUTE') THEN
    RAISE EXCEPTION 'Authenticated role must have the explicitly granted Admin RPC entry point';
  END IF;
  IF has_table_privilege('authenticated', 'public.admin_audit_logs', 'INSERT')
     OR has_table_privilege('authenticated', 'public.admin_audit_logs', 'UPDATE')
     OR has_table_privilege('authenticated', 'public.admin_audit_logs', 'DELETE') THEN
    RAISE EXCEPTION 'Authenticated clients must not directly mutate Admin audit rows';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000128', true);
  changed := public.api_admin_list_users();
  IF jsonb_typeof(changed) <> 'array' OR jsonb_array_length(changed) < 3 THEN
    RAISE EXCEPTION 'Admin user-list RPC should return profile data for an authorized administrator';
  END IF;

  changed := public.api_admin_set_user_role(
    target_profile_id,
    'dispatcher',
    'Approved role assignment for emergency dispatch coordination.'
  );
  IF changed->>'role' <> 'dispatcher' OR changed->>'old_role' <> 'requester' THEN
    RAISE EXCEPTION 'Authorized Admin role change returned an unexpected result: %', changed;
  END IF;

  SELECT count(*) INTO audit_count
  FROM public.admin_audit_logs
  WHERE actor_user_id = admin_profile_id
    AND target_user_id = target_profile_id
    AND action = 'user_role_changed'
    AND old_role = 'requester'
    AND new_role = 'dispatcher'
    AND reason = 'Approved role assignment for emergency dispatch coordination.';
  IF audit_count <> 1 THEN
    RAISE EXCEPTION 'Successful role change must write exactly one matching audit event, found %', audit_count;
  END IF;

  -- Even an Admin cannot promote, demote, or edit an Admin account through this RPC.
  denied := false;
  BEGIN
    PERFORM public.api_admin_set_user_role(
      admin_profile_id,
      'requester',
      'Attempt to demote an administrator account.'
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Admin role-change RPC must reject changes to an Admin account';
  END IF;

  -- Ordinary authenticated profiles must not list users or change another role.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000130', true);
  denied := false;
  BEGIN
    PERFORM public.api_admin_list_users();
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Ordinary requester must not read the Admin user list';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_set_user_role(
      target_profile_id,
      'responder',
      'Unauthorized role escalation attempt by a requester.'
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Ordinary requester must not change another profile role';
  END IF;

  -- Direct profile role mutation is rejected by the existing database guard.
  denied := false;
  BEGIN
    UPDATE public.profiles SET role = 'admin'
    WHERE id = attacker_profile_id;
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Ordinary requester must not self-promote through a direct profile update';
  END IF;
  SELECT role INTO role_after_attempt FROM public.profiles WHERE id = attacker_profile_id;
  IF role_after_attempt <> 'requester' THEN
    RAISE EXCEPTION 'Failed self-promotion attempt changed the profile role to %', role_after_attempt;
  END IF;

  -- A missing auth.uid() is treated as unauthenticated, not as an Admin.
  PERFORM set_config('request.jwt.claim.sub', '', true);
  denied := false;
  BEGIN
    PERFORM public.api_admin_list_audit_logs(100);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Unauthenticated request must not read Admin audit events';
  END IF;
END;
$admin_authorization_test$;
SQL

echo "PASS: all $(find supabase/migrations -maxdepth 1 -name '*.sql' | wc -l) Supabase migrations replayed; incident regressions and Admin role/audit authorization tests passed."
