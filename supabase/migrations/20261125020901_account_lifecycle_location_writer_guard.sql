BEGIN;

-- Location writes are a safety-relevant operation and the private definer
-- implementation is directly callable. Keep its validated write/rate-limit
-- logic private, but place an active-account profile lock at the callable edge.
ALTER FUNCTION private.tacresq_record_location_v2(
  bigint, double precision, double precision, real, timestamptz
) RENAME TO tacresq_record_location_v2_internal;
REVOKE ALL ON FUNCTION private.tacresq_record_location_v2_internal(
  bigint, double precision, double precision, real, timestamptz
) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION private.tacresq_record_location_v2(
  p_incident_id bigint,
  p_latitude double precision,
  p_longitude double precision,
  p_accuracy_m real,
  p_recorded_at timestamptz
)
RETURNS bigint
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_id bigint;
  actor_role text;
BEGIN
  SELECT p.id, p.role
    INTO actor_id, actor_role
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid()
     AND p.account_status = 'active'
   FOR SHARE;

  IF actor_id IS NULL OR actor_role NOT IN ('responder', 'driver') THEN
    RAISE EXCEPTION 'Only active responders and drivers can share incident locations'
      USING ERRCODE = '42501';
  END IF;

  RETURN private.tacresq_record_location_v2_internal(
    p_incident_id, p_latitude, p_longitude, p_accuracy_m, p_recorded_at
  );
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_record_location_v2(
  bigint, double precision, double precision, real, timestamptz
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_record_location_v2(
  bigint, double precision, double precision, real, timestamptz
) TO authenticated;

COMMIT;
