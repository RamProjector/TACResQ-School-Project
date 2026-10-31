BEGIN;

-- The incident-chat flood guard counts a sender's messages from the last minute.
-- This composite index supports the sender equality predicate followed by the
-- created_at range, avoiding a scan of that sender's full message history.
CREATE INDEX IF NOT EXISTS incident_messages_sender_created_at_idx
  ON public.incident_messages (sender_id, created_at DESC);

DO $chat_rate_index_contract$
DECLARE
  actual_index_definition text;
BEGIN
  SELECT indexdef INTO actual_index_definition
    FROM pg_catalog.pg_indexes
   WHERE schemaname = 'public'
     AND indexname = 'incident_messages_sender_created_at_idx';

  IF actual_index_definition IS NULL
     OR actual_index_definition NOT ILIKE '%(sender_id, created_at DESC)%' THEN
    RAISE EXCEPTION 'Incident chat sender/time rate-limit index is missing or has the wrong key order: %',
      actual_index_definition;
  END IF;
END;
$chat_rate_index_contract$;

COMMIT;
