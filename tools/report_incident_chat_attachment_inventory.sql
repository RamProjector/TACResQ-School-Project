-- Read-only point-in-time inventory for TACResQ incident-chat attachments.
-- Run with a database role that can read storage.objects and public.incident_messages.
-- This report deliberately omits object names, signed URLs, message contents, and coordinates.
-- It never deletes, updates, or changes Storage objects.

WITH bucket AS (
  SELECT id, public, file_size_limit, allowed_mime_types,
         versioning_status, lifecycle_configuration
  FROM storage.buckets
  WHERE id = 'incident-chat-attachments'
),
objects AS (
  SELECT
    o.id,
    o.name,
    o.created_at,
    CASE
      WHEN o.metadata->>'size' ~ '^[0-9]+$'
      THEN (o.metadata->>'size')::bigint
      ELSE NULL
    END AS size_bytes,
    o.metadata->>'mimetype' AS mime_type,
    o.name ~ '^[1-9][0-9]*/[1-9][0-9]*/[0-9a-f-]{36}[.](jpg|png|webp)$'
      AS valid_path_shape,
    EXISTS (
      SELECT 1
      FROM public.incident_messages m
      WHERE m.attachment_path = o.name
    ) AS has_message_reference,
    EXISTS (
      SELECT 1
      FROM public.incident_messages m
      WHERE m.attachment_path = o.name
        AND (
          m.incident_id::text <> split_part(o.name, '/', 1)
          OR m.sender_id::text <> split_part(o.name, '/', 2)
        )
    ) AS has_path_owner_mismatch
  FROM storage.objects o
  WHERE o.bucket_id = 'incident-chat-attachments'
),
summary AS (
  SELECT
    count(*) AS object_count,
    count(*) FILTER (WHERE has_message_reference) AS referenced_object_count,
    count(*) FILTER (WHERE NOT has_message_reference) AS unreferenced_object_count,
    coalesce(sum(size_bytes) FILTER (WHERE NOT has_message_reference), 0)
      AS unreferenced_bytes,
    count(*) FILTER (WHERE has_path_owner_mismatch) AS path_owner_mismatch_count,
    count(*) FILTER (WHERE NOT valid_path_shape) AS invalid_path_shape_count,
    count(*) FILTER (WHERE size_bytes IS NULL) AS missing_or_invalid_size_count,
    count(*) FILTER (WHERE size_bytes > 5242880) AS over_limit_size_count,
    min(created_at) AS oldest_object_created_at,
    max(created_at) AS newest_object_created_at,
    coalesce(sum(size_bytes), 0) AS total_metadata_bytes
  FROM objects
)
SELECT
  b.id AS bucket_id,
  b.public AS bucket_is_public,
  b.file_size_limit AS configured_max_bytes,
  b.allowed_mime_types,
  b.versioning_status,
  (b.lifecycle_configuration IS NOT NULL) AS has_lifecycle_configuration,
  s.object_count,
  s.referenced_object_count,
  s.unreferenced_object_count,
  s.unreferenced_bytes,
  s.path_owner_mismatch_count,
  s.invalid_path_shape_count,
  s.missing_or_invalid_size_count,
  s.over_limit_size_count,
  s.total_metadata_bytes,
  s.oldest_object_created_at,
  s.newest_object_created_at
FROM bucket b
CROSS JOIN summary s;
