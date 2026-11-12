-- Build the GPS rate-limit index without blocking live responder location inserts.
-- This migration intentionally has no explicit transaction: PostgreSQL forbids
-- CREATE INDEX CONCURRENTLY inside a transaction block.
CREATE INDEX CONCURRENTLY IF NOT EXISTS location_logs_received_rate_idx
  ON public.location_logs (
    user_id, incident_id, (COALESCE(received_at, recorded_at)) DESC
  );
