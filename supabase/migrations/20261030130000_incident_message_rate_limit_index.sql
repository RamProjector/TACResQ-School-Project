BEGIN;

-- The incident-chat flood guard counts a sender's messages from the last minute.
-- This composite index supports the sender equality predicate followed by the
-- created_at range, avoiding a scan of that sender's full message history.
CREATE INDEX IF NOT EXISTS incident_messages_sender_created_at_idx
  ON public.incident_messages (sender_id, created_at DESC);

COMMIT;
