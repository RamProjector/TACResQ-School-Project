-- Read-only inventory of every Supabase Storage bucket and its object policies.
-- Run after the account-lifecycle migrations on a disposable or deployed test DB.
-- Unknown buckets are a release blocker: add a reviewed owner/access contract and
-- same-valid-JWT suspended-account tests before treating them as supported.
DO $storage_bucket_inventory_contract$
DECLARE
  bucket_ids text[];
  expected_policies text[] := ARRAY[
    'tacresq_incident_chat_attachment_insert',
    'tacresq_incident_chat_attachment_read'
  ]::text[];
  actual_policies text[];
  unknown_bucket_ids text[];
  unexpected_client_policies text[];
BEGIN
  IF to_regclass('storage.buckets') IS NULL OR to_regclass('storage.objects') IS NULL THEN
    RAISE EXCEPTION 'Supabase Storage buckets/objects catalog is unavailable';
  END IF;

  SELECT coalesce(array_agg(id ORDER BY id), ARRAY[]::text[])
    INTO bucket_ids
    FROM storage.buckets;

  SELECT coalesce(array_agg(id ORDER BY id), ARRAY[]::text[])
    INTO unknown_bucket_ids
    FROM storage.buckets
   WHERE id <> 'incident-chat-attachments';

  IF cardinality(unknown_bucket_ids) > 0 THEN
    RAISE EXCEPTION
      'Unreviewed Storage bucket(s) detected: %. Add bucket-specific access, retention, and suspended-session tests before release.',
      unknown_bucket_ids;
  END IF;

  IF bucket_ids IS DISTINCT FROM ARRAY['incident-chat-attachments']::text[] THEN
    RAISE EXCEPTION
      'Expected the reviewed private incident-chat bucket; discovered bucket inventory: %',
      bucket_ids;
  END IF;

  SELECT coalesce(array_agg(pol.polname ORDER BY pol.polname), ARRAY[]::text[])
    INTO actual_policies
    FROM pg_catalog.pg_policy pol
   WHERE pol.polrelid = 'storage.objects'::regclass
     AND pol.polname = ANY(expected_policies);

  IF actual_policies IS DISTINCT FROM expected_policies THEN
    RAISE EXCEPTION
      'Incident-chat Storage policy inventory is incomplete. Expected %, found %',
      expected_policies, actual_policies;
  END IF;

  -- An additional permissive policy can widen access even when both reviewed
  -- policies remain present. Fail closed on any unreviewed policy that applies
  -- to PUBLIC or authenticated callers for read/write operations.
  SELECT coalesce(array_agg(pol.polname ORDER BY pol.polname), ARRAY[]::text[])
    INTO unexpected_client_policies
    FROM pg_catalog.pg_policy pol
   WHERE pol.polrelid = 'storage.objects'::regclass
     AND pol.polpermissive IS TRUE
     AND pol.polcmd IN ('*', 'r', 'a', 'w')
     AND (0 = ANY(pol.polroles)
       OR 'authenticated'::regrole::oid = ANY(pol.polroles))
     AND NOT (pol.polname = ANY(expected_policies));

  IF cardinality(unexpected_client_policies) > 0 THEN
    RAISE EXCEPTION
      'Unreviewed permissive Storage client policy(ies) detected: %',
      unexpected_client_policies;
  END IF;

  IF NOT EXISTS (
    SELECT 1
      FROM storage.buckets
     WHERE id = 'incident-chat-attachments'
       AND public IS FALSE
       AND file_size_limit = 5242880
       AND allowed_mime_types = ARRAY['image/jpeg', 'image/png', 'image/webp']::text[]
  ) THEN
    RAISE EXCEPTION 'Incident-chat bucket privacy, size, or MIME configuration differs from reviewed contract';
  END IF;

  IF NOT EXISTS (
    SELECT 1
      FROM pg_catalog.pg_policy pol
     WHERE pol.polrelid = 'storage.objects'::regclass
       AND pol.polname = 'tacresq_incident_chat_attachment_insert'
       AND pol.polcmd = 'a'
       AND pol.polpermissive IS TRUE
       AND pol.polroles = ARRAY['authenticated'::regrole::oid]
       AND pg_catalog.pg_get_expr(pol.polwithcheck, pol.polrelid)
           LIKE '%tacresq_auth_profile_id%'
       AND pg_catalog.pg_get_expr(pol.polwithcheck, pol.polrelid)
           LIKE '%tacresq_auth_can_access_incident_chat%'
  ) THEN
    RAISE EXCEPTION 'Storage INSERT policy must bind the active profile and incident-chat membership';
  END IF;

  IF NOT EXISTS (
    SELECT 1
      FROM pg_catalog.pg_policy pol
     WHERE pol.polrelid = 'storage.objects'::regclass
       AND pol.polname = 'tacresq_incident_chat_attachment_read'
       AND pol.polcmd = 'r'
       AND pol.polpermissive IS TRUE
       AND pol.polroles = ARRAY['authenticated'::regrole::oid]
       AND pg_catalog.pg_get_expr(pol.polqual, pol.polrelid)
           LIKE '%tacresq_auth_can_access_incident_chat%'
  ) THEN
    RAISE EXCEPTION 'Storage SELECT policy must bind current incident-chat membership';
  END IF;
END;
$storage_bucket_inventory_contract$;

-- Metadata-only report: never prints object paths, signed URLs, or file contents.
SELECT
  b.id AS bucket_id,
  b.public,
  b.file_size_limit,
  b.allowed_mime_types,
  count(o.id) AS object_count,
  coalesce(sum(CASE
    WHEN o.metadata->>'size' ~ '^[0-9]+

      THEN (o.metadata->>'size')::bigint
    ELSE 0
  END), 0) AS declared_object_bytes,
  ARRAY(
    SELECT pol.polname
      FROM pg_catalog.pg_policy pol
     WHERE pol.polrelid = 'storage.objects'::regclass
     ORDER BY pol.polname
  ) AS storage_object_policies
FROM storage.buckets b
LEFT JOIN storage.objects o ON o.bucket_id = b.id
GROUP BY b.id, b.public, b.file_size_limit, b.allowed_mime_types
ORDER BY b.id;
