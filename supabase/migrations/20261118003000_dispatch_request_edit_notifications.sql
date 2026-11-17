BEGIN;

-- A requester edit and its dispatch alert must commit or roll back together.
-- The current schema has one shared dispatcher queue (no organization scope),
-- so every dispatcher account receives the event. Keep the alert generic:
-- the incident detail remains available only through the authenticated queue.
ALTER TABLE public.user_notifications
  ADD COLUMN IF NOT EXISTS source_event_id bigint
    REFERENCES public.incident_request_edit_logs(id) ON DELETE CASCADE;

ALTER TABLE public.user_notifications
  DROP CONSTRAINT IF EXISTS user_notifications_event_type_check;

ALTER TABLE public.user_notifications
  ADD CONSTRAINT user_notifications_event_type_check
  CHECK (event_type IN (
    'incident_assigned',
    'incident_status_changed',
    'incident_message',
    'incident_request_updated'
  ));

-- One alert per dispatcher per edit-log row. This also makes a retried trigger
-- safe without suppressing alerts for later corrections to the same incident.
CREATE UNIQUE INDEX IF NOT EXISTS user_notifications_request_edit_event_unique_idx
  ON public.user_notifications (recipient_user_id, source_event_id)
  WHERE source_event_id IS NOT NULL;

CREATE OR REPLACE FUNCTION private.tacresq_notify_requester_edit()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  INSERT INTO public.user_notifications (
    recipient_user_id,
    incident_id,
    event_type,
    title,
    body,
    source_event_id
  )
  SELECT
    dispatcher.id,
    NEW.incident_id,
    'incident_request_updated',
    'Emergency details updated',
    'A requester corrected an emergency report. Open the dispatch queue to review the latest details.',
    NEW.id
  FROM public.profiles AS dispatcher
  WHERE dispatcher.role = 'dispatcher'
    AND dispatcher.id IS DISTINCT FROM NEW.requester_id
  ON CONFLICT (recipient_user_id, source_event_id)
    WHERE source_event_id IS NOT NULL
    DO NOTHING;

  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_notify_requester_edit()
  FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS tacresq_notify_requester_edit
  ON public.incident_request_edit_logs;

CREATE TRIGGER tacresq_notify_requester_edit
AFTER INSERT ON public.incident_request_edit_logs
FOR EACH ROW
EXECUTE FUNCTION private.tacresq_notify_requester_edit();

COMMIT;
