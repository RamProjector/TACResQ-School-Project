BEGIN;

-- Keep incident_assignments as the current-membership table. This append-only
-- event stream preserves who joined or left a team and when the incident closed.
CREATE TABLE IF NOT EXISTS public.incident_assignment_events (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  incident_id bigint NOT NULL REFERENCES public.incidents(id) ON DELETE RESTRICT,
  user_id bigint NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
  event_type text NOT NULL CHECK (event_type IN ('assigned', 'released', 'incident_closed')),
  actor_user_id bigint REFERENCES public.profiles(id) ON DELETE SET NULL,
  source text NOT NULL CHECK (source IN ('assignment_insert', 'assignment_delete', 'incident_status')),
  assignment_status text,
  incident_status text NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS incident_assignment_events_incident_time_idx
  ON public.incident_assignment_events (incident_id, occurred_at, id);

CREATE INDEX IF NOT EXISTS incident_assignment_events_user_time_idx
  ON public.incident_assignment_events (user_id, occurred_at, id);

ALTER TABLE public.incident_assignment_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS incident_assignment_events_dispatch_read
  ON public.incident_assignment_events;

CREATE POLICY incident_assignment_events_dispatch_read
  ON public.incident_assignment_events
  FOR SELECT TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.profiles p
      WHERE p.auth_user_id = auth.uid()
        AND p.role IN ('dispatcher', 'admin')
    )
  );

REVOKE ALL ON TABLE public.incident_assignment_events FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.incident_assignment_events TO authenticated;

CREATE OR REPLACE FUNCTION private.tacresq_log_assignment_membership_event()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_id bigint;
  current_incident_status text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    SELECT i.status INTO current_incident_status
    FROM public.incidents i WHERE i.id = NEW.incident_id;

    INSERT INTO public.incident_assignment_events (
      incident_id, user_id, event_type, actor_user_id, source,
      assignment_status, incident_status
    ) VALUES (
      NEW.incident_id, NEW.user_id, 'assigned', NEW.assigned_by,
      'assignment_insert', NEW.responder_status,
      coalesce(current_incident_status, 'unknown')
    );
    RETURN NEW;
  END IF;

  SELECT p.id INTO actor_id
  FROM public.profiles p
  WHERE p.auth_user_id = auth.uid();

  SELECT i.status INTO current_incident_status
  FROM public.incidents i WHERE i.id = OLD.incident_id;

  INSERT INTO public.incident_assignment_events (
    incident_id, user_id, event_type, actor_user_id, source,
    assignment_status, incident_status
  ) VALUES (
    OLD.incident_id, OLD.user_id, 'released', actor_id,
    'assignment_delete', OLD.responder_status,
    coalesce(current_incident_status, 'unknown')
  );
  RETURN OLD;
END;
$function$;

CREATE OR REPLACE FUNCTION private.tacresq_log_incident_team_closure()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_id bigint;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status
     OR NEW.status NOT IN ('resolved', 'cancelled') THEN
    RETURN NEW;
  END IF;

  SELECT p.id INTO actor_id
  FROM public.profiles p
  WHERE p.auth_user_id = auth.uid();

  INSERT INTO public.incident_assignment_events (
    incident_id, user_id, event_type, actor_user_id, source,
    assignment_status, incident_status
  )
  SELECT a.incident_id, a.user_id, 'incident_closed', actor_id,
         'incident_status', a.responder_status, NEW.status
  FROM public.incident_assignments a
  WHERE a.incident_id = NEW.id;

  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_log_assignment_membership_event()
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION private.tacresq_log_incident_team_closure()
  FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS tacresq_assignment_membership_event
  ON public.incident_assignments;
CREATE TRIGGER tacresq_assignment_membership_event
  AFTER INSERT OR DELETE ON public.incident_assignments
  FOR EACH ROW EXECUTE FUNCTION private.tacresq_log_assignment_membership_event();

DROP TRIGGER IF EXISTS tacresq_incident_team_closure_event
  ON public.incidents;
CREATE TRIGGER tacresq_incident_team_closure_event
  AFTER UPDATE OF status ON public.incidents
  FOR EACH ROW EXECUTE FUNCTION private.tacresq_log_incident_team_closure();

COMMIT;
