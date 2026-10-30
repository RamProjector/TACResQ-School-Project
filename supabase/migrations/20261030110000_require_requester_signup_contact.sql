-- Public requester signup must include the contact details required for
-- emergency coordination. Ignore any client-supplied role and always create
-- requester profiles; administrator provisioning remains operator-only.
CREATE OR REPLACE FUNCTION public.tacresq_profile_on_signup()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  full_name text := NULLIF(pg_catalog.btrim(NEW.raw_user_meta_data ->> 'name'), '');
  phone_value text := NULLIF(pg_catalog.btrim(NEW.raw_user_meta_data ->> 'phone'), '');
  phone_digits text;
BEGIN
  IF NEW.email IS NULL OR pg_catalog.btrim(NEW.email) = '' THEN
    RAISE EXCEPTION 'A valid email address is required for requester registration'
      USING ERRCODE = '22023';
  END IF;

  IF full_name IS NULL THEN
    RAISE EXCEPTION 'A full name is required for requester registration'
      USING ERRCODE = '22023';
  END IF;

  IF phone_value IS NULL OR phone_value !~ '^[0-9+() -]+$' THEN
    RAISE EXCEPTION 'A valid mobile number is required for requester registration'
      USING ERRCODE = '22023';
  END IF;

  phone_digits := pg_catalog.regexp_replace(phone_value, '[^0-9]', '', 'g');
  IF pg_catalog.length(phone_digits) NOT BETWEEN 7 AND 15 THEN
    RAISE EXCEPTION 'Requester mobile numbers must contain 7 to 15 digits'
      USING ERRCODE = '22023';
  END IF;

  INSERT INTO public.profiles(auth_user_id, name, email, phone, role, duty_status)
  VALUES (
    NEW.id,
    full_name,
    pg_catalog.lower(pg_catalog.btrim(NEW.email)),
    phone_value,
    'requester',
    NULL
  )
  ON CONFLICT (auth_user_id) DO NOTHING;

  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION public.tacresq_profile_on_signup() FROM PUBLIC, anon, authenticated;
