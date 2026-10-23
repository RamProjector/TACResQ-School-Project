BEGIN;

-- Record whether an edit bypassed the cooldown solely to correct an incorrect pin.
ALTER TABLE public.incident_request_edit_logs
  ADD COLUMN IF NOT EXISTS urgent_location_correction boolean NOT NULL DEFAULT false;

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
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  profile_row public.profiles;
  incident_row public.incidents;
  before_values jsonb;
  after_values jsonb;
  cooldown_seconds integer;
  location_only_correction boolean := false;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO profile_row
    FROM public.profiles
   WHERE auth_user_id = auth.uid()
   FOR UPDATE;

  IF profile_row.id IS NULL OR profile_row.role <> 'requester' THEN
    RAISE EXCEPTION 'Only requesters can edit their own requests' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO incident_row
    FROM public.incidents
   WHERE id = p_id AND requester_id = profile_row.id
   FOR UPDATE;

  IF incident_row.id IS NULL THEN
    RAISE EXCEPTION 'Request not found' USING ERRCODE = 'P0002';
  END IF;

  IF incident_row.status <> 'pending' OR incident_row.assigned_to IS NOT NULL THEN
    RAISE EXCEPTION 'This request can only be edited before dispatch assignment. Contact dispatch for urgent corrections.'
      USING ERRCODE = '22023';
  END IF;

  IF p_type NOT IN ('medical', 'fire', 'vehicular', 'disaster', 'other') THEN
    RAISE EXCEPTION 'Choose a valid emergency type' USING ERRCODE = '22023';
  END IF;
  IF length(coalesce(p_description, '')) > 2000 THEN
    RAISE EXCEPTION 'Emergency details must be 2000 characters or fewer' USING ERRCODE = '22023';
  END IF;
  IF length(coalesce(p_location_notes, '')) > 500 THEN
    RAISE EXCEPTION 'Location notes must be 500 characters or fewer' USING ERRCODE = '22023';
  END IF;
  IF (p_latitude IS NULL) <> (p_longitude IS NULL) THEN
    RAISE EXCEPTION 'Both latitude and longitude are required for a map pin' USING ERRCODE = '22023';
  END IF;
  IF p_latitude IS NOT NULL AND (p_latitude < -90 OR p_latitude > 90 OR p_longitude < -180 OR p_longitude > 180) THEN
    RAISE EXCEPTION 'The selected map pin is invalid' USING ERRCODE = '22023';
  END IF;
  IF p_accuracy_m IS NOT NULL AND p_accuracy_m < 0 THEN
    RAISE EXCEPTION 'Location accuracy cannot be negative' USING ERRCODE = '22023';
  END IF;
  IF p_location_source NOT IN ('none', 'gps', 'manual') THEN
    RAISE EXCEPTION 'Choose a valid location source' USING ERRCODE = '22023';
  END IF;
  IF p_latitude IS NULL AND (p_location_source <> 'none' OR p_accuracy_m IS NOT NULL OR p_location_recorded_at IS NOT NULL) THEN
    RAISE EXCEPTION 'Location metadata must be cleared when no pin is attached' USING ERRCODE = '22023';
  END IF;
  IF p_latitude IS NOT NULL AND p_location_source = 'none' THEN
    RAISE EXCEPTION 'A location pin must identify whether it came from GPS or a selected map pin' USING ERRCODE = '22023';
  END IF;

  -- A wrong emergency pin is safety-critical. Allow a location-only correction
  -- during the 60-second cooldown, but require review confirmation and audit it.
  location_only_correction :=
    incident_row.type IS NOT DISTINCT FROM p_type
    AND incident_row.description IS NOT DISTINCT FROM nullif(trim(p_description), '')
    AND incident_row.location_notes IS NOT DISTINCT FROM nullif(trim(p_location_notes), '')
    AND (
      incident_row.latitude IS DISTINCT FROM p_latitude
      OR incident_row.longitude IS DISTINCT FROM p_longitude
    );

  IF incident_row.requester_edit_count > 0 THEN
    cooldown_seconds := greatest(
      0,
      ceil(extract(epoch FROM (incident_row.requester_last_edited_at + interval '60 seconds' - clock_timestamp())))::integer
    );
    IF cooldown_seconds > 0 AND NOT (location_only_correction AND coalesce(p_confirm_review, false)) THEN
      RAISE EXCEPTION 'You can edit this request again in % seconds. A location-only correction can be reviewed and saved now.', cooldown_seconds USING ERRCODE = '22023';
    END IF;
    IF NOT coalesce(p_confirm_review, false) THEN
      RAISE EXCEPTION 'Review the updated emergency details and confirm before saving another edit' USING ERRCODE = '22023';
    END IF;
  END IF;

  before_values := jsonb_build_object(
    'type', incident_row.type,
    'description', incident_row.description,
    'latitude', incident_row.latitude,
    'longitude', incident_row.longitude,
    'accuracy_m', incident_row.accuracy_m,
    'location_notes', incident_row.location_notes,
    'location_recorded_at', incident_row.location_recorded_at,
    'location_source', incident_row.location_source
  );

  UPDATE public.incidents
     SET type = p_type,
         description = nullif(trim(p_description), ''),
         latitude = p_latitude,
         longitude = p_longitude,
         accuracy_m = p_accuracy_m,
         location_notes = nullif(trim(p_location_notes), ''),
         location_recorded_at = p_location_recorded_at,
         location_source = p_location_source,
         requester_edit_count = requester_edit_count + 1,
         requester_last_edited_at = clock_timestamp()
   WHERE id = incident_row.id
   RETURNING * INTO incident_row;

  after_values := jsonb_build_object(
    'type', incident_row.type,
    'description', incident_row.description,
    'latitude', incident_row.latitude,
    'longitude', incident_row.longitude,
    'accuracy_m', incident_row.accuracy_m,
    'location_notes', incident_row.location_notes,
    'location_recorded_at', incident_row.location_recorded_at,
    'location_source', incident_row.location_source
  );

  INSERT INTO public.incident_request_edit_logs(
    incident_id, requester_id, edit_number, before_values, after_values,
    urgent_location_correction
  ) VALUES (
    incident_row.id, profile_row.id, incident_row.requester_edit_count, before_values, after_values,
    location_only_correction AND coalesce(p_confirm_review, false)
  );

  RETURN jsonb_build_object(
    'id', incident_row.id,
    'type', incident_row.type,
    'description', incident_row.description,
    'latitude', incident_row.latitude,
    'longitude', incident_row.longitude,
    'accuracy_m', incident_row.accuracy_m,
    'location_notes', incident_row.location_notes,
    'location_recorded_at', incident_row.location_recorded_at,
    'location_source', incident_row.location_source,
    'status', incident_row.status,
    'requester_name', profile_row.name,
    'requester_phone', profile_row.phone,
    'assigned_to', incident_row.assigned_to,
    'created_at', incident_row.created_at,
    'requester_edit_count', incident_row.requester_edit_count,
    'requester_last_edited_at', incident_row.requester_last_edited_at
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.api_update_my_incident(bigint, text, text, double precision, double precision, real, text, timestamptz, text, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_update_my_incident(bigint, text, text, double precision, double precision, real, text, timestamptz, text, boolean) TO authenticated;



COMMIT;
