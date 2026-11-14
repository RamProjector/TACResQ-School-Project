-- Runtime contract for the server-only, atomic FCM delivery queue.
-- Runs only against the disposable PostgreSQL database used by migration replay.
BEGIN;

DO $fcm_queue_privileges$
DECLARE
  claim_oid regprocedure := to_regprocedure(
    'public.api_claim_fcm_delivery_batch(integer,integer)'
  );
  function_definer boolean;
  function_settings text[];
BEGIN
  IF claim_oid IS NULL THEN
    RAISE EXCEPTION 'FCM delivery claim RPC is missing';
  END IF;
  IF NOT has_function_privilege('service_role', claim_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'service_role must execute the FCM delivery claim RPC';
  END IF;
  IF has_function_privilege('anon', claim_oid, 'EXECUTE')
     OR has_function_privilege('authenticated', claim_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'FCM delivery claim RPC must not be executable by client roles';
  END IF;

  SELECT prosecdef, proconfig
    INTO function_definer, function_settings
    FROM pg_proc
   WHERE oid = claim_oid;
  IF function_definer IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'FCM delivery claim RPC must execute with its constrained owner privileges';
  END IF;
  IF NOT (coalesce(function_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'FCM delivery claim RPC must pin search_path to pg_catalog';
  END IF;
  IF has_table_privilege('authenticated', 'public.fcm_delivery_attempts', 'SELECT')
     OR has_table_privilege('authenticated', 'public.fcm_delivery_attempts', 'INSERT')
     OR has_table_privilege('authenticated', 'public.fcm_delivery_attempts', 'UPDATE')
     OR has_table_privilege('anon', 'public.fcm_delivery_attempts', 'SELECT') THEN
    RAISE EXCEPTION 'Client roles must not access the private FCM delivery ledger';
  END IF;
END;
$fcm_queue_privileges$;

INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('f1000000-1111-4111-8111-000000000100', 'fcm-queue-fixture@example.test',
   '{"name":"FCM Queue Fixture","phone":"+63917000100"}');

DO $fcm_queue_seed$
DECLARE
  fixture_profile_id bigint;
  old_notification_id bigint;
  new_notification_id bigint;
  permanent_notification_id bigint;
  fixture_token text := 'fcm-test-token-queue-0000000000000100';
BEGIN
  SELECT id INTO fixture_profile_id
    FROM public.profiles
   WHERE auth_user_id = 'f1000000-1111-4111-8111-000000000100';
  IF fixture_profile_id IS NULL THEN
    RAISE EXCEPTION 'FCM queue fixture profile was not created';
  END IF;

  INSERT INTO public.fcm_tokens (profile_id, token)
  VALUES (fixture_profile_id, fixture_token);

  INSERT INTO public.user_notifications (
    recipient_user_id, incident_id, event_type, title, body
  ) VALUES (
    fixture_profile_id, NULL, 'incident_status_changed',
    'Older delivered notification', 'Already sent and remains unread.'
  ) RETURNING id INTO old_notification_id;

  INSERT INTO public.fcm_delivery_attempts (
    notification_id, profile_id, token, status, attempt_count
  ) VALUES (
    old_notification_id, fixture_profile_id, fixture_token, 'sent', 1
  );

  INSERT INTO public.user_notifications (
    recipient_user_id, incident_id, event_type, title, body
  ) VALUES (
    fixture_profile_id, NULL, 'incident_status_changed',
    'New due notification', 'Must be available to the delivery worker.'
  ) RETURNING id INTO new_notification_id;

  INSERT INTO public.user_notifications (
    recipient_user_id, incident_id, event_type, title, body
  ) VALUES (
    fixture_profile_id, NULL, 'incident_status_changed',
    'Permanently failed notification', 'Must never be retried.'
  ) RETURNING id INTO permanent_notification_id;

  INSERT INTO public.fcm_delivery_attempts (
    notification_id, profile_id, token, status, attempt_count
  ) VALUES (
    permanent_notification_id, fixture_profile_id, fixture_token, 'permanent_failure', 1
  );

  PERFORM set_config('tacresq.test_fcm_notification_id', new_notification_id::text, true);
  PERFORM set_config('tacresq.test_fcm_token', fixture_token, true);
END;
$fcm_queue_seed$;

-- First worker claims only the unattempted notification. A second worker cannot
-- claim it again while the lease is active.
SET LOCAL ROLE service_role;
DO $fcm_first_claim$
DECLARE
  expected_notification_id bigint := current_setting('tacresq.test_fcm_notification_id')::bigint;
  claimed_count integer;
  claimed_id bigint;
  claimed_attempt_count integer;
BEGIN
  SELECT count(*), min(notification_id), min(attempt_count)
    INTO claimed_count, claimed_id, claimed_attempt_count
    FROM public.api_claim_fcm_delivery_batch(50, 600);

  IF claimed_count <> 1 OR claimed_id <> expected_notification_id THEN
    RAISE EXCEPTION 'Expected only the new due notification to be claimed; count %, id %',
      claimed_count, claimed_id;
  END IF;
  IF claimed_attempt_count <> 1 THEN
    RAISE EXCEPTION 'First delivery claim must have attempt_count 1; found %', claimed_attempt_count;
  END IF;

  SELECT count(*) INTO claimed_count
    FROM public.api_claim_fcm_delivery_batch(100, 600);
  IF claimed_count <> 0 THEN
    RAISE EXCEPTION 'A second worker claim must not reclaim a live lease';
  END IF;
END;
$fcm_first_claim$;
RESET ROLE;

-- Failed work is excluded until its backoff expires, then is atomically re-claimed.
UPDATE public.fcm_delivery_attempts
   SET status = 'failed',
       next_attempt_at = statement_timestamp() + interval '5 minutes'
 WHERE notification_id = current_setting('tacresq.test_fcm_notification_id')::bigint
   AND token = current_setting('tacresq.test_fcm_token');

SET LOCAL ROLE service_role;
DO $fcm_backoff$
DECLARE
  claimed_count integer;
BEGIN
  SELECT count(*) INTO claimed_count
    FROM public.api_claim_fcm_delivery_batch(100, 600);
  IF claimed_count <> 0 THEN
    RAISE EXCEPTION 'A failed attempt must respect next_attempt_at backoff';
  END IF;
END;
$fcm_backoff$;
RESET ROLE;

UPDATE public.fcm_delivery_attempts
   SET next_attempt_at = statement_timestamp() - interval '1 second'
 WHERE notification_id = current_setting('tacresq.test_fcm_notification_id')::bigint
   AND token = current_setting('tacresq.test_fcm_token');

SET LOCAL ROLE service_role;
DO $fcm_retry$
DECLARE
  expected_notification_id bigint := current_setting('tacresq.test_fcm_notification_id')::bigint;
  claimed_count integer;
  claimed_id bigint;
  claimed_attempt_count integer;
BEGIN
  SELECT count(*), min(notification_id), min(attempt_count)
    INTO claimed_count, claimed_id, claimed_attempt_count
    FROM public.api_claim_fcm_delivery_batch(100, 600);

  IF claimed_count <> 1 OR claimed_id <> expected_notification_id THEN
    RAISE EXCEPTION 'A due failed attempt must be retried; count %, id %', claimed_count, claimed_id;
  END IF;
  IF claimed_attempt_count <> 2 THEN
    RAISE EXCEPTION 'A retry must increment attempt_count to 2; found %', claimed_attempt_count;
  END IF;
END;
$fcm_retry$;
RESET ROLE;

UPDATE public.fcm_delivery_attempts
   SET status = 'failed',
       attempt_count = 8,
       next_attempt_at = statement_timestamp() - interval '1 second'
 WHERE notification_id = current_setting('tacresq.test_fcm_notification_id')::bigint
   AND token = current_setting('tacresq.test_fcm_token');

SET LOCAL ROLE service_role;
DO $fcm_exhausted$
DECLARE
  claimed_count integer;
BEGIN
  SELECT count(*) INTO claimed_count
    FROM public.api_claim_fcm_delivery_batch(100, 600);
  IF claimed_count <> 0 THEN
    RAISE EXCEPTION 'Attempts at the retry ceiling must not be claimed again';
  END IF;
END;
$fcm_exhausted$;
RESET ROLE;

-- A crashed worker's expired lease may be reclaimed, but a claim already at
-- the eight-attempt ceiling must remain excluded even after its lease expires.
DO $fcm_lease_seed$
DECLARE
  fixture_profile_id bigint;
  stale_notification_id bigint;
  exhausted_notification_id bigint;
  fixture_token text := current_setting('tacresq.test_fcm_token');
BEGIN
  SELECT id INTO fixture_profile_id
    FROM public.profiles
   WHERE auth_user_id = 'f1000000-1111-4111-8111-000000000100';

  INSERT INTO public.user_notifications (
    recipient_user_id, incident_id, event_type, title, body
  ) VALUES (
    fixture_profile_id, NULL, 'incident_status_changed',
    'Expired delivery lease', 'A crashed worker lease can be recovered.'
  ) RETURNING id INTO stale_notification_id;

  INSERT INTO public.fcm_delivery_attempts (
    notification_id, profile_id, token, status, attempted_at, updated_at, attempt_count
  ) VALUES (
    stale_notification_id, fixture_profile_id, fixture_token, 'claimed',
    statement_timestamp() - interval '20 minutes',
    statement_timestamp() - interval '20 minutes',
    1
  );

  INSERT INTO public.user_notifications (
    recipient_user_id, incident_id, event_type, title, body
  ) VALUES (
    fixture_profile_id, NULL, 'incident_status_changed',
    'Expired attempt ceiling', 'This delivery has already exhausted retries.'
  ) RETURNING id INTO exhausted_notification_id;

  INSERT INTO public.fcm_delivery_attempts (
    notification_id, profile_id, token, status, attempted_at, updated_at, attempt_count
  ) VALUES (
    exhausted_notification_id, fixture_profile_id, fixture_token, 'claimed',
    statement_timestamp() - interval '20 minutes',
    statement_timestamp() - interval '20 minutes',
    8
  );

  PERFORM set_config('tacresq.test_fcm_stale_notification_id', stale_notification_id::text, true);
END;
$fcm_lease_seed$;

SET LOCAL ROLE service_role;
DO $fcm_expired_lease$
DECLARE
  expected_notification_id bigint := current_setting('tacresq.test_fcm_stale_notification_id')::bigint;
  claimed_count integer;
  claimed_id bigint;
  claimed_attempt_count integer;
BEGIN
  SELECT count(*), min(notification_id), min(attempt_count)
    INTO claimed_count, claimed_id, claimed_attempt_count
    FROM public.api_claim_fcm_delivery_batch(100, 600);

  IF claimed_count <> 1 OR claimed_id <> expected_notification_id THEN
    RAISE EXCEPTION 'Only an expired lease below the retry ceiling must be reclaimed; count %, id %',
      claimed_count, claimed_id;
  END IF;
  IF claimed_attempt_count <> 2 THEN
    RAISE EXCEPTION 'Reclaiming an expired lease must increment attempt_count; found %', claimed_attempt_count;
  END IF;

  SELECT count(*) INTO claimed_count
    FROM public.api_claim_fcm_delivery_batch(100, 600);
  IF claimed_count <> 0 THEN
    RAISE EXCEPTION 'A reclaimed lease must be fresh and the exhausted lease must stay excluded';
  END IF;
END;
$fcm_expired_lease$;
RESET ROLE;

ROLLBACK;
SELECT 'PASS: FCM claims are service-role-only, due/leased, retry-aware, and skip delivered or permanently failed inbox items.' AS result;
