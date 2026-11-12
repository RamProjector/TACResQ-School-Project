BEGIN;

-- A push notification is a locator, never an authorization grant. This RPC
-- returns exactly one row only when it belongs to auth.uid()'s profile.
CREATE OR REPLACE FUNCTION public.api_my_notification(p_notification_id bigint)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
  notification_payload jsonb;
BEGIN
  SELECT p.id
    INTO actor_profile_id
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid();

  IF actor_profile_id IS NULL THEN
    RAISE EXCEPTION 'Authenticated profile is required'
      USING ERRCODE = '42501';
  END IF;

  IF p_notification_id IS NULL OR p_notification_id < 1 THEN
    RAISE EXCEPTION 'Notification ID is invalid'
      USING ERRCODE = '22023';
  END IF;

  SELECT jsonb_build_object(
    'id', n.id,
    'incident_id', n.incident_id,
    'event_type', n.event_type,
    'title', n.title,
    'body', n.body,
    'created_at', n.created_at,
    'read_at', n.read_at
  )
    INTO notification_payload
    FROM public.user_notifications n
   WHERE n.id = p_notification_id
     AND n.recipient_user_id = actor_profile_id;

  -- A missing row and a row owned by another profile are indistinguishable.
  RETURN notification_payload;
END;
$function$;

REVOKE ALL ON FUNCTION public.api_my_notification(bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_my_notification(bigint) TO authenticated;

COMMIT;
