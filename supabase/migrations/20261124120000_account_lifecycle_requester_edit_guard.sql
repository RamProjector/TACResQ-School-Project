BEGIN;

-- The legacy requester edit body performs the detailed ownership, assignment,
-- validation, cooldown, and immutable edit-history checks. Keep that body
-- intact behind a private implementation and add the lifecycle check at the
-- public callable boundary before it can read or mutate request data.
ALTER FUNCTION public.api_update_my_incident(
  bigint, text, text, double precision, double precision, real,
  text, timestamptz, text, boolean
) SET SCHEMA private;

ALTER FUNCTION private.api_update_my_incident(
  bigint, text, text, double precision, double precision, real,
  text, timestamptz, text, boolean
) RENAME TO tacresq_update_my_incident_internal;

REVOKE ALL ON FUNCTION private.tacresq_update_my_incident_internal(
  bigint, text, text, double precision, double precision, real,
  text, timestamptz, text, boolean
) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.api_update_my_incident(
  p_id bigint,
  p_type text,
  p_description text,
  p_latitude double precision,
  p_longitude double precision,
  p_accuracy_m real,
  p_location_notes text,
  p_location_recorded_at timestamptz,
  p_location_source text,
  p_confirm_review boolean DEFAULT false
) RETURNS jsonb
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_id bigint;
  actor_role text;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Authentication required'
      USING ERRCODE = '42501';
  END IF;

  -- Hold the profile lock through the internal edit transaction. Account
  -- suspension updates this same row, so either suspension commits first and
  -- this call is denied, or the edit completes before suspension commits.
  SELECT p.id, p.role
    INTO actor_id, actor_role
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
     AND p.account_status = 'active'
   FOR SHARE;

  IF actor_id IS NULL OR actor_role IS DISTINCT FROM 'requester' THEN
    RAISE EXCEPTION 'Only active requesters can edit their own requests'
      USING ERRCODE = '42501';
  END IF;

  RETURN private.tacresq_update_my_incident_internal(
    p_id, p_type, p_description, p_latitude, p_longitude,
    p_accuracy_m, p_location_notes, p_location_recorded_at,
    p_location_source, p_confirm_review
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.api_update_my_incident(
  bigint, text, text, double precision, double precision, real,
  text, timestamptz, text, boolean
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_update_my_incident(
  bigint, text, text, double precision, double precision, real,
  text, timestamptz, text, boolean
) TO authenticated;

COMMIT;
