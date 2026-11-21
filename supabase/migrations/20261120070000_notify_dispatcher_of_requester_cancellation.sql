BEGIN;

-- Pending requester cancellations have no assigned field-unit recipients.
-- Notify dispatchers explicitly so a withdrawn emergency is visible in their
-- durable inbox even though the incident is removed from the pending queue.
ALTER TABLE public.user_notifications
  DROP CONSTRAINT IF EXISTS user_notifications_event_type_check;

ALTER TABLE public.user_notifications
  ADD CONSTRAINT user_notifications_event_type_check
  CHECK (event_type IN (
    'incident_assigned',
    'incident_status_changed',
    'incident_message',
    'incident_request_updated',
    'incident_cancelled'
  ));

-- A cancellation is terminal and occurs once per incident. Keep one durable
-- dispatcher alert per incident, including after the alert has been read.
CREATE UNIQUE INDEX IF NOT EXISTS user_notifications_one_cancellation_alert_idx
  ON public.user_notifications (recipient_user_id, incident_id)
  WHERE event_type = 'incident_cancelled';

CREATE OR REPLACE FUNCTION private.tacresq_notify_status_log_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  incident_requester_id bigint;
  recipient record;
  notification_title text;
  notification_body text;
BEGIN
  -- Initial request creation and the assignment status are covered by the
  -- requester flow and assignment trigger respectively; avoid duplicate alerts.
  IF NEW.status IN ('pending', 'assigned') THEN
    RETURN NEW;
  END IF;

  SELECT i.requester_id
    INTO incident_requester_id
    FROM public.incidents i
   WHERE i.id = NEW.incident_id;

  IF incident_requester_id IS NULL THEN
    RETURN NEW;
  END IF;

  -- An unassigned pending incident has no field-unit participants. Send a
  -- generic queue-change alert to dispatchers, excluding the actor if a
  -- dispatcher performed a future cancellation workflow.
  IF NEW.status = 'cancelled' THEN
    INSERT INTO public.user_notifications (
      recipient_user_id, incident_id, event_type, title, body
    )
    SELECT
      dispatcher.id,
      NEW.incident_id,
      'incident_cancelled',
      'Requester cancelled an emergency',
      'A requester cancelled a pending emergency. Open the dispatch queue to review the updated queue.'
    FROM public.profiles dispatcher
    WHERE dispatcher.role = 'dispatcher'
      AND dispatcher.id IS DISTINCT FROM NEW.changed_by
    ON CONFLICT (recipient_user_id, incident_id)
      WHERE event_type = 'incident_cancelled'
      DO NOTHING;

    RETURN NEW;
  END IF;

  notification_title := 'Incident status updated';
  notification_body := 'The incident status is now ' || replace(NEW.status::text, '_', ' ') || '.';

  FOR recipient IN
    SELECT participant.profile_id
      FROM (
        SELECT incident_requester_id AS profile_id
        UNION
        SELECT a.user_id AS profile_id
          FROM public.incident_assignments a
         WHERE a.incident_id = NEW.incident_id
      ) AS participant
     WHERE participant.profile_id IS NOT NULL
       AND participant.profile_id IS DISTINCT FROM NEW.changed_by
  LOOP
    INSERT INTO public.user_notifications (
      recipient_user_id, incident_id, event_type, title, body
    ) VALUES (
      recipient.profile_id, NEW.incident_id, 'incident_status_changed',
      notification_title, notification_body
    );
  END LOOP;

  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_notify_status_log_insert()
  FROM PUBLIC, anon, authenticated;

COMMIT;
