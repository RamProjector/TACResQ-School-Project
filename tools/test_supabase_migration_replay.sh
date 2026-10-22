#!/usr/bin/env bash
set -euo pipefail

: "${PGHOST:?PGHOST is required}"
: "${PGPORT:?PGPORT is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${PGDATABASE:?PGDATABASE is required}"

psql -v ON_ERROR_STOP=1 <<'SQL'
CREATE ROLE anon NOLOGIN;
CREATE ROLE authenticated NOLOGIN;
CREATE ROLE service_role NOLOGIN BYPASSRLS;
CREATE SCHEMA auth;
CREATE TABLE auth.users (
  id uuid PRIMARY KEY,
  email text,
  phone text,
  raw_user_meta_data jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION auth.uid()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$ SELECT NULL::uuid $$;
SQL

for migration in supabase/migrations/*.sql; do
  echo "Applying ${migration}"
  psql -v ON_ERROR_STOP=1 -f "${migration}"
done

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'incidents'
      AND column_name = 'location_source'
      AND is_nullable = 'NO'
  ) THEN
    RAISE EXCEPTION 'location_source column missing or nullable after replay';
  END IF;

  IF to_regclass('public.fcm_tokens') IS NULL THEN
    RAISE EXCEPTION 'fcm_tokens table missing after replay';
  END IF;

  IF to_regprocedure('public.api_create_incident_v3(text,text,double precision,double precision,real,text,timestamp with time zone,text)') IS NULL THEN
    RAISE EXCEPTION 'api_create_incident_v3 missing after replay';
  END IF;

  IF to_regprocedure('public.api_register_fcm_token(text)') IS NULL
     OR to_regprocedure('public.api_unregister_fcm_token(text)') IS NULL THEN
    RAISE EXCEPTION 'FCM token RPCs missing after replay';
  END IF;
END;
$$;

-- Regression test for the failure seen in the Android recording:
-- creating an incident must also create its first status log without an FK error.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000123',
  'sos-regression@example.test',
  '{"name":"SOS Regression","role":"requester"}'::jsonb
);

DO $$
DECLARE
  requester_profile_id bigint;
  created_incident_id bigint;
  status_log_count integer;
BEGIN
  SELECT id INTO requester_profile_id
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000123';

  IF requester_profile_id IS NULL THEN
    RAISE EXCEPTION 'Signup trigger did not create the regression requester profile';
  END IF;

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_notes, location_recorded_at, location_source
  ) VALUES (
    requester_profile_id, 'medical', 'Automated SOS audit regression',
    11.244, 125.003, 'Synthetic CI location', now(), 'manual'
  )
  RETURNING id INTO created_incident_id;

  SELECT count(*) INTO status_log_count
    FROM public.incident_status_logs
   WHERE incident_id = created_incident_id
     AND status = 'pending'
     AND changed_by = requester_profile_id;

  IF status_log_count <> 1 THEN
    RAISE EXCEPTION 'Expected exactly one initial status log for incident %, found %',
      created_incident_id, status_log_count;
  END IF;
END;
$$;
SQL

echo "PASS: all $(find supabase/migrations -maxdepth 1 -name '*.sql' | wc -l) Supabase migrations replayed, and initial incident status logging passed."
