-- Runtime security contract for the single-notification lookup used by push taps.
-- Runs only on the disposable PostgreSQL database created by migration replay.
BEGIN;

DO $notification_lookup_privileges$
DECLARE
  lookup_oid regprocedure := to_regprocedure('public.api_my_notification(bigint)');
  lookup_definer boolean;
  lookup_settings text[];
BEGIN
  IF lookup_oid IS NULL THEN
    RAISE EXCEPTION 'Caller-scoped notification lookup function is missing';
  END IF;
  IF NOT has_function_privilege('authenticated', lookup_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'authenticated must execute the caller-scoped notification lookup';
  END IF;
  IF has_function_privilege('anon', lookup_oid, 'EXECUTE')
     OR has_function_privilege('public', lookup_oid, 'EXECUTE') THEN
    RAISE EXCEPTION 'anon/PUBLIC must not execute the caller-scoped notification lookup';
  END IF;
  SELECT prosecdef, proconfig INTO lookup_definer, lookup_settings
    FROM pg_proc WHERE oid = lookup_oid;
  IF lookup_definer IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Notification lookup must remain SECURITY DEFINER';
  END IF;
  IF NOT (coalesce(lookup_settings, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog']) THEN
    RAISE EXCEPTION 'Notification lookup must pin search_path to pg_catalog';
  END IF;
  IF has_table_privilege('authenticated', 'public.user_notifications', 'SELECT') THEN
    RAISE EXCEPTION 'authenticated must not bypass the protected notification RPC';
  END IF;
END;
$notification_lookup_privileges$;

INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000160', 'notification-lookup-owner@example.test', '{"name":"Notification Lookup Owner","phone":"+639170000160"}'),
  ('00000000-0000-4000-8000-000000000161', 'notification-lookup-outsider@example.test', '{"name":"Notification Lookup Outsider","phone":"+639170000161"}');

INSERT INTO public.user_notifications (
  recipient_user_id, incident_id, event_type, title, body
)
SELECT p.id, NULL, 'incident_status_changed', 'Disposable notification lookup', 'Private notification lookup test.'
  FROM public.profiles p
 WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000160';

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000160', true);
SET LOCAL ROLE authenticated;
DO $owner_can_read$
DECLARE
  notification_id bigint;
  payload jsonb;
BEGIN
  SELECT n.id INTO notification_id
    FROM public.user_notifications n
    JOIN public.profiles p ON p.id = n.recipient_user_id
   WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000160'
     AND n.title = 'Disposable notification lookup';
  payload := public.api_my_notification(notification_id);
  IF payload IS NULL OR payload->>'id' IS DISTINCT FROM notification_id::text THEN
    RAISE EXCEPTION 'The notification owner must receive their exact notification row';
  END IF;
  IF payload->>'title' IS DISTINCT FROM 'Disposable notification lookup' THEN
    RAISE EXCEPTION 'The notification owner received an unexpected payload';
  END IF;
END;
$owner_can_read$;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000161', true);
DO $outsider_cannot_read$
DECLARE
  notification_id bigint;
  payload jsonb;
BEGIN
  SELECT n.id INTO notification_id
    FROM public.user_notifications n
    JOIN public.profiles p ON p.id = n.recipient_user_id
   WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000160'
     AND n.title = 'Disposable notification lookup';
  payload := public.api_my_notification(notification_id);
  IF payload IS NOT NULL THEN
    RAISE EXCEPTION 'A different authenticated profile must not read this notification';
  END IF;
END;
$outsider_cannot_read$;

RESET ROLE;
ROLLBACK;

SELECT 'PASS: notification lookup grants, caller scoping, protected table access, and non-disclosing not-found behavior.' AS result;
