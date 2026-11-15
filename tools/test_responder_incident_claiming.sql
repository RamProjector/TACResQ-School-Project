-- Runtime security & functional contract for responder incident claiming.
-- Runs on the disposable PostgreSQL database during migration replay testing.
BEGIN;

DO $claiming_privileges$
DECLARE
  claim_oid regprocedure := to_regprocedure('public.api_claim_incident(bigint)');
  set_claimable_oid regprocedure := to_regprocedure('public.api_set_incident_claimable(bigint,boolean)');
  claimable_list_oid regprocedure := to_regprocedure('public.api_claimable_incidents()');
  proc_definer boolean;
  proc_settings text[];
BEGIN
  IF claim_oid IS NULL OR set_claimable_oid IS NULL OR claimable_list_oid IS NULL THEN
    RAISE EXCEPTION 'Incident claiming RPC functions are missing';
  END IF;

  IF NOT has_function_privilege('authenticated', claim_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'authenticated must execute api_claim_incident';
  END IF;

  IF has_function_privilege('anon', claim_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'anon/PUBLIC must not execute api_claim_incident';
  END IF;

  SELECT prosecdef, proconfig INTO proc_definer, proc_settings
    FROM pg_proc WHERE oid = claim_oid;
  IF proc_definer IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'api_claim_incident must be SECURITY DEFINER';
  END IF;
  IF NOT (coalesce(proc_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'api_claim_incident must pin search_path to pg_catalog';
  END IF;
END;
$claiming_privileges$;

-- Seed test accounts
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('c1000000-1111-4111-8111-000000000100', 'claim-requester@example.test', '{"name":"Claim Requester","phone":"+639170000100"}'),
  ('c1010000-1111-4111-8111-000000000101', 'claim-dispatcher@example.test', '{"name":"Claim Dispatcher","phone":"+639170000101"}'),
  ('c1020000-1111-4111-8111-000000000102', 'claim-responder@example.test', '{"name":"Claim Responder","phone":"+639170000102"}');

UPDATE public.profiles SET role = 'dispatcher' WHERE auth_user_id = 'c1010000-1111-4111-8111-000000000101';
UPDATE public.profiles SET role = 'responder', is_on_duty = true WHERE auth_user_id = 'c1020000-1111-4111-8111-000000000102';

-- Create incident as requester
SELECT set_config('request.jwt.claim.sub', 'c1000000-1111-4111-8111-000000000100', true);
SET LOCAL ROLE authenticated;

DO $create_test_incident$
DECLARE
  v_result jsonb;
BEGIN
  v_result := public.api_create_incident_v4(
    'medical', 'Claim test incident', 11.243, 125.004, 10.0, 'Park entrance',
    now()::text, 'gps', '00000000-0000-4000-8000-000000000100'
  );
  PERFORM set_config('tacresq.test_claim_incident_id', (v_result->>'id'), true);
END;
$create_test_incident$;

-- Set claimable as dispatcher
SELECT set_config('request.jwt.claim.sub', 'c1010000-1111-4111-8111-000000000101', true);
SET LOCAL ROLE authenticated;

DO $dispatcher_set_claimable$
DECLARE
  v_incident_id bigint := current_setting('tacresq.test_claim_incident_id')::bigint;
  v_result jsonb;
BEGIN
  v_result := public.api_set_incident_claimable(v_incident_id, true);
  IF (v_result->>'is_claimable')::boolean IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Dispatcher failed to set incident claimable';
  END IF;
END;
$dispatcher_set_claimable$;

-- Claim incident as responder
SELECT set_config('request.jwt.claim.sub', 'c1020000-1111-4111-8111-000000000102', true);
SET LOCAL ROLE authenticated;

DO $responder_claim_incident$
DECLARE
  v_incident_id bigint := current_setting('tacresq.test_claim_incident_id')::bigint;
  v_result jsonb;
BEGIN
  v_result := public.api_claim_incident(v_incident_id);
  IF (v_result->>'status') IS DISTINCT FROM 'claimed' THEN
    RAISE EXCEPTION 'On-duty responder failed to claim incident: %', v_result;
  END IF;
END;
$responder_claim_incident$;

ROLLBACK;
