-- Read-only schema contract for incident-chat upload reservations.
-- Safe to run against a deployed Supabase project: this file performs no writes.
-- It intentionally fails closed when migration history and live schema diverge.

DO $incident_chat_upload_schema_contract$
DECLARE
  policy_columns text[];
  enforcement_default text;
  enforcement_not_null boolean;
  bucket_is_private boolean;
  bucket_size_limit bigint;
  bucket_mime_types text[];
  insert_policy_definition text;
  read_policy_definition text;
BEGIN
  IF to_regclass('private.incident_chat_upload_policy') IS NULL THEN
    RAISE EXCEPTION 'Incident-chat upload policy table is missing';
  END IF;

  IF to_regclass('private.incident_chat_upload_reservations') IS NULL THEN
    RAISE EXCEPTION 'Incident-chat upload reservations table is missing';
  END IF;

  SELECT array_agg(column_name ORDER BY column_name)
    INTO policy_columns
    FROM information_schema.columns
   WHERE table_schema = 'private'
     AND table_name = 'incident_chat_upload_policy';

  IF NOT (coalesce(policy_columns, ARRAY[]::text[]) @> ARRAY[
    'singleton',
    'reservation_ttl_seconds',
    'uploads_per_minute',
    'reservation_enforcement_enabled',
    'updated_at'
  ]) THEN
    RAISE EXCEPTION
      'Incident-chat upload policy schema is incomplete; found columns: %',
      coalesce(policy_columns, ARRAY[]::text[]);
  END IF;

  SELECT column_default, is_nullable = 'NO'
    INTO enforcement_default, enforcement_not_null
    FROM information_schema.columns
   WHERE table_schema = 'private'
     AND table_name = 'incident_chat_upload_policy'
     AND column_name = 'reservation_enforcement_enabled';

  IF enforcement_default IS NULL
     OR lower(enforcement_default) NOT IN ('false', 'false::boolean')
     OR enforcement_not_null IS DISTINCT FROM true THEN
    RAISE EXCEPTION
      'Reservation enforcement must be NOT NULL with a false default; default=%, not_null=%',
      enforcement_default, enforcement_not_null;
  END IF;

  IF NOT EXISTS (
    SELECT 1
      FROM private.incident_chat_upload_policy
     WHERE singleton = true
       AND reservation_enforcement_enabled = false
  ) THEN
    RAISE EXCEPTION
      'Expected the singleton reservation policy row with enforcement disabled for staged rollout';
  END IF;

  IF to_regprocedure(
    'private.tacresq_upload_reservation_allows_insert(text,text,bigint)'
  ) IS NULL THEN
    RAISE EXCEPTION 'Storage reservation authorization function is missing';
  END IF;

  SELECT public, file_size_limit, allowed_mime_types
    INTO bucket_is_private, bucket_size_limit, bucket_mime_types
    FROM storage.buckets
   WHERE id = 'incident-chat-attachments';

  IF NOT FOUND
     OR bucket_is_private IS DISTINCT FROM false
     OR bucket_size_limit IS DISTINCT FROM 5242880
     OR bucket_mime_types IS DISTINCT FROM
       ARRAY['image/jpeg', 'image/png', 'image/webp']::text[] THEN
    RAISE EXCEPTION
      'Incident-chat attachment bucket does not match the private 5 MiB image-only contract';
  END IF;

  SELECT pg_get_expr(polqual, polrelid)
    INTO read_policy_definition
    FROM pg_policy
   WHERE polrelid = 'storage.objects'::regclass
     AND polname = 'tacresq_incident_chat_attachment_read';

  SELECT pg_get_expr(polwithcheck, polrelid)
    INTO insert_policy_definition
    FROM pg_policy
   WHERE polrelid = 'storage.objects'::regclass
     AND polname = 'tacresq_incident_chat_attachment_insert';

  IF read_policy_definition IS NULL OR
     read_policy_definition NOT LIKE '%tacresq_auth_can_access_incident_chat%' THEN
    RAISE EXCEPTION 'Incident-chat Storage read policy is missing its participant-access check';
  END IF;

  IF insert_policy_definition IS NULL OR
     insert_policy_definition NOT LIKE '%tacresq_upload_reservation_allows_insert%' OR
     insert_policy_definition NOT LIKE '%tacresq_auth_can_access_incident_chat%' THEN
    RAISE EXCEPTION 'Incident-chat Storage insert policy is missing reservation or participant authorization';
  END IF;
END;
$incident_chat_upload_schema_contract$;

SELECT
  'incident-chat-upload-schema-contract' AS check_name,
  'passed' AS result,
  'read-only; reservation enforcement remains disabled' AS detail;
