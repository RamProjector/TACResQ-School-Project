-- Every newly submitted emergency needs a usable location, but GPS permission
-- remains optional: a requester may provide a manually selected pin or a landmark.
-- This trigger protects the invariant even when a client bypasses the Android form.
CREATE OR REPLACE FUNCTION public.tacresq_require_incident_location()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
  IF (NEW.latitude IS NULL) <> (NEW.longitude IS NULL) THEN
    RAISE EXCEPTION 'Latitude and longitude must be supplied together'
      USING ERRCODE = '22023';
  END IF;

  IF NEW.latitude IS NULL
     AND NULLIF(pg_catalog.btrim(NEW.location_notes), '') IS NULL THEN
    RAISE EXCEPTION 'A location pin or landmark/access detail is required'
      USING ERRCODE = '22023';
  END IF;

  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS incidents_require_location_before_insert ON public.incidents;
CREATE TRIGGER incidents_require_location_before_insert
BEFORE INSERT ON public.incidents
FOR EACH ROW
EXECUTE FUNCTION public.tacresq_require_incident_location();
