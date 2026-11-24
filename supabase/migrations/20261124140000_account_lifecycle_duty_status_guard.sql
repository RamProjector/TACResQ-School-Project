BEGIN;

-- Duty status is operational state, not account lifecycle state. A suspended
-- responder/driver must not change it with a still-valid JWT. The profile row
-- lock serializes this mutation with the Admin suspension transition.
CREATE OR REPLACE FUNCTION public.api_set_duty_status(p_duty_status text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $function$
DECLARE
  p public.profiles;
BEGIN
  IF p_duty_status IS NULL OR p_duty_status NOT IN ('available', 'off_duty') THEN
    RAISE EXCEPTION 'Duty status may only be set to available or off duty'
      USING ERRCODE = '22023';
  END IF;

  SELECT * INTO p
  FROM public.profiles
  WHERE auth_user_id = auth.uid()
  FOR UPDATE;

  IF p.id IS NULL
     OR p.role NOT IN ('responder', 'driver')
     OR p.account_status IS DISTINCT FROM 'active' THEN
    RAISE EXCEPTION 'Only active responders and drivers can change duty status'
      USING ERRCODE = '42501';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.incidents
    WHERE assigned_to = p.id
      AND status IN ('assigned', 'en_route', 'on_scene', 'transporting')
  ) THEN
    RAISE EXCEPTION 'Finish your active incident first'
      USING ERRCODE = '22023';
  END IF;

  UPDATE public.profiles
  SET duty_status = p_duty_status,
      updated_at = now()
  WHERE id = p.id
  RETURNING * INTO p;

  RETURN jsonb_build_object(
    'id', p.id,
    'name', p.name,
    'email', p.email,
    'phone', p.phone,
    'role', p.role,
    'duty_status', p.duty_status
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.api_set_duty_status(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_set_duty_status(text) TO authenticated;

COMMIT;
