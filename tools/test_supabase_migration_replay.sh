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
SQL

echo "PASS: all $(find supabase/migrations -maxdepth 1 -name '*.sql' | wc -l) Supabase migrations replayed, and incident audit plus per-responder status regressions passed."
