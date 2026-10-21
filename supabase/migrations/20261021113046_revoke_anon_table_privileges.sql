-- Remove direct anonymous/PUBLIC access to TACResQ application tables and identity sequences.
-- RLS remains enabled as a second authorization layer; this migration makes the
-- anonymous role fail at the SQL privilege layer before row policies are evaluated.
-- Authenticated grants are intentionally preserved for existing invoker RPCs and
-- role-scoped client operations.

REVOKE ALL PRIVILEGES
  ON TABLE public.profiles,
            public.incidents,
            public.location_logs,
            public.incident_status_logs,
            public.fcm_tokens
  FROM PUBLIC, anon;

REVOKE ALL PRIVILEGES
  ON SEQUENCE public.profiles_id_seq,
               public.incidents_id_seq,
               public.location_logs_id_seq,
               public.incident_status_logs_id_seq,
               public.fcm_tokens_id_seq
  FROM PUBLIC, anon;
