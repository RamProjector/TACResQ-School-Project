-- Runtime regression test for GPS RPC privileges and authorization.
-- Runs only against the disposable PostgreSQL database created by CI migration replay.

DO $privilege_contract$
DECLARE
  public_writer_definer boolean;
  public_reader_definer boolean;
  private_writer_definer boolean;
  private_reader_definer boolean;
  writer_settings text[];
  reader_settings text[];
BEGIN
  IF NOT has_schema_privilege('authenticated', 'private', 'USAGE') THEN
    RAISE EXCEPTION 'authenticated must have USAGE on private to resolve invoker RPC helpers';
  END IF;

  IF NOT has_function_privilege(
      'authenticated',
      'private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)',
      'EXECUTE'
    )
    OR NOT has_function_privilege(
      'authenticated',
      'private.tacresq_active_responder_locations()',
      'EXECUTE'
    ) THEN
    RAISE EXCEPTION 'authenticated must have explicit EXECUTE on the private GPS helpers';
  END IF;

  IF has_function_privilege(
      'anon',
      'private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)',
      'EXECUTE'
    )
    OR has_function_privilege('anon', 'private.tacresq_active_responder_locations()', 'EXECUTE')
    OR has_function_privilege(
      'anon',
      'public.api_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)',
      'EXECUTE'
    )
    OR has_function_privilege('anon', 'public.api_active_responder_locations()', 'EXECUTE') THEN
    RAISE EXCEPTION 'anon must not execute GPS public RPCs or private helpers';
  END IF;

  IF has_table_privilege('authenticated', 'public.location_logs', 'INSERT') THEN
    RAISE EXCEPTION 'authenticated must not bypass GPS validation with direct location_logs INSERT';
  END IF;

  IF NOT has_function_privilege(
      'authenticated',
      'public.api_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)',
      'EXECUTE'
    )
    OR NOT has_function_privilege('authenticated', 'public.api_active_responder_locations()', 'EXECUTE') THEN
    RAISE EXCEPTION 'authenticated must retain the approved public GPS RPC entrypoints';
  END IF;

  SELECT prosecdef INTO public_writer_definer
    FROM pg_proc WHERE oid = to_regprocedure('public.api_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)');
  SELECT prosecdef INTO public_reader_definer
    FROM pg_proc WHERE oid = to_regprocedure('public.api_active_responder_locations()');
  SELECT prosecdef, proconfig INTO private_writer_definer, writer_settings
    FROM pg_proc WHERE oid = to_regprocedure('private.tacresq_record_location_v2(bigint,double precision,double precision,real,timestamp with time zone)');
  SELECT prosecdef, proconfig INTO private_reader_definer, reader_settings
    FROM pg_proc WHERE oid = to_regprocedure('private.tacresq_active_responder_locations()');

  IF public_writer_definer IS DISTINCT FROM false OR public_reader_definer IS DISTINCT FROM false THEN
    RAISE EXCEPTION 'Public GPS RPC wrappers must remain SECURITY INVOKER';
  END IF;
  IF private_writer_definer IS DISTINCT FROM true OR private_reader_definer IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Private GPS implementations must remain SECURITY DEFINER';
  END IF;
  IF NOT (coalesce(writer_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog'])
     OR NOT (coalesce(reader_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Private GPS implementations must pin search_path to pg_catalog';
  END IF;
END;
$privilege_contract$;

INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000140', 'gps-contract-requester@example.test', '{"name":"GPS Contract Requester"}'),
  ('00000000-0000-4000-8000-000000000141', 'gps-contract-dispatcher@example.test', '{"name":"GPS Contract Dispatcher"}'),
  ('00000000-0000-4000-8000-000000000142', 'gps-contract-responder@example.test', '{"name":"GPS Contract Responder"}'),
  ('00000000-0000-4000-8000-000000000143', 'gps-contract-outsider@example.test', '{"name":"GPS Contract Outsider"}');

DO $fixture_roles$
BEGIN
  UPDATE public.profiles SET role = 'dispatcher', duty_status = NULL
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000141';
  UPDATE public.profiles SET role = 'responder', duty_status = 'available'
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000142';
END;
$fixture_roles$;

INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude, location_recorded_at, location_source
)
SELECT p.id, 'medical', 'Disposable GPS RPC authorization contract', 11.2443, 125.0030, now(), 'manual'
FROM public.profiles p
WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000140';

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000141', false);
SELECT public.api_assign_incident_team(
  (SELECT max(i.id) FROM public.incidents i WHERE i.description = 'Disposable GPS RPC authorization contract'),
  ARRAY[(SELECT p.id FROM public.profiles p WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000142')]
);
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000142', false);
SELECT public.api_update_my_assignment_status(
  (SELECT max(i.id) FROM public.incidents i WHERE i.description = 'Disposable GPS RPC authorization contract'),
  'en_route'
);

-- Exercise the real invoker wrapper as authenticated, not as the migration owner.
SET ROLE authenticated;
SELECT public.api_record_location_v2(
  (SELECT max(i.id) FROM public.incidents i WHERE i.description = 'Disposable GPS RPC authorization contract'),
  11.2443, 125.0030, 8, now()
);
DO $direct_insert_denied$
DECLARE
  responder_id bigint;
  incident_id bigint;
  denied boolean := false;
BEGIN
  SELECT id INTO responder_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000142';
  SELECT max(id) INTO incident_id FROM public.incidents
   WHERE description = 'Disposable GPS RPC authorization contract';
  BEGIN
    INSERT INTO public.location_logs(user_id, incident_id, latitude, longitude, recorded_at)
    VALUES (responder_id, incident_id, 11.2443, 125.0030, now());
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Direct INSERT into location_logs unexpectedly succeeded';
  END IF;
END;
$direct_insert_denied$;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000141', false);
DO $dispatcher_reader$
DECLARE
  location_count integer;
BEGIN
  SELECT count(*) INTO location_count FROM public.api_active_responder_locations();
  IF location_count < 1 THEN
    RAISE EXCEPTION 'Authorized dispatcher should read the active responder GPS point';
  END IF;
END;
$dispatcher_reader$;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000143', false);
DO $non_dispatcher_denied$
DECLARE
  denied boolean := false;
BEGIN
  BEGIN
    PERFORM * FROM public.api_active_responder_locations();
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Non-dispatcher unexpectedly read responder GPS locations';
  END IF;
END;
$non_dispatcher_denied$;
RESET ROLE;

SELECT 'PASS: GPS RPC runtime grants, invoker/definer boundaries, direct-write denial, and dispatcher-only location reads.' AS result;
