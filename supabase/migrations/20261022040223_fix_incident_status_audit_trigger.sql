-- Fix SOS creation: a BEFORE INSERT trigger cannot insert a child status-log
-- row that references the incident row before PostgreSQL has inserted that row.
-- Keep timestamp maintenance BEFORE UPDATE, but write audit records AFTER changes.

BEGIN;

CREATE OR REPLACE FUNCTION public.tacresq_log_incident_status()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_id bigint;
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO public.incident_status_logs(incident_id, status, changed_by)
    VALUES (NEW.id, NEW.status, NEW.requester_id);
  ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
    SELECT id
      INTO actor_id
      FROM public.profiles
     WHERE auth_user_id = auth.uid();

    INSERT INTO public.incident_status_logs(incident_id, status, changed_by)
    VALUES (NEW.id, NEW.status, actor_id);

    IF NEW.assigned_to IS NOT NULL
       AND NEW.status IN ('en_route', 'on_scene', 'transporting', 'resolved') THEN
      UPDATE public.profiles
         SET duty_status = CASE NEW.status
             WHEN 'resolved' THEN 'available'
             ELSE NEW.status
           END,
             updated_at = now()
       WHERE id = NEW.assigned_to;
    END IF;
  END IF;

  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION public.tacresq_log_incident_status() FROM PUBLIC, anon, authenticated;

-- The parent incident now exists when the FK in incident_status_logs is checked.
DROP TRIGGER IF EXISTS tacresq_incident_status_audit ON public.incidents;
CREATE TRIGGER tacresq_incident_status_audit
  AFTER INSERT OR UPDATE ON public.incidents
  FOR EACH ROW
  EXECUTE FUNCTION public.tacresq_log_incident_status();

-- AFTER triggers cannot change NEW, so maintain updated_at separately before updates.
CREATE OR REPLACE FUNCTION public.tacresq_touch_incident_updated_at()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION public.tacresq_touch_incident_updated_at() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS tacresq_incident_touch_updated_at ON public.incidents;
CREATE TRIGGER tacresq_incident_touch_updated_at
  BEFORE UPDATE ON public.incidents
  FOR EACH ROW
  EXECUTE FUNCTION public.tacresq_touch_incident_updated_at();

COMMIT;
