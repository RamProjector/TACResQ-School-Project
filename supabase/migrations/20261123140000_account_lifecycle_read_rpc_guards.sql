BEGIN;

-- These SECURITY DEFINER read RPCs bypass ordinary RLS. Resolve the caller
-- through the account-aware identity helper and keep its profile share lock
-- for the duration of the read transaction.
CREATE OR REPLACE FUNCTION public.api_my_notifications(p_limit integer DEFAULT 50)
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
BEGIN
  actor_profile_id := private.tacresq_auth_profile_id();

  IF actor_profile_id IS NULL THEN
    RAISE EXCEPTION 'Authenticated profile is required'
      USING ERRCODE = '42501';
  END IF;

  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 100 THEN
    RAISE EXCEPTION 'Notification page size must be between 1 and 100'
      USING ERRCODE = '22023';
  END IF;

  RETURN (
    SELECT coalesce(
      jsonb_agg(
        jsonb_build_object(
          'id', n.id,
          'incident_id', n.incident_id,
          'event_type', n.event_type,
          'title', n.title,
          'body', n.body,
          'created_at', n.created_at,
          'read_at', n.read_at
        ) ORDER BY n.created_at DESC, n.id DESC
      ),
      '[]'::jsonb
    )
    FROM (
      SELECT id, incident_id, event_type, title, body, created_at, read_at
        FROM public.user_notifications n
       WHERE n.recipient_user_id = actor_profile_id
       ORDER BY n.created_at DESC, n.id DESC
       LIMIT p_limit
    ) n
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.api_my_notifications(integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_my_notifications(integer) TO authenticated;

CREATE OR REPLACE FUNCTION public.api_my_notification(p_notification_id bigint)
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
  notification_payload jsonb;
BEGIN
  actor_profile_id := private.tacresq_auth_profile_id();

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

CREATE OR REPLACE FUNCTION public.api_my_incident_messages(
  p_incident_id bigint,
  p_limit integer DEFAULT 50,
  p_before_id bigint DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
BEGIN
  actor_profile_id := private.tacresq_auth_profile_id();

  IF actor_profile_id IS NULL THEN
    RAISE EXCEPTION 'Authenticated profile is required'
      USING ERRCODE = '42501';
  END IF;

  IF p_incident_id IS NULL OR p_incident_id < 1
     OR p_limit IS NULL OR p_limit < 1 OR p_limit > 100
     OR (p_before_id IS NOT NULL AND p_before_id < 1) THEN
    RAISE EXCEPTION 'Message page parameters are invalid'
      USING ERRCODE = '22023';
  END IF;

  IF NOT private.tacresq_can_access_incident_chat(p_incident_id, actor_profile_id) THEN
    RAISE EXCEPTION 'Incident chat is not available to this account'
      USING ERRCODE = '42501';
  END IF;

  RETURN (
    SELECT coalesce(
      jsonb_agg(
        jsonb_build_object(
          'id', page.id,
          'incident_id', page.incident_id,
          'sender_id', page.sender_id,
          'sender_name', page.sender_name,
          'message', page.message,
          'created_at', page.created_at,
          'attachment_path', page.attachment_path,
          'attachment_mime_type', page.attachment_mime_type,
          'attachment_size_bytes', page.attachment_size_bytes
        ) ORDER BY page.id ASC
      ),
      '[]'::jsonb
    )
    FROM (
      SELECT m.id, m.incident_id, m.sender_id, p.name AS sender_name,
             m.message, m.created_at, m.attachment_path,
             m.attachment_mime_type, m.attachment_size_bytes
        FROM public.incident_messages m
        JOIN public.profiles p ON p.id = m.sender_id
       WHERE m.incident_id = p_incident_id
         AND (p_before_id IS NULL OR m.id < p_before_id)
       ORDER BY m.id DESC
       LIMIT p_limit
    ) AS page
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.api_my_incident_messages(bigint, integer, bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_my_incident_messages(bigint, integer, bigint) TO authenticated;

-- Claimable-incident discovery is also a SECURITY DEFINER read path. It must
-- not reveal incident queue details to a suspended field-unit account.
CREATE OR REPLACE FUNCTION public.api_claimable_incidents()
RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_auth_uid uuid;
  v_actor_id bigint;
  v_actor_role text;
  v_duty_status text;
  v_result jsonb;
BEGIN
  v_auth_uid := auth.uid();
  IF v_auth_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required.' USING ERRCODE = '42501';
  END IF;

  SELECT id, role, duty_status
  INTO v_actor_id, v_actor_role, v_duty_status
  FROM public.profiles
  WHERE auth_user_id = v_auth_uid
    AND account_status = 'active'
  FOR SHARE;

  IF v_actor_id IS NULL OR v_actor_role IS NULL OR v_actor_role NOT IN ('responder', 'driver')
     OR v_duty_status IS DISTINCT FROM 'available' THEN
    RETURN '[]'::jsonb;
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.incident_assignments a
    JOIN public.incidents active_i ON active_i.id = a.incident_id
    WHERE a.user_id = v_actor_id
      AND active_i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) OR EXISTS (
    SELECT 1
    FROM public.incidents active_i
    WHERE active_i.assigned_to = v_actor_id
      AND active_i.status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) THEN
    RETURN '[]'::jsonb;
  END IF;

  SELECT COALESCE(jsonb_agg(
    jsonb_build_object(
      'id', i.id,
      'type', i.type,
      'description', i.description,
      'latitude', i.latitude,
      'longitude', i.longitude,
      'status', i.status,
      'is_claimable', i.is_claimable,
      'created_at', i.created_at,
      'updated_at', i.updated_at,
      'assigned_user_ids', COALESCE((
        SELECT jsonb_agg(a.user_id ORDER BY a.assigned_at, a.user_id)
        FROM public.incident_assignments a WHERE a.incident_id = i.id
      ), '[]'::jsonb)
    ) ORDER BY i.created_at ASC
  ), '[]'::jsonb) INTO v_result
  FROM public.incidents i
  WHERE i.is_claimable = true
    AND i.status IN ('pending', 'assigned')
    AND i.assigned_to IS DISTINCT FROM v_actor_id
    AND NOT EXISTS (
      SELECT 1
      FROM public.incident_assignments own_assignment
      WHERE own_assignment.incident_id = i.id
        AND own_assignment.user_id = v_actor_id
    )
    AND (
      SELECT count(*) + CASE
        WHEN i.assigned_to IS NOT NULL AND NOT EXISTS (
          SELECT 1 FROM public.incident_assignments legacy_lead
          WHERE legacy_lead.incident_id = i.id AND legacy_lead.user_id = i.assigned_to
        ) THEN 1
        ELSE 0
      END
      FROM public.incident_assignments a
      WHERE a.incident_id = i.id
    ) < 5;

  RETURN v_result;
END;
$function$;
REVOKE ALL ON FUNCTION public.api_claimable_incidents() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_claimable_incidents() TO authenticated;

COMMIT;
