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

-- Reproduce the live PostgreSQL public-schema defaults for objects
-- created by the postgres migration owner. The hosted defaults grant broad
-- table, sequence and function privileges to anon, authenticated and
-- service_role; migrations must explicitly narrow each new object.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT ALL PRIVILEGES ON TABLES TO anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT ALL PRIVILEGES ON SEQUENCES TO anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO anon, authenticated, service_role;
CREATE SCHEMA auth;
-- Supabase Auth's investigation feed reads only these allowlisted audit fields.
CREATE TABLE auth.audit_log_entries (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
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

CREATE FUNCTION auth.jwt()
RETURNS jsonb
LANGUAGE sql
STABLE
AS $auth_jwt$
  SELECT coalesce(
    nullif(current_setting('request.jwt.claims', true), ''),
    '{}'
  )::jsonb
$auth_jwt$;

-- Match Supabase's auth schema/function visibility for PostgREST roles.
GRANT USAGE ON SCHEMA auth TO authenticated;
GRANT EXECUTE ON FUNCTION auth.uid() TO authenticated;
GRANT EXECUTE ON FUNCTION auth.jwt() TO authenticated;

-- Minimal Supabase Storage schema for migration replay. This lets the test
-- exercise real RLS policies without depending on a running Supabase service.
CREATE SCHEMA storage;
CREATE TABLE storage.buckets (
  id text PRIMARY KEY,
  name text NOT NULL,
  public boolean NOT NULL DEFAULT false,
  file_size_limit bigint,
  allowed_mime_types text[],
  versioning_status text,
  lifecycle_configuration jsonb
);
CREATE TABLE storage.objects (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  bucket_id text NOT NULL,
  name text NOT NULL,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE (bucket_id, name),
  created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;
CREATE FUNCTION storage.foldername(object_name text)
RETURNS text[]
LANGUAGE sql
IMMUTABLE
AS $foldername$
  SELECT CASE
    WHEN array_length(parts, 1) > 1 THEN parts[1:array_length(parts, 1) - 1]
    ELSE ARRAY[]::text[]
  END
  FROM (SELECT string_to_array(object_name, '/') AS parts) AS parsed
$foldername$;
GRANT USAGE ON SCHEMA storage TO authenticated;
GRANT SELECT, INSERT ON storage.objects TO authenticated;
GRANT EXECUTE ON FUNCTION storage.foldername(text) TO authenticated;
SQL

for migration in supabase/migrations/*.sql; do
  echo "Applying ${migration}"
  psql -v ON_ERROR_STOP=1 -f "${migration}"
done

# Keep the account-suspension callable definer inventory in sync with reviewed signatures.
psql -v ON_ERROR_STOP=1 -f tools/test_account_suspension_definer_inventory.sql
# Keep directly authenticated-callable private SECURITY DEFINER helpers inventoried too.
psql -v ON_ERROR_STOP=1 -f tools/test_account_suspension_private_definer_inventory.sql

# Fail early if the replayed schema does not satisfy the incident-chat upload contract.
# The same read-only check can be run manually against a deployed test project.
psql -v ON_ERROR_STOP=1 -f tools/check_incident_chat_upload_schema.sql

# Inventory every application Storage bucket and fail closed on unreviewed buckets.
psql -v ON_ERROR_STOP=1 -f tools/check_storage_bucket_inventory.sql

# Verify account lifecycle schema, immutable audit, and same-valid-JWT state lookup.
psql -v ON_ERROR_STOP=1 -f tools/test_account_lifecycle_foundation.sql

# Verify profile reads and api_me remain valid in PostgreSQL READ ONLY transactions.
psql -v ON_ERROR_STOP=1 -f tools/test_read_only_account_profile_rls.sql

psql -v ON_ERROR_STOP=1 <<'SQL'
-- The Auth -> profile trigger is a privileged account-lifecycle boundary.
-- Fail replay if it disappears, is disabled, or loses its expected definer mode.
DO $auth_profile_trigger_contract$
DECLARE
  trigger_enabled "char";
  function_is_definer boolean;
  function_settings text[];
  function_source text;
  signup_function oid;
BEGIN
  SELECT t.tgenabled, p.prosecdef, p.oid
    INTO trigger_enabled, function_is_definer, signup_function
    FROM pg_catalog.pg_trigger AS t
    JOIN pg_catalog.pg_proc AS p ON p.oid = t.tgfoid
   WHERE t.tgrelid = 'auth.users'::regclass
     AND t.tgname = 'tacresq_auth_user_created'
     AND NOT t.tgisinternal;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Expected Auth signup trigger tacresq_auth_user_created is missing';
  END IF;
  IF trigger_enabled <> 'O' THEN
    RAISE EXCEPTION 'Auth signup trigger must be enabled for normal origin writes; found mode %', trigger_enabled;
  END IF;
  IF function_is_definer IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Auth signup trigger function must retain its SECURITY DEFINER execution mode';
  END IF;
  IF signup_function IS DISTINCT FROM to_regprocedure('public.tacresq_profile_on_signup()') THEN
    RAISE EXCEPTION 'Auth signup trigger must call public.tacresq_profile_on_signup()';
  END IF;

  SELECT p.proconfig, p.prosrc
    INTO function_settings, function_source
    FROM pg_catalog.pg_proc AS p
   WHERE p.oid = signup_function;

  IF NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Auth signup SECURITY DEFINER function must pin search_path to pg_catalog';
  END IF;
  IF function_source NOT LIKE '%ON CONFLICT (auth_user_id) DO NOTHING%'
     OR function_source NOT LIKE '%''requester''%'
     OR function_source NOT LIKE '%pg_catalog.regexp_replace%'
     OR function_source NOT LIKE '%pg_catalog.lower%' THEN
    RAISE EXCEPTION 'Final Auth signup function lost its conflict-safe requester role or qualified normalization contract';
  END IF;
  IF has_function_privilege('authenticated', signup_function, 'EXECUTE')
     OR has_function_privilege('anon', signup_function, 'EXECUTE') THEN
    RAISE EXCEPTION 'Auth signup trigger function must not be directly executable by client roles';
  END IF;
END;
$auth_profile_trigger_contract$;

-- Exercise the final Auth signup trigger's server-side contact validation.
-- A rejected signup must roll back the Auth row as well as profile creation.
DO $auth_signup_validation_test$
DECLARE
  rejected boolean;
  test_user_id uuid;
BEGIN
  test_user_id := '00000000-0000-4000-8000-000000000198';
  rejected := false;
  BEGIN
    INSERT INTO auth.users (id, email, raw_user_meta_data)
    VALUES (test_user_id, 'invalid-phone-signup@example.test',
      '{"name":"Invalid Phone","phone":"abc"}'::jsonb);
  EXCEPTION WHEN invalid_parameter_value THEN
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'Auth signup trigger must reject a phone number without 7-15 digits';
  END IF;
  IF EXISTS (SELECT 1 FROM auth.users WHERE id = test_user_id)
     OR EXISTS (SELECT 1 FROM public.profiles WHERE auth_user_id = test_user_id) THEN
    RAISE EXCEPTION 'Rejected invalid-phone signup must leave neither Auth nor profile row';
  END IF;

  test_user_id := '00000000-0000-4000-8000-000000000199';
  rejected := false;
  BEGIN
    INSERT INTO auth.users (id, email, raw_user_meta_data)
    VALUES (test_user_id, 'long-name-signup@example.test',
      jsonb_build_object('name', repeat('N', 121), 'phone', '+63917000099'));
  EXCEPTION WHEN invalid_parameter_value THEN
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'Auth signup trigger must reject names longer than 120 characters';
  END IF;
  IF EXISTS (SELECT 1 FROM auth.users WHERE id = test_user_id)
     OR EXISTS (SELECT 1 FROM public.profiles WHERE auth_user_id = test_user_id) THEN
    RAISE EXCEPTION 'Rejected overlength-name signup must leave neither Auth nor profile row';
  END IF;
END;
$auth_signup_validation_test$;

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
  public_rpc_settings text[];
  public_rpc_source text;
  guard_rpc_definer boolean;
  guard_rpc_settings text[];
  guard_rpc_source text;
  implementation_definer boolean;
  implementation_settings text[];
BEGIN
  SELECT prosecdef, proconfig, prosrc
    INTO public_rpc_definer, public_rpc_settings, public_rpc_source
    FROM pg_catalog.pg_proc
   WHERE oid = to_regprocedure('public.api_assign_incident_team(bigint,bigint[])');
  SELECT prosecdef, proconfig, prosrc
    INTO guard_rpc_definer, guard_rpc_settings, guard_rpc_source
    FROM pg_catalog.pg_proc
   WHERE oid = to_regprocedure('private.tacresq_assign_incident_team_guarded(bigint,bigint[])');
  SELECT prosecdef, proconfig
    INTO implementation_definer, implementation_settings
    FROM pg_catalog.pg_proc
   WHERE oid = to_regprocedure('private.tacresq_assign_incident_team(bigint,bigint[])');

  IF public_rpc_definer IS DISTINCT FROM false
     OR NOT (coalesce(public_rpc_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR public_rpc_source NOT ILIKE '%tacresq_assign_incident_team_guarded%' THEN
    RAISE EXCEPTION 'Public team-assignment wrapper must remain SECURITY INVOKER and delegate to the guarded private helper';
  END IF;
  IF guard_rpc_definer IS DISTINCT FROM true
     OR NOT (coalesce(guard_rpc_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR guard_rpc_source NOT ILIKE '%account_status = ''active''%'
     OR guard_rpc_source NOT ILIKE '%FOR SHARE%'
     OR guard_rpc_source NOT ILIKE '%private.tacresq_assign_incident_team%' THEN
    RAISE EXCEPTION 'Private assignment guard must be a pinned definer with an active-dispatcher share lock';
  END IF;
  IF implementation_definer IS DISTINCT FROM true
     OR NOT (coalesce(implementation_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Underlying assignment implementation must remain a pinned SECURITY DEFINER internal routine';
  END IF;
  IF NOT has_function_privilege('authenticated', 'private.tacresq_assign_incident_team_guarded(bigint,bigint[])', 'EXECUTE')
     OR has_function_privilege('anon', 'private.tacresq_assign_incident_team_guarded(bigint,bigint[])', 'EXECUTE')
     OR has_function_privilege('authenticated', 'private.tacresq_assign_incident_team(bigint,bigint[])', 'EXECUTE')
     OR has_function_privilege('anon', 'private.tacresq_assign_incident_team(bigint,bigint[])', 'EXECUTE') THEN
    RAISE EXCEPTION 'Only the guarded private assignment helper may be callable by authenticated clients';
  END IF;
END;
$dispatch_test$;

-- Regression test for the failure seen in the Android recording:
-- creating an incident must also create its first status log without an FK error.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000123',
  'sos-regression@example.test',
  '{"name":"SOS Regression","phone":"+63917000001","role":"requester"}'::jsonb
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

-- Location fallback must remain enforced when a requester edits an existing incident.
DO $incident_location_update_test$
DECLARE
  requester_profile_id bigint;
  incident_id bigint;
BEGIN
  SELECT id INTO requester_profile_id
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000123';

  -- Reuse the fixture incident created above; the one-active-request
  -- invariant correctly prevents creating a second active request here.
  SELECT id INTO incident_id
    FROM public.incidents
   WHERE requester_id = requester_profile_id
     AND description = 'Automated SOS audit regression';

  IF incident_id IS NULL THEN
    RAISE EXCEPTION 'Expected the existing requester fixture incident';
  END IF;

  -- Convert the fixture to a valid landmark-only location before testing
  -- attempts to remove its final usable location detail.
  UPDATE public.incidents
     SET latitude = NULL,
         longitude = NULL,
         accuracy_m = NULL,
         location_recorded_at = NULL,
         location_notes = 'Beside the public market entrance',
         location_source = 'none'
   WHERE id = incident_id;

  BEGIN
    UPDATE public.incidents
       SET location_notes = NULL
     WHERE id = incident_id;
    RAISE EXCEPTION 'Expected an incident update without pin or landmark to be rejected';
  EXCEPTION WHEN SQLSTATE '22023' THEN
    NULL;
  END;

  BEGIN
    UPDATE public.incidents
       SET longitude = 125.003
     WHERE id = incident_id;
    RAISE EXCEPTION 'Expected a partial coordinate update to be rejected';
  EXCEPTION WHEN SQLSTATE '22023' THEN
    NULL;
  END;

  UPDATE public.incidents
     SET location_notes = 'Beside the verified public market entrance'
   WHERE id = incident_id;
END;
$incident_location_update_test$;

-- Regression for the requester cancellation trigger conflict observed in the
-- app: the owner can cancel a pending, unassigned request through its RPC.
DO $requester_cancel_guard_test$
DECLARE
  requester_profile_id bigint;
  incident_id bigint;
  result jsonb;
  final_status text;
BEGIN
  SELECT id INTO requester_profile_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000123';
  SELECT id INTO incident_id FROM public.incidents
   WHERE requester_id = requester_profile_id
     AND description = 'Automated SOS audit regression';
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000123', true);
  result := public.api_cancel_my_incident(incident_id);
  SELECT status INTO final_status FROM public.incidents WHERE id = incident_id;
  IF result->>'status' <> 'cancelled' OR final_status <> 'cancelled' THEN
    RAISE EXCEPTION 'Requester cancellation RPC should persist a cancelled response; result=% status=%', result, final_status;
  END IF;
END;
$requester_cancel_guard_test$;


-- End-to-end SQL regression: dispatch owns incident milestones and all
-- assigned members inherit the same shared progress snapshot.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000124', 'dispatch-regression@example.test', '{"name":"Dispatch Regression","phone":"+63917000002"}'),
  ('00000000-0000-4000-8000-000000000127', 'dispatcher-progress-requester@example.test', '{"name":"Dispatcher Progress Requester","phone":"+63917000003"}'),
  ('00000000-0000-4000-8000-000000000125', 'responder-one@example.test', '{"name":"Responder One","phone":"+63917000004"}'),
  ('00000000-0000-4000-8000-000000000126', 'responder-two@example.test', '{"name":"Responder Two","phone":"+63917000005"}');

DO $dispatcher_progress_test$
DECLARE
  requester_id bigint;
  dispatcher_id bigint;
  responder_one_id bigint;
  responder_two_id bigint;
  test_incident_id bigint;
  response jsonb;
  stale_assignment_count integer;
  stale_duty_count integer;
  final_status text;
  rejected boolean;
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
  VALUES (requester_id, 'medical', 'Synthetic dispatcher progress test', 11.244, 125.003, now(), 'manual')
  RETURNING id INTO test_incident_id;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000124', true);
  PERFORM public.api_assign_incident_team(test_incident_id, ARRAY[responder_one_id, responder_two_id]);

  IF has_function_privilege('authenticated', 'public.api_update_my_assignment_status(bigint,text)', 'EXECUTE')
     OR has_function_privilege('authenticated', 'private.tacresq_update_my_assignment_status(bigint,text)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Legacy per-responder status RPCs must not be executable by authenticated clients';
  END IF;

  -- Field units cannot self-assert operational duty states; dispatch cannot
  -- write a duty state that does not match the assigned incident milestone.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000125', true);
  rejected := false;
  BEGIN
    UPDATE public.profiles SET duty_status = 'en_route' WHERE id = responder_one_id;
    RAISE EXCEPTION 'Responder must not self-assert en_route duty status';
  EXCEPTION WHEN SQLSTATE '22023' THEN
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'Responder self-set duty status unexpectedly succeeded';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000124', true);
  rejected := false;
  BEGIN
    UPDATE public.profiles SET duty_status = 'on_scene' WHERE id = responder_one_id;
    RAISE EXCEPTION 'Dispatcher must not set a duty status ahead of the incident milestone';
  EXCEPTION WHEN SQLSTATE '22023' THEN
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'Dispatcher mismatched duty status unexpectedly succeeded';
  END IF;

  -- A field unit may not advance shared incident progress through the dispatcher RPC.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000125', true);
  rejected := false;
  BEGIN
    PERFORM public.api_update_incident_status(test_incident_id, 'en_route');
  EXCEPTION WHEN insufficient_privilege THEN
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'Responder must not advance dispatcher-owned incident progress';
  END IF;

  -- Only the dispatcher advances the incident; all assigned members inherit the milestone.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000124', true);
  response := public.api_update_incident_status(test_incident_id, 'en_route');
  IF response->>'status' <> 'en_route' THEN
    RAISE EXCEPTION 'Dispatcher should advance assigned incident to en_route';
  END IF;

  SELECT count(*) INTO stale_assignment_count
    FROM public.incident_assignments a
   WHERE a.incident_id = test_incident_id AND a.responder_status <> 'en_route';
  IF stale_assignment_count <> 0 THEN
    RAISE EXCEPTION 'All assigned members must inherit en_route; stale assignment rows=%', stale_assignment_count;
  END IF;
  SELECT count(*) INTO stale_duty_count
    FROM public.profiles
   WHERE id IN (responder_one_id, responder_two_id) AND duty_status IS DISTINCT FROM 'en_route';
  IF stale_duty_count <> 0 THEN
    RAISE EXCEPTION 'All assigned member duty snapshots must inherit en_route; stale profiles=%', stale_duty_count;
  END IF;

  response := public.api_update_incident_status(test_incident_id, 'on_scene');
  IF response->>'status' <> 'on_scene' THEN
    RAISE EXCEPTION 'Dispatcher should advance incident to on_scene';
  END IF;
  SELECT count(*) INTO stale_assignment_count
    FROM public.incident_assignments a
   WHERE a.incident_id = test_incident_id AND a.responder_status <> 'on_scene';
  IF stale_assignment_count <> 0 THEN
    RAISE EXCEPTION 'All assigned members must inherit on_scene; stale assignment rows=%', stale_assignment_count;
  END IF;

  -- GPS is no longer accepted as an en-route location update once dispatch records arrival.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000125', true);
  rejected := false;
  BEGIN
    PERFORM public.api_record_location_v2(test_incident_id, 11.244, 125.003, 10, now());
  EXCEPTION WHEN insufficient_privilege THEN
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'GPS update should be rejected after dispatcher records on_scene';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000124', true);
  response := public.api_update_incident_status(test_incident_id, 'transporting');
  IF response->>'status' <> 'transporting' THEN
    RAISE EXCEPTION 'Dispatcher should advance incident to transporting';
  END IF;
  response := public.api_update_incident_status(test_incident_id, 'resolved');
  IF response->>'status' <> 'resolved' THEN
    RAISE EXCEPTION 'Dispatcher should complete the incident';
  END IF;

  SELECT status INTO final_status FROM public.incidents WHERE id = test_incident_id;
  IF final_status <> 'resolved' THEN
    RAISE EXCEPTION 'Expected final incident status resolved, found %', final_status;
  END IF;
  SELECT count(*) INTO stale_duty_count
    FROM public.profiles
   WHERE id IN (responder_one_id, responder_two_id) AND duty_status IS DISTINCT FROM 'available';
  IF stale_duty_count <> 0 THEN
    RAISE EXCEPTION 'Resolved incident should release assigned members without other active assignments';
  END IF;
END;
$dispatcher_progress_test$;
SQL

psql -v ON_ERROR_STOP=1 <<'SQL'
-- Notification persistence, event creation, ownership, and RPC grants.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000131', 'notification-dispatcher@example.test', '{"name":"Notification Dispatcher","phone":"+63917000006"}'),
  ('00000000-0000-4000-8000-000000000132', 'notification-requester@example.test', '{"name":"Notification Requester","phone":"+63917000007"}'),
  ('00000000-0000-4000-8000-000000000133', 'notification-responder@example.test', '{"name":"Notification Responder","phone":"+63917000008"}'),
  ('00000000-0000-4000-8000-000000000134', 'notification-legacy-responder@example.test', '{"name":"Notification Legacy Responder","phone":"+63917000009"}'),
  ('00000000-0000-4000-8000-000000000135', 'notification-legacy-requester@example.test', '{"name":"Notification Legacy Requester","phone":"+63917000010"}');

DO $notification_test$
DECLARE
  dispatcher_id bigint;
  requester_id bigint;
  responder_id bigint;
  legacy_responder_id bigint;
  legacy_requester_id bigint;
  test_incident_id bigint;
  legacy_incident_id bigint;
  requester_notifications jsonb;
  responder_notifications jsonb;
  requester_notification_id bigint;
  responder_notification_id bigint;
  result_boolean boolean;
  notification_count integer;
  denied boolean;
  function_is_definer boolean;
  function_settings text[];
BEGIN
  SELECT prosecdef, proconfig INTO function_is_definer, function_settings
    FROM pg_proc WHERE oid = to_regprocedure('private.tacresq_notify_assignment_insert()');
  IF function_is_definer IS DISTINCT FROM true
     OR NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Assignment notification trigger must be SECURITY DEFINER with a pinned search_path';
  END IF;
  SELECT prosecdef, proconfig INTO function_is_definer, function_settings
    FROM pg_proc WHERE oid = to_regprocedure('private.tacresq_notify_status_log_insert()');
  IF function_is_definer IS DISTINCT FROM true
     OR NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Status notification trigger must be SECURITY DEFINER with a pinned search_path';
  END IF;
  SELECT prosecdef, proconfig INTO function_is_definer, function_settings
    FROM pg_proc WHERE oid = to_regprocedure('private.tacresq_notify_legacy_assignment_update()');
  IF function_is_definer IS DISTINCT FROM true
     OR NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Legacy assignment notification trigger must be SECURITY DEFINER with a pinned search_path';
  END IF;
  SELECT id INTO dispatcher_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000131';
  SELECT id INTO requester_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000132';
  SELECT id INTO responder_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000133';
  SELECT id INTO legacy_responder_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000134';
  SELECT id INTO legacy_requester_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000135';

  UPDATE public.profiles SET role = 'dispatcher', duty_status = NULL WHERE id = dispatcher_id;
  UPDATE public.profiles SET role = 'requester', duty_status = NULL WHERE id = requester_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available' WHERE id = responder_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available' WHERE id = legacy_responder_id;
  UPDATE public.profiles SET role = 'requester', duty_status = NULL WHERE id = legacy_requester_id;

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source
  ) VALUES (
    requester_id, 'medical', 'Synthetic notification test incident',
    11.244, 125.003, now(), 'manual'
  ) RETURNING id INTO test_incident_id;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000131', true);
  PERFORM public.api_assign_incident_team(test_incident_id, ARRAY[responder_id]);

  SELECT count(*) INTO notification_count
    FROM public.user_notifications n
   WHERE n.incident_id = test_incident_id
     AND n.event_type = 'incident_assigned';
  IF notification_count <> 2 THEN
    RAISE EXCEPTION 'Assignment must create one notification for the requester and one for the assigned unit; found %', notification_count;
  END IF;

  IF has_table_privilege('anon', 'public.user_notifications', 'SELECT')
     OR has_table_privilege('authenticated', 'public.user_notifications', 'SELECT')
     OR has_table_privilege('authenticated', 'public.user_notifications', 'INSERT')
     OR has_table_privilege('authenticated', 'public.user_notifications', 'UPDATE')
     OR has_table_privilege('authenticated', 'public.user_notifications', 'DELETE') THEN
    RAISE EXCEPTION 'Notification table must not be directly accessible to API roles';
  END IF;
  IF has_function_privilege('anon', 'public.api_my_notifications(integer)', 'EXECUTE')
     OR has_function_privilege('anon', 'public.api_mark_notification_read(bigint)', 'EXECUTE')
     OR NOT has_function_privilege('authenticated', 'public.api_my_notifications(integer)', 'EXECUTE')
     OR NOT has_function_privilege('authenticated', 'public.api_mark_notification_read(bigint)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Notification RPC execute grants are incorrect';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000132', true);
  requester_notifications := public.api_my_notifications(10);
  IF jsonb_array_length(requester_notifications) <> 1
     OR requester_notifications->0->>'event_type' <> 'incident_assigned'
     OR requester_notifications->0->>'title' <> 'Response unit assigned' THEN
    RAISE EXCEPTION 'Requester must receive only their own assignment notification: %', requester_notifications;
  END IF;
  requester_notification_id := (requester_notifications->0->>'id')::bigint;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000133', true);
  responder_notifications := public.api_my_notifications(10);
  IF jsonb_array_length(responder_notifications) <> 1
     OR responder_notifications->0->>'title' <> 'New incident assignment' THEN
    RAISE EXCEPTION 'Assigned unit must receive only its own assignment notification: %', responder_notifications;
  END IF;
  responder_notification_id := (responder_notifications->0->>'id')::bigint;

  -- A requester cannot mark a responder's notification read or learn whether it exists.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000132', true);
  result_boolean := public.api_mark_notification_read(responder_notification_id);
  IF result_boolean THEN
    RAISE EXCEPTION 'A user must not mark another recipient''s notification as read';
  END IF;
  result_boolean := public.api_mark_notification_read(requester_notification_id);
  IF NOT result_boolean THEN
    RAISE EXCEPTION 'A recipient should be able to mark their own notification read';
  END IF;
  result_boolean := public.api_mark_notification_read(requester_notification_id);
  IF NOT result_boolean THEN
    RAISE EXCEPTION 'Marking an already-read notification should be safely idempotent';
  END IF;

  -- A dispatcher-owned milestone should notify the requester without exposing other users' rows.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000131', true);
  PERFORM public.api_update_incident_status(test_incident_id, 'en_route');

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000132', true);
  requester_notifications := public.api_my_notifications(10);
  SELECT count(*) INTO notification_count
    FROM jsonb_array_elements(requester_notifications) AS notifications(item)
   WHERE item->>'event_type' = 'incident_status_changed'
     AND item->>'incident_id' = test_incident_id::text;
  IF notification_count < 1 THEN
    RAISE EXCEPTION 'Requester must be notified when an assigned unit changes incident status: %', requester_notifications;
  END IF;

  -- The web dispatcher still uses the legacy single-unit api_assign_incident RPC.
  -- Its assignment must also produce exactly one alert per recipient. Use a
  -- separate available unit because the first responder is still on an active incident.
  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source
  ) VALUES (
    legacy_requester_id, 'medical', 'Synthetic legacy assignment notification test',
    11.245, 125.004, now(), 'manual'
  ) RETURNING id INTO legacy_incident_id;
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000131', true);
  PERFORM public.api_assign_incident(legacy_incident_id, legacy_responder_id);
  SELECT count(*) INTO notification_count
    FROM public.user_notifications n
   WHERE n.incident_id = legacy_incident_id
     AND n.event_type = 'incident_assigned';
  IF notification_count <> 2 THEN
    RAISE EXCEPTION 'Legacy dashboard assignment must create exactly two recipient alerts, found %', notification_count;
  END IF;

  denied := false;
  PERFORM set_config('request.jwt.claim.sub', '', true);
  BEGIN
    PERFORM public.api_my_notifications(10);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Notification list must reject unauthenticated callers';
  END IF;

  denied := false;  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000132', true);
  BEGIN
    PERFORM public.api_my_notifications(101);
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Notification list must reject limits above 100';
  END IF;
END;
$notification_test$;
SQL

psql -v ON_ERROR_STOP=1 <<'SQL'
-- Incident chat authorization, idempotency, rate/size bounds, and message alerts.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000136', 'chat-dispatcher@example.test', '{"name":"Chat Dispatcher","phone":"+63917000011"}'),
  ('00000000-0000-4000-8000-000000000137', 'chat-requester@example.test', '{"name":"Chat Requester","phone":"+63917000012"}'),
  ('00000000-0000-4000-8000-000000000138', 'chat-responder@example.test', '{"name":"Chat Responder","phone":"+63917000013"}'),
  ('00000000-0000-4000-8000-000000000139', 'chat-outsider@example.test', '{"name":"Chat Outsider","phone":"+63917000014"}');

DO $chat_test$
DECLARE
  dispatcher_id bigint;
  requester_id bigint;
  responder_id bigint;
  outsider_id bigint;
  test_incident_id bigint;
  send_result jsonb;
  retry_result jsonb;
  message_list jsonb;
  first_message_id bigint;
  reply_message_id bigint;
  denied boolean;
  notification_count integer;
  i integer;
BEGIN
  SELECT id INTO dispatcher_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000136';
  SELECT id INTO requester_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000137';
  SELECT id INTO responder_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000138';
  SELECT id INTO outsider_id FROM public.profiles WHERE auth_user_id = '00000000-0000-4000-8000-000000000139';

  UPDATE public.profiles SET role = 'dispatcher', duty_status = NULL WHERE id = dispatcher_id;
  UPDATE public.profiles SET role = 'requester', duty_status = NULL WHERE id = requester_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available' WHERE id = responder_id;
  UPDATE public.profiles SET role = 'requester', duty_status = NULL WHERE id = outsider_id;

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source
  ) VALUES (
    requester_id, 'medical', 'Synthetic chat access test incident',
    11.246, 125.005, now(), 'manual'
  ) RETURNING id INTO test_incident_id;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000136', true);
  PERFORM public.api_assign_incident_team(test_incident_id, ARRAY[responder_id]);

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000137', true);
  send_result := public.api_send_incident_message(
    test_incident_id, 'Need help at the front entrance.',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa0136'
  );
  first_message_id := (send_result->>'id')::bigint;
  retry_result := public.api_send_incident_message(
    test_incident_id, 'Need help at the front entrance.',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa0136'
  );
  IF (retry_result->>'id')::bigint <> first_message_id THEN
    RAISE EXCEPTION 'Retry with the same client message ID must return the original message';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      test_incident_id, 'Different content with reused key.',
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa0136'
    );
  EXCEPTION WHEN unique_violation THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Reusing a client message ID for different content must be rejected';
  END IF;

  message_list := public.api_my_incident_messages(test_incident_id, 50, NULL);
  IF jsonb_array_length(message_list) <> 1
     OR message_list->0->>'message' <> 'Need help at the front entrance.'
     OR (message_list->0->>'sender_id')::bigint <> requester_id THEN
    RAISE EXCEPTION 'Requester must see their own incident message: %', message_list;
  END IF;

  -- Only the assigned responder is notified about the requester message; the
  -- sender must not receive a notification for their own message.
  SELECT count(*) INTO notification_count
    FROM public.user_notifications n
   WHERE n.incident_id = test_incident_id
     AND n.event_type = 'incident_message'
     AND n.recipient_user_id = responder_id;
  IF notification_count <> 1 THEN
    RAISE EXCEPTION 'Assigned responder must receive one generic message notification; found %', notification_count;
  END IF;
  SELECT count(*) INTO notification_count
    FROM public.user_notifications n
   WHERE n.incident_id = test_incident_id
     AND n.event_type = 'incident_message'
     AND n.recipient_user_id = requester_id;
  IF notification_count <> 0 THEN
    RAISE EXCEPTION 'A sender must not be notified about their own message';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000138', true);
  message_list := public.api_my_incident_messages(test_incident_id, 50, NULL);
  IF jsonb_array_length(message_list) <> 1
     OR (message_list->0->>'id')::bigint <> first_message_id THEN
    RAISE EXCEPTION 'Assigned responder must be able to read the incident conversation';
  END IF;

  send_result := public.api_send_incident_message(
    test_incident_id, 'Responder is on the way.',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbb0138'
  );
  reply_message_id := (send_result->>'id')::bigint;
  message_list := public.api_my_incident_messages(test_incident_id, 1, reply_message_id);
  IF jsonb_array_length(message_list) <> 1
     OR (message_list->0->>'id')::bigint <> first_message_id THEN
    RAISE EXCEPTION 'Before-ID pagination must return the older message';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000139', true);
  denied := false;
  BEGIN
    PERFORM public.api_my_incident_messages(test_incident_id, 50, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Unrelated account must not read incident chat';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      test_incident_id, 'Unauthorized message.',
      'cccccccc-cccc-4ccc-8ccc-cccccccc0139'
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Unrelated account must not send incident chat messages';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000136', true);
  message_list := public.api_my_incident_messages(test_incident_id, 50, NULL);
  IF jsonb_array_length(message_list) <> 2 THEN
    RAISE EXCEPTION 'Dispatcher should be able to read incident chat';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_my_incident_messages(test_incident_id, 101, NULL);
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Chat page size above 100 must be rejected';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      test_incident_id, '   ',
      'dddddddd-dddd-4ddd-8ddd-dddddddd0136'
    );
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Blank chat messages must be rejected';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      test_incident_id, repeat('x', 2001),
      'eeeeeeee-eeee-4eee-8eee-eeeeeeee0136'
    );
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Chat messages longer than 2000 characters must be rejected';
  END IF;

  IF has_table_privilege('anon', 'public.incident_messages', 'SELECT')
     OR has_table_privilege('authenticated', 'public.incident_messages', 'SELECT')
     OR has_table_privilege('authenticated', 'public.incident_messages', 'INSERT')
     OR has_table_privilege('authenticated', 'public.incident_messages', 'UPDATE')
     OR has_table_privilege('authenticated', 'public.incident_messages', 'DELETE') THEN
    RAISE EXCEPTION 'Chat table must not be directly accessible to API roles';
  END IF;
  IF has_function_privilege('anon', 'public.api_my_incident_messages(bigint,integer,bigint)', 'EXECUTE')
     OR has_function_privilege('anon', 'public.api_send_incident_message(bigint,text,uuid)', 'EXECUTE')
     OR NOT has_function_privilege('authenticated', 'public.api_my_incident_messages(bigint,integer,bigint)', 'EXECUTE')
     OR NOT has_function_privilege('authenticated', 'public.api_send_incident_message(bigint,text,uuid)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Incident chat RPC grants are incorrect';
  END IF;

  SELECT count(*) INTO notification_count
    FROM public.user_notifications n
   WHERE n.incident_id = test_incident_id
     AND n.event_type = 'incident_message'
     AND n.recipient_user_id = requester_id;
  IF notification_count <> 1 THEN
    RAISE EXCEPTION 'Requester must receive one message notification after responder reply';
  END IF;

  -- Per-sender flood guard: twenty sends in a minute are allowed; the next is rejected.
  FOR i IN 1..20 LOOP
    PERFORM public.api_send_incident_message(
      test_incident_id,
      'Rate limit probe ' || i,
      ('f0000000-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid
    );
  END LOOP;
  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      test_incident_id,
      'Rate limit probe 21',
      'f0000000-0000-4000-8000-000000000021'
    );
  EXCEPTION WHEN program_limit_exceeded THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'The 21st message within one minute must be rate limited';
  END IF;
END;
$chat_test$;
SQL

echo "PASS: migration replay and baseline incident/chat/notification/admin regressions passed; continuing with SOS idempotency stress tests."


psql -v ON_ERROR_STOP=1 <<'SQL'
-- Admin role/audit regression tests run only in this disposable clean database.
-- Public signup metadata must never grant the privileged role.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000128', 'admin-spoof@example.test', '{"name":"Admin Spoof Attempt","phone":"+63917000015","role":"admin"}'),
  ('00000000-0000-4000-8000-000000000129', 'admin-role-target@example.test', '{"name":"Admin Role Target","phone":"+63917000016","role":"requester"}'),
  ('00000000-0000-4000-8000-000000000130', 'admin-escalation-attempt@example.test', '{"name":"Admin Escalation Attempt","phone":"+63917000017","role":"requester"}');

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
  IF has_function_privilege('authenticated', 'public.api_admin_list_users()', 'EXECUTE') THEN
    RAISE EXCEPTION 'Legacy unbounded Admin user-list RPC must not be executable by authenticated clients';
  END IF;
  IF has_function_privilege('anon', 'public.api_admin_list_users_page(integer,integer,text,text)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Anonymous role must not execute the Admin paginated user-list RPC';
  END IF;
  IF NOT has_function_privilege('authenticated', 'public.api_admin_list_users_page(integer,integer,text,text)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Authenticated role must have the explicitly granted Admin pagination RPC entry point';
  END IF;
  IF has_table_privilege('authenticated', 'public.admin_audit_logs', 'INSERT')
     OR has_table_privilege('authenticated', 'public.admin_audit_logs', 'UPDATE')
     OR has_table_privilege('authenticated', 'public.admin_audit_logs', 'DELETE') THEN
    RAISE EXCEPTION 'Authenticated clients must not directly mutate Admin audit rows';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000128', true);
  PERFORM set_config(
    'request.jwt.claims',
    '{"aal":"aal2","amr":[{"method":"password","timestamp":1},{"method":"totp","timestamp":2}]}',
    true
  );
  -- Function EXECUTE grants are asserted above. Direct audit-table RLS is
  -- exercised under the authenticated role after this authorization block.

  changed := public.api_admin_list_users_page(25, 0, '', 'all');
  IF jsonb_typeof(changed) IS DISTINCT FROM 'object'
     OR jsonb_typeof(changed->'users') IS DISTINCT FROM 'array'
     OR jsonb_array_length(changed->'users') > 25 THEN
    RAISE EXCEPTION 'Admin pagination did not return a bounded user page: %', changed;
  END IF;
  -- Search must be case-insensitive and literal (wildcard characters are not SQL patterns).
  changed := public.api_admin_list_users_page(25, 0, 'ADMIN-ROLE-TARGET@example.test', 'requester');
  IF changed->>'total_count' <> '1'
     OR jsonb_array_length(changed->'users') <> 1
     OR changed->'users'->0->>'email' <> 'admin-role-target@example.test' THEN
    RAISE EXCEPTION 'Admin pagination search/filter returned an unexpected result: %', changed;
  END IF;

  changed := public.api_admin_list_users_page(25, 0, '%', 'requester');
  IF changed->>'total_count' <> '0' OR jsonb_array_length(changed->'users') <> 0 THEN
    RAISE EXCEPTION 'Percent in Admin search must be treated literally, not as a wildcard: %', changed;
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_list_users_page(101, 0, '', 'all');
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Admin pagination must reject page sizes above 100';
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

  -- A real Admin identity with AAL2 but no TOTP method must still be
  -- rejected by every browser-callable privileged RPC wrapper.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000128', true);
  PERFORM set_config(
    'request.jwt.claims',
    '{"aal":"aal2","amr":[{"method":"password","timestamp":20}]}',
    true
  );

  denied := false;
  BEGIN
    PERFORM public.api_admin_list_users_page(25, 0, '', 'all');
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Admin user pagination accepted AAL2 without TOTP';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_set_user_role(
      999999,
      'responder',
      'Synthetic no-TOTP wrapper authorization test.'
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Admin role change accepted AAL2 without TOTP';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_list_audit_logs(100);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Admin audit RPC accepted AAL2 without TOTP';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_list_audit_logs_page(25, 0, '', 'all', NULL, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Paginated Admin audit RPC accepted AAL2 without TOTP';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_analytics_summary(now() - interval '7 days', now());
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Admin analytics RPC accepted AAL2 without TOTP';
  END IF;

  -- Ordinary authenticated profiles must not list users or change another role.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000130', true);
  PERFORM set_config(
    'request.jwt.claims',
    '{"aal":"aal1","amr":[{"method":"password","timestamp":3}]}',
    true
  );
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
  END;  IF NOT denied THEN
    RAISE EXCEPTION 'Ordinary requester must not change another profile role';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_list_audit_logs(100);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Ordinary requester must not read Admin audit events';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_admin_list_audit_logs_page(25, 0, '', 'all', NULL, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Ordinary requester must not read paginated Admin audit events';
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

-- Exercise the restrictive audit-log MFA policy as an actual authenticated
-- database role. Superuser-owned DO blocks bypass RLS and cannot prove this.
SET ROLE authenticated;
DO $admin_audit_rls_mfa_test$
DECLARE
  visible_count integer;
BEGIN
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000128', true);

  -- A password-only AAL1 session must not see audit rows, even for an Admin.
  PERFORM set_config(
    'request.jwt.claims',
    '{"aal":"aal1","amr":[{"method":"password","timestamp":10}]}',
    true
  );
  SELECT count(*) INTO visible_count FROM public.admin_audit_logs;
  IF visible_count <> 0 THEN
    RAISE EXCEPTION 'Admin audit rows were visible to an AAL1 session';
  END IF;

  -- AAL2 without a TOTP method must also be denied.
  PERFORM set_config(
    'request.jwt.claims',
    '{"aal":"aal2","amr":[{"method":"password","timestamp":11}]}',
    true
  );
  SELECT count(*) INTO visible_count FROM public.admin_audit_logs;
  IF visible_count <> 0 THEN
    RAISE EXCEPTION 'Admin audit rows were visible without TOTP in the AAL2 session';
  END IF;

  -- The verified TOTP session must retain the intended Admin read access.
  PERFORM set_config(
    'request.jwt.claims',
    '{"aal":"aal2","amr":[{"method":"password","timestamp":12},{"method":"totp","timestamp":13}]}',
    true
  );
  SELECT count(*) INTO visible_count FROM public.admin_audit_logs;
  IF visible_count < 1 THEN
    RAISE EXCEPTION 'A verified Admin TOTP session could not read its audit history';
  END IF;

  -- RFC-8176 string-form AMR claims are also accepted by the MFA policy.
  PERFORM set_config(
    'request.jwt.claims',
    '{"aal":"aal2","amr":["password","totp"]}',
    true
  );
  SELECT count(*) INTO visible_count FROM public.admin_audit_logs;
  IF visible_count < 1 THEN
    RAISE EXCEPTION 'A string-form TOTP AMR session could not read its audit history';
  END IF;
END;
$admin_audit_rls_mfa_test$;
RESET ROLE;

-- Every ordinary operational role must be denied by the database authorization
-- boundary, even though each role can execute the authenticated RPC entrypoint.
DO $admin_role_matrix_test$
DECLARE
  principal record;
  denied boolean;
  target_profile_id bigint;
BEGIN
  SELECT id INTO target_profile_id
  FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000129';

  FOR principal IN
    SELECT * FROM (VALUES
      ('00000000-0000-4000-8000-000000000124'::uuid, 'dispatcher'::text),
      ('00000000-0000-4000-8000-000000000125'::uuid, 'responder'::text),
      ('00000000-0000-4000-8000-000000000126'::uuid, 'driver'::text),
      ('00000000-0000-4000-8000-000000000130'::uuid, 'requester'::text)
    ) AS ordinary_principals(auth_id, role_name)
  LOOP
    PERFORM set_config('request.jwt.claim.sub', principal.auth_id::text, true);
    PERFORM set_config(
      'request.jwt.claims',
      '{"aal":"aal1","amr":[{"method":"password","timestamp":4}]}',
      true
    );

    denied := false;
    BEGIN
      PERFORM public.api_admin_list_users();
    EXCEPTION WHEN insufficient_privilege THEN
      denied := true;
    END;
    IF NOT denied THEN
      RAISE EXCEPTION 'Ordinary role % unexpectedly read Admin user list', principal.role_name;
    END IF;

    denied := false;
    BEGIN
      PERFORM public.api_admin_list_users_page(25, 0, '', 'all');
    EXCEPTION WHEN insufficient_privilege THEN
      denied := true;
    END;
    IF NOT denied THEN
      RAISE EXCEPTION 'Ordinary role % unexpectedly used Admin pagination RPC', principal.role_name;
    END IF;

    denied := false;
    BEGIN
      PERFORM public.api_admin_set_user_role(
        target_profile_id,
        'responder',
        'Unauthorized role change regression test.'
      );
    EXCEPTION WHEN insufficient_privilege THEN
      denied := true;
    END;
    IF NOT denied THEN
      RAISE EXCEPTION 'Ordinary role % unexpectedly changed an account role', principal.role_name;
    END IF;

    denied := false;
    BEGIN
      PERFORM public.api_admin_list_audit_logs(100);
    EXCEPTION WHEN insufficient_privilege THEN
      denied := true;
    END;
    IF NOT denied THEN
      RAISE EXCEPTION 'Ordinary role % unexpectedly read Admin audit logs', principal.role_name;
    END IF;

    -- A user cannot self-promote through the profile-update route regardless
    -- of whether the account is a requester, responder, driver, or dispatcher.
    denied := false;
    BEGIN
      UPDATE public.profiles
      SET role = 'admin', duty_status = NULL
      WHERE auth_user_id = principal.auth_id;
    EXCEPTION WHEN insufficient_privilege THEN
      denied := true;
    END;
    IF NOT denied THEN
      RAISE EXCEPTION 'Ordinary role % unexpectedly self-promoted via profile update', principal.role_name;
    END IF;

    IF EXISTS (
      SELECT 1 FROM public.profiles
      WHERE auth_user_id = principal.auth_id AND role = 'admin'
    ) THEN
      RAISE EXCEPTION 'Denied self-promotion changed ordinary role % to admin', principal.role_name;
    END IF;
  END LOOP;
END;
$admin_role_matrix_test$;

-- Audit history must keep immutable actor/target identity values after profile deletion.
-- This protects historical attribution from ON DELETE SET NULL side effects.
DO $admin_audit_immutability_test$
DECLARE
  audit_event_id bigint;
  audit_target_id bigint;
  audit_actor_id bigint;
  audit_actor_identity text;
BEGIN
  SELECT id, target_user_id, actor_user_id, actor_identity
  INTO audit_event_id, audit_target_id, audit_actor_id, audit_actor_identity
  FROM public.admin_audit_logs
  WHERE target_user_id = (
    SELECT id FROM public.profiles
    WHERE auth_user_id = '00000000-0000-4000-8000-000000000129'
  )
  ORDER BY id DESC
  LIMIT 1;

  IF audit_event_id IS NULL OR audit_target_id IS NULL OR audit_actor_id IS NULL OR audit_actor_identity IS NULL THEN
    RAISE EXCEPTION 'Expected a complete application-admin audit event before testing identity retention';
  END IF;

  INSERT INTO public.admin_audit_logs (
    actor_user_id, actor_identity, target_user_id, action, old_role, new_role, reason
  ) VALUES (
    NULL, 'CI trusted operator', audit_target_id, 'user_role_changed', 'requester', 'admin',
    'Disposable migration test operator provisioning event'
  );
  IF NOT EXISTS (
    SELECT 1 FROM public.admin_audit_logs
    WHERE target_user_id = audit_target_id
      AND actor_user_id IS NULL
      AND actor_identity = 'CI trusted operator'
  ) THEN
    RAISE EXCEPTION 'Trusted-operator audit events must support an explicit identity without a profile actor ID';
  END IF;

  DELETE FROM public.profiles
  WHERE auth_user_id IN (
    '00000000-0000-4000-8000-000000000129',
    '00000000-0000-4000-8000-000000000128'
  );

  IF NOT EXISTS (
    SELECT 1 FROM public.admin_audit_logs
    WHERE id = audit_event_id
      AND target_user_id = audit_target_id
      AND actor_user_id = audit_actor_id
  ) THEN
    RAISE EXCEPTION 'Deleting profiles must not mutate or erase historical Admin audit identity snapshots';
  END IF;
END;
$admin_audit_immutability_test$;
SQL


# Concurrent SOS idempotency regression. Each psql process is an independent
# database session, exercising unique-key arbitration across sessions.
psql -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000140',
  'concurrent-sos-regression@example.test',
  '{"name":"Concurrent SOS Regression","phone":"+63917000136","role":"requester"}'::jsonb
);
SQL

concurrent_dir="$(mktemp -d)"
worker_pids=()
cleanup_concurrent_dir() { rm -rf "$concurrent_dir"; }
trap cleanup_concurrent_dir EXIT

for worker in $(seq 1 8); do
  (
    psql -X -qAt -v ON_ERROR_STOP=1 >"$concurrent_dir/response-${worker}.json" <<'SQL'
BEGIN;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000140', true);
SELECT public.api_create_incident_v4(
  'medical',
  'Concurrent idempotency regression',
  11.244,
  125.003,
  NULL,
  'Synthetic concurrent CI location',
  '2026-10-30T12:00:00Z'::timestamptz,
  'manual',
  '00000000-0000-4000-8000-000000000140'::uuid
)::text;
COMMIT;
SQL
  ) &
  worker_pids+=("$!")
done
for worker_pid in "${worker_pids[@]}"; do
  wait "$worker_pid"
done

python3 - "$concurrent_dir" <<'PY'
from pathlib import Path
import sys

directory = Path(sys.argv[1])
responses = [
    line.strip()
    for path in sorted(directory.glob("response-*.json"))
    for line in path.read_text().splitlines()
    if line.strip().startswith("{")
]
if len(responses) != 8:
    raise SystemExit(f"Expected 8 successful concurrent RPC responses, found {len(responses)}")
if len(set(responses)) != 1:
    raise SystemExit("Concurrent SOS retries returned different incident responses")
print("PASS: all 8 concurrent SOS submissions returned the same saved response")
PY

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $concurrent_sos_assert$
DECLARE
  requester_profile_id bigint;
  incident_count integer;
  saved_key_count integer;
  response_count integer;
BEGIN
  SELECT id INTO requester_profile_id
  FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000140';

  SELECT count(*) INTO incident_count
  FROM public.incidents
  WHERE requester_id = requester_profile_id
    AND description = 'Concurrent idempotency regression';

  SELECT count(*), count(response_payload)
    INTO saved_key_count, response_count
  FROM public.incident_submission_keys
  WHERE requester_id = requester_profile_id
    AND client_request_id = '00000000-0000-4000-8000-000000000140';

  IF incident_count <> 1 THEN
    RAISE EXCEPTION 'Eight concurrent retries must create exactly one incident; found %', incident_count;
  END IF;
  IF saved_key_count <> 1 OR response_count <> 1 THEN
    RAISE EXCEPTION 'Expected one completed idempotency record; keys %, responses %',
      saved_key_count, response_count;
  END IF;
END;
$concurrent_sos_assert$;
SQL


# Reusing a key with changed details must be rejected, and an unauthenticated
# caller must not be able to reserve or retrieve another request's idempotency key.
psql -v ON_ERROR_STOP=1 <<'SQL'
DO $sos_idempotency_negative_test$
DECLARE
  rejected boolean := false;
BEGIN
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000140', true);
  BEGIN
    PERFORM public.api_create_incident_v4(
      'medical',
      'Changed payload must not reuse key',
      11.244,
      125.003,
      NULL,
      'Synthetic concurrent CI location',
      '2026-10-30T12:00:00Z'::timestamptz,
      'manual',
      '00000000-0000-4000-8000-000000000140'::uuid
    );
  EXCEPTION WHEN SQLSTATE '22023' THEN
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'Reusing an SOS key with changed details must be rejected';
  END IF;

  rejected := false;
  PERFORM set_config('request.jwt.claim.sub', '', true);
  BEGIN
    PERFORM public.api_create_incident_v4(
      'medical', 'Unauthenticated SOS attempt', NULL, NULL, NULL, NULL,
      NULL, 'none', '00000000-0000-4000-8000-000000000137'::uuid
    );
  EXCEPTION WHEN insufficient_privilege THEN
    rejected := true;
  END;
  IF NOT rejected THEN
    RAISE EXCEPTION 'Unauthenticated callers must not create SOS submissions';
  END IF;
END;
$sos_idempotency_negative_test$;
SQL


# Failure-atomicity regression: force the underlying incident insert to fail
# after the idempotency key has been reserved. The failed RPC must roll back
# that reservation so the same request ID can be retried successfully.
psql -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000141',
  'sos-atomicity-regression-141@example.test',
  '{"name":"SOS Atomicity Regression","phone":"+63917000141","role":"requester"}'::jsonb
);

CREATE OR REPLACE FUNCTION public.tacresq_test_fail_sos_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $test$
BEGIN
  IF NEW.description = 'Atomicity failure injection' THEN
    RAISE EXCEPTION 'Intentional CI failure after idempotency reservation'
      USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END;
$test$;

CREATE TRIGGER tacresq_test_fail_sos_insert
BEFORE INSERT ON public.incidents
FOR EACH ROW EXECUTE FUNCTION public.tacresq_test_fail_sos_insert();
SQL

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $sos_atomicity_failure$
DECLARE
  requester_profile_id bigint;
  failure_observed boolean := false;
BEGIN
  SELECT id INTO requester_profile_id FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000141';

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000141', true);
  BEGIN
    PERFORM public.api_create_incident_v4(
      'medical', 'Atomicity failure injection', 11.244, 125.003, NULL,
      'Synthetic CI failure location', '2026-10-30T12:00:00Z'::timestamptz,
      'manual', '00000000-0000-4000-8000-000000000141'::uuid
    );
  EXCEPTION WHEN SQLSTATE 'P0001' THEN
    failure_observed := true;
  END;

  IF NOT failure_observed THEN
    RAISE EXCEPTION 'Expected the injected incident-creation failure';
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.incident_submission_keys
    WHERE requester_id = requester_profile_id
      AND client_request_id = '00000000-0000-4000-8000-000000000141'::uuid
  ) THEN
    RAISE EXCEPTION 'Failed SOS transaction left behind an idempotency reservation';
  END IF;
END;
$sos_atomicity_failure$;

DROP TRIGGER tacresq_test_fail_sos_insert ON public.incidents;
DROP FUNCTION public.tacresq_test_fail_sos_insert();

DO $sos_atomicity_retry$
DECLARE
  response jsonb;
  requester_profile_id bigint;
BEGIN
  SELECT id INTO requester_profile_id
  FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000141';

  IF requester_profile_id IS NULL THEN
    RAISE EXCEPTION 'Atomicity retry fixture profile was not created';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000141', true);
  SELECT public.api_create_incident_v4(
    'medical', 'Atomicity failure injection', 11.244, 125.003, NULL,
    'Synthetic CI failure location', '2026-10-30T12:00:00Z'::timestamptz,
    'manual', '00000000-0000-4000-8000-000000000141'::uuid
  ) INTO response;

  IF response IS NULL THEN
    RAISE EXCEPTION 'Retry after rolled-back SOS reservation must return the saved incident response';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.incident_submission_keys
    WHERE requester_id = requester_profile_id
      AND client_request_id = '00000000-0000-4000-8000-000000000141'::uuid
      AND response_payload = response
  ) THEN
    RAISE EXCEPTION 'Successful retry did not persist its idempotency response';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.incidents
    WHERE requester_id = requester_profile_id
      AND description = 'Atomicity failure injection'
  ) THEN
    RAISE EXCEPTION 'Successful retry did not create the expected incident';
  END IF;
END;
$sos_atomicity_retry$;
SQL

# Verify the isolated GPS RPC privilege boundary after all migrations replay.
psql -v ON_ERROR_STOP=1 <<'SQL'
DO $location_rpc_boundary_test$
DECLARE
  public_writer regprocedure := to_regprocedure(
    'public.api_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)'
  );
  private_writer regprocedure := to_regprocedure(
    'private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)'
  );
  public_reader regprocedure := to_regprocedure('public.api_active_responder_locations()');
  private_reader regprocedure := to_regprocedure('private.tacresq_active_responder_locations()');
  function_settings text[];
BEGIN
  IF public_writer IS NULL OR private_writer IS NULL OR public_reader IS NULL OR private_reader IS NULL THEN
    RAISE EXCEPTION 'Expected public GPS wrappers and private implementations to exist';
  END IF;
  IF (SELECT prosecdef FROM pg_proc WHERE oid = public_writer) IS DISTINCT FROM false
     OR (SELECT prosecdef FROM pg_proc WHERE oid = private_writer) IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'GPS write boundary must be public SECURITY INVOKER and private SECURITY DEFINER';
  END IF;
  IF (SELECT prosecdef FROM pg_proc WHERE oid = public_reader) IS DISTINCT FROM false
     OR (SELECT prosecdef FROM pg_proc WHERE oid = private_reader) IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'GPS read boundary must be public SECURITY INVOKER and private SECURITY DEFINER';
  END IF;
  SELECT proconfig INTO function_settings FROM pg_proc WHERE oid = private_writer;
  IF NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Private GPS writer must pin search_path to pg_catalog';
  END IF;
  SELECT proconfig INTO function_settings FROM pg_proc WHERE oid = private_reader;
  IF NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Private GPS reader must pin search_path to pg_catalog';
  END IF;
  IF NOT has_schema_privilege('authenticated', 'private', 'USAGE')
     OR NOT has_function_privilege('authenticated', public_writer, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', private_writer, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', public_reader, 'EXECUTE')
     OR NOT has_function_privilege('authenticated', private_reader, 'EXECUTE') THEN
    RAISE EXCEPTION 'Authenticated role is missing the required private GPS RPC privileges';
  END IF;
  IF has_function_privilege('anon', public_writer, 'EXECUTE')
     OR has_function_privilege('anon', private_writer, 'EXECUTE')
     OR has_function_privilege('anon', public_reader, 'EXECUTE')
     OR has_function_privilege('anon', private_reader, 'EXECUTE') THEN
    RAISE EXCEPTION 'Anonymous role must not execute GPS RPCs';
  END IF;
  IF has_table_privilege('authenticated', 'public.location_logs', 'INSERT')
     OR has_table_privilege('anon', 'public.location_logs', 'INSERT') THEN
    RAISE EXCEPTION 'Client roles must not bypass the validated GPS writer with direct inserts';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'location_logs'
      AND column_name = 'received_at' AND column_default LIKE '%clock_timestamp%'
  ) THEN
    RAISE EXCEPTION 'location_logs.received_at must have a server-generated default';
  END IF;
  IF to_regclass('public.location_logs_received_rate_idx') IS NULL THEN
    RAISE EXCEPTION 'Concurrent receipt-time rate index is missing';
  END IF;
END;
$location_rpc_boundary_test$;
SQL


# Verify the data-free dependency health RPC privilege and execution contract.
psql -v ON_ERROR_STOP=1 -f tools/test_dependency_health_rpc.sql

# Verify incident-image Storage RLS and the authenticated image-message RPC
# against the minimal Storage schema created above.
psql -v ON_ERROR_STOP=1 -f tools/test_incident_chat_storage_policies.sql

# Execute the read-only attachment inventory against the replayed schema.
# This catches SQL syntax, metadata comparison, CTE alias, and projection errors
# that source-string assertions cannot detect.
psql -v ON_ERROR_STOP=1 -f tools/report_incident_chat_attachment_inventory.sql

psql -v ON_ERROR_STOP=1 -f tools/test_notification_rpc_security.sql
# Verify committed app actions are recorded without sensitive content and appear in the Admin feed.
psql -v ON_ERROR_STOP=1 -f tools/test_app_action_audit.sql
psql -v ON_ERROR_STOP=1 -f tools/test_fcm_delivery_queue.sql
psql -v ON_ERROR_STOP=1 -f tools/test_fcm_token_account_switch.sql
psql -v ON_ERROR_STOP=1 -f tools/test_responder_incident_claiming.sql

# Real two-connection races for field self-claim and cross-path assignment.
bash tools/test_field_claim_concurrency.sh
bash tools/test_assignment_cross_path_concurrency.sh

# Verify requester location edit authorization and validation as authenticated.
psql -v ON_ERROR_STOP=1 -f tools/test_authenticated_requester_location_edit.sql

# Race dispatcher claimability changes against requester cancellation.
bash tools/test_claimability_cancellation_concurrency.sh

# Verify authenticated callers cannot bypass the incident RPC write boundary.
psql -v ON_ERROR_STOP=1 -f tools/test_authenticated_incident_write_boundary.sql

# Verify immutable profile identity metadata and dispatcher-verified duty-status synchronization.
psql -v ON_ERROR_STOP=1 -f tools/test_authenticated_profile_metadata.sql

# Verify legacy and paginated Admin list RPC access as authenticated.
psql -v ON_ERROR_STOP=1 -f tools/test_authenticated_admin_user_list_boundary.sql

# Exercise dispatcher release, primary handoff, duty reservation, and audit events.
psql -v ON_ERROR_STOP=1 -f tools/test_dispatcher_release_assignment.sql

# Keep the Admin audit-log MFA policy restrictive. PostgreSQL ORs permissive
# policies, so a second permissive AAL2 policy would not enforce MFA.
psql -v ON_ERROR_STOP=1 <<'SQL'
DO $audit_mfa_policy$
DECLARE
  mfa_policy_permissive text;
  admin_policy_permissive text;
BEGIN
  SELECT permissive INTO mfa_policy_permissive
    FROM pg_catalog.pg_policies
   WHERE schemaname = 'public'
     AND tablename = 'admin_audit_logs'
     AND policyname = 'admin_audit_logs_require_aal2'
     AND cmd = 'SELECT'
     AND 'authenticated' = ANY(roles);

  SELECT permissive INTO admin_policy_permissive
    FROM pg_catalog.pg_policies
   WHERE schemaname = 'public'
     AND tablename = 'admin_audit_logs'
     AND policyname = 'admin_audit_logs_read_admin'
     AND cmd = 'SELECT'
     AND 'authenticated' = ANY(roles);

  IF mfa_policy_permissive IS DISTINCT FROM 'RESTRICTIVE' THEN
    RAISE EXCEPTION 'Admin audit-log AAL2 policy must remain RESTRICTIVE';
  END IF;
  IF admin_policy_permissive IS DISTINCT FROM 'PERMISSIVE' THEN
    RAISE EXCEPTION 'Admin audit-log role policy must remain PERMISSIVE';
  END IF;
END;
$audit_mfa_policy$;
SQL

# Print the effective authenticated API, RLS, and Storage authorization surface.
# This inventory is read-only and does not mark any path as suspension-safe.
psql -v ON_ERROR_STOP=1 -f tools/check_account_suspension_authorization_surface.sql


# Guard client schema creation, RLS-bypass membership, and future-object ACLs.
# These checks turn the newly inventoried catalog facts into regression gates.
psql -v ON_ERROR_STOP=1 <<'SQL'
DO $account_suspension_privilege_baseline$
DECLARE
  anon_oid oid;
  authenticated_oid oid;
BEGIN
  SELECT oid INTO anon_oid FROM pg_catalog.pg_roles WHERE rolname = 'anon';
  SELECT oid INTO authenticated_oid FROM pg_catalog.pg_roles WHERE rolname = 'authenticated';

  IF anon_oid IS NULL OR authenticated_oid IS NULL THEN
    RAISE EXCEPTION 'Expected Supabase anon and authenticated roles in clean replay';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM (VALUES ('public'), ('private'), ('storage'), ('auth')) AS schemas(schema_name)
    WHERE has_schema_privilege('anon', schema_name, 'CREATE')
       OR has_schema_privilege('authenticated', schema_name, 'CREATE')
  ) THEN
    RAISE EXCEPTION 'Supabase client roles must not have CREATE on application/Auth/Storage schemas';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_catalog.pg_roles AS client_role
    CROSS JOIN pg_catalog.pg_roles AS elevated_role
    WHERE client_role.rolname IN ('anon', 'authenticated')
      AND (elevated_role.rolsuper OR elevated_role.rolbypassrls)
      AND pg_catalog.pg_has_role(client_role.oid, elevated_role.oid, 'MEMBER')
  ) THEN
    RAISE EXCEPTION 'Supabase client roles must not inherit SUPERUSER or BYPASSRLS membership';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_catalog.pg_default_acl AS defaults
    CROSS JOIN LATERAL pg_catalog.aclexplode(defaults.defaclacl) AS expanded
    WHERE defaults.defaclnamespace = 0
      AND expanded.grantee IN (0::oid, anon_oid, authenticated_oid)
  ) THEN
    RAISE EXCEPTION 'Global default ACLs must not grant future object privileges to PUBLIC or Supabase client roles';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_catalog.pg_class AS relation
    JOIN pg_catalog.pg_namespace AS schema_name ON schema_name.oid = relation.relnamespace
    WHERE schema_name.nspname = 'public'
      AND relation.relkind IN ('r', 'p')
      AND (
        has_table_privilege('anon', relation.oid, 'TRUNCATE')
        OR has_table_privilege('authenticated', relation.oid, 'TRUNCATE')
        OR has_table_privilege('anon', relation.oid, 'REFERENCES')
        OR has_table_privilege('authenticated', relation.oid, 'REFERENCES')
        OR has_table_privilege('anon', relation.oid, 'TRIGGER')
        OR has_table_privilege('authenticated', relation.oid, 'TRIGGER')
      )
  ) THEN
    RAISE EXCEPTION 'Supabase client roles must not gain public-table TRUNCATE, REFERENCES, or TRIGGER privileges';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_catalog.pg_class AS sequence
    JOIN pg_catalog.pg_namespace AS schema_name ON schema_name.oid = sequence.relnamespace
    WHERE schema_name.nspname = 'public'
      AND sequence.relkind = 'S'
      AND (
        has_sequence_privilege('anon', sequence.oid, 'USAGE')
        OR has_sequence_privilege('anon', sequence.oid, 'SELECT')
        OR has_sequence_privilege('anon', sequence.oid, 'UPDATE')
        OR has_sequence_privilege('authenticated', sequence.oid, 'USAGE')
        OR has_sequence_privilege('authenticated', sequence.oid, 'SELECT')
        OR has_sequence_privilege('authenticated', sequence.oid, 'UPDATE')
      )
  ) THEN
    RAISE EXCEPTION 'Public sequence grants must not expose anon or authenticated sequence access';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_catalog.pg_class AS sequence
    JOIN pg_catalog.pg_namespace AS schema_name ON schema_name.oid = sequence.relnamespace
    WHERE schema_name.nspname = 'public'
      AND sequence.relkind = 'S'
      AND sequence.relname NOT IN (
        'api_operation_logs_id_seq',
        'device_security_events_id_seq',
        'device_integrity_checks_id_seq'
      )
      AND (
        has_sequence_privilege('service_role', sequence.oid, 'USAGE')
        OR has_sequence_privilege('service_role', sequence.oid, 'SELECT')
        OR has_sequence_privilege('service_role', sequence.oid, 'UPDATE')
      )
  ) THEN
    RAISE EXCEPTION 'service_role must not retain access to RPC/trigger-owned application sequences';
  END IF;

  FOREACH privilege_name IN ARRAY ARRAY['USAGE', 'SELECT'] LOOP
    IF NOT has_sequence_privilege('service_role', 'public.api_operation_logs_id_seq', privilege_name)
       OR NOT has_sequence_privilege('service_role', 'public.device_security_events_id_seq', privilege_name)
       OR NOT has_sequence_privilege('service_role', 'public.device_integrity_checks_id_seq', privilege_name) THEN
      RAISE EXCEPTION 'Trusted direct-insert sequence consumers lost required % privilege', privilege_name;
    END IF;
  END LOOP;

  IF has_sequence_privilege('service_role', 'public.api_operation_logs_id_seq', 'UPDATE')
     OR has_sequence_privilege('service_role', 'public.device_security_events_id_seq', 'UPDATE')
     OR has_sequence_privilege('service_role', 'public.device_integrity_checks_id_seq', 'UPDATE') THEN
    RAISE EXCEPTION 'Trusted server sequence consumers must not retain UPDATE/setval';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_catalog.pg_class AS relation
    JOIN pg_catalog.pg_namespace AS schema_name ON schema_name.oid = relation.relnamespace
    JOIN pg_catalog.pg_attribute AS column_info ON column_info.attrelid = relation.oid
    WHERE schema_name.nspname = 'public'
      AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
      AND column_info.attnum > 0
      AND NOT column_info.attisdropped
      AND (
        has_column_privilege('anon', relation.oid, column_info.attnum, 'REFERENCES')
        OR has_column_privilege('authenticated', relation.oid, column_info.attnum, 'REFERENCES')
      )
  ) THEN
    RAISE EXCEPTION 'Supabase client roles must not gain public column REFERENCES privileges';
  END IF;
END;
$account_suspension_privilege_baseline$;
SQL

# PostgreSQL 17 adds MAINTAIN to table ACLs. PG16 keeps this test conditional;
# the same replay on PG17 must prove authenticated clients cannot run maintenance
# commands while retaining their normal read and narrowly scoped duty updates.
psql -v ON_ERROR_STOP=1 <<'SQL'
DO $pg17_maintain_privilege_contract$
BEGIN
  IF current_setting('server_version_num')::integer >= 170000 THEN
    IF has_table_privilege('authenticated', 'public.profiles', 'MAINTAIN')
       OR has_table_privilege('authenticated', 'public.incidents', 'MAINTAIN')
       OR has_table_privilege('anon', 'public.profiles', 'MAINTAIN')
       OR has_table_privilege('anon', 'public.incidents', 'MAINTAIN') THEN
      RAISE EXCEPTION 'Supabase client roles must not retain PostgreSQL 17 MAINTAIN on profiles/incidents';
    END IF;

    IF NOT has_table_privilege('authenticated', 'public.profiles', 'SELECT')
       OR NOT has_table_privilege('authenticated', 'public.incidents', 'SELECT')
       OR NOT has_column_privilege('authenticated', 'public.profiles', 'duty_status', 'UPDATE')
       OR NOT has_column_privilege('authenticated', 'public.profiles', 'updated_at', 'UPDATE') THEN
      RAISE EXCEPTION 'PostgreSQL 17 MAINTAIN reduction must preserve the normal profile/incident client workflow';
    END IF;
  ELSE
    RAISE NOTICE 'Skipping PostgreSQL 17 MAINTAIN assertion on server version %',
      current_setting('server_version');
  END IF;
END;
$pg17_maintain_privilege_contract$;
SQL

# Preserve the audit-log MFA gate as a RESTRICTIVE policy. A second
# PERMISSIVE policy would be OR-combined with the Admin-role policy and
# silently let role membership bypass the AAL2/TOTP requirement.
psql -v ON_ERROR_STOP=1 <<'SQL'
DO $admin_audit_policy_contract$
DECLARE
  policy_mode text;
  policy_command text;
  policy_roles text[];
  policy_qual text;
BEGIN
  SELECT permissive, cmd, roles, qual
    INTO policy_mode, policy_command, policy_roles, policy_qual
    FROM pg_catalog.pg_policies
   WHERE schemaname = 'public'
     AND tablename = 'admin_audit_logs'
     AND policyname = 'admin_audit_logs_require_aal2';

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Required admin audit AAL2 policy is missing';
  END IF;
  IF policy_mode IS DISTINCT FROM 'RESTRICTIVE' THEN
    RAISE EXCEPTION 'Admin audit AAL2 policy must remain RESTRICTIVE; found %', policy_mode;
  END IF;
  IF policy_command IS DISTINCT FROM 'SELECT' OR NOT ('authenticated' = ANY(policy_roles)) THEN
    RAISE EXCEPTION 'Admin audit AAL2 policy must apply to authenticated SELECT';
  END IF;
  IF policy_qual IS NULL
     OR policy_qual NOT LIKE '%aal2%'
     OR policy_qual NOT LIKE '%totp%' THEN
    RAISE EXCEPTION 'Admin audit AAL2 policy must require both aal2 and TOTP';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_policies
     WHERE schemaname = 'public'
       AND tablename = 'admin_audit_logs'
       AND policyname = 'admin_audit_logs_read_admin'
       AND permissive = 'PERMISSIVE'
       AND cmd = 'SELECT'
  ) THEN
    RAISE EXCEPTION 'Admin audit role policy must remain a separate PERMISSIVE SELECT policy';
  END IF;
END;
$admin_audit_policy_contract$;
SQL


# Verify the trigger-only incident invariant has no direct client/service EXECUTE
# path after the full migration replay. The application DML path remains protected
# by the enabled row trigger; callers do not need EXECUTE on the trigger function.
psql -v ON_ERROR_STOP=1 <<'SQL'
DO $incident_location_trigger_grant_contract$
DECLARE
  trigger_function oid := to_regprocedure(
    'public.tacresq_require_incident_location()'
  );
  bound_trigger_count integer;
  enabled_trigger_count integer;
BEGIN
  IF trigger_function IS NULL THEN
    RAISE EXCEPTION 'Required incident location trigger function is missing';
  END IF;

  SELECT count(*),
         count(*) FILTER (WHERE trigger_row.tgenabled = 'O')
    INTO bound_trigger_count, enabled_trigger_count
    FROM pg_catalog.pg_trigger AS trigger_row
   WHERE NOT trigger_row.tgisinternal
     AND trigger_row.tgfoid = trigger_function
     AND trigger_row.tgrelid = 'public.incidents'::regclass
     AND trigger_row.tgname = 'incidents_require_location_before_write';

  IF bound_trigger_count <> 1 OR enabled_trigger_count <> 1 THEN
    RAISE EXCEPTION
      'Expected exactly one enabled incidents_require_location_before_write binding on public.incidents; found % binding(s), % enabled',
      bound_trigger_count, enabled_trigger_count;
  END IF;

  IF EXISTS (
    SELECT 1
      FROM pg_catalog.pg_proc AS function_row
      CROSS JOIN LATERAL pg_catalog.aclexplode(
        coalesce(
          function_row.proacl,
          pg_catalog.acldefault('f', function_row.proowner)
        )
      ) AS privilege_row
     WHERE function_row.oid = trigger_function
       AND privilege_row.grantee = 0
       AND privilege_row.privilege_type = 'EXECUTE'
  ) THEN
    RAISE EXCEPTION 'PUBLIC must not retain EXECUTE on the trigger-only incident location function';
  END IF;

  IF has_function_privilege('anon', trigger_function, 'EXECUTE')
     OR has_function_privilege('authenticated', trigger_function, 'EXECUTE')
     OR has_function_privilege('service_role', trigger_function, 'EXECUTE') THEN
    RAISE EXCEPTION 'Client and service roles must not directly EXECUTE the trigger-only incident location function';
  END IF;

  IF NOT has_function_privilege('postgres', trigger_function, 'EXECUTE') THEN
    RAISE EXCEPTION 'The function owner must retain EXECUTE on the trigger-only incident location function';
  END IF;
END;
$incident_location_trigger_grant_contract$;
SQL

# Verify the service-role Admin audit boundary and preserve authenticated Admin RPC grants.
psql -v ON_ERROR_STOP=1 -f tools/test_service_role_admin_audit_privileges.sql
