-- Read-only point-in-time inventory for TACResQ incident-chat attachments.
-- Run with a database role that can read storage.objects and the private reservation ledger.
-- Deliberately omits object paths, reservation IDs, signed URLs, message contents, and coordinates.
-- This report never deletes, updates, or changes Storage objects or reservation rows.

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
    ) AS has_path_owner_mismatch,
    EXISTS (
      SELECT 1
      FROM private.incident_chat_upload_reservations r
      WHERE r.object_path = o.name
        AND r.consumed_at IS NULL
    ) AS has_unconsumed_reservation,
    EXISTS (
      SELECT 1
      FROM private.incident_chat_upload_reservations r
      WHERE r.object_path = o.name
    ) AS has_any_reservation
  FROM storage.objects o
  WHERE o.bucket_id = 'incident-chat-attachments'
),
object_summary AS (
  SELECT
    count(*) AS object_count,
    count(*) FILTER (WHERE has_message_reference) AS referenced_object_count,
    count(*) FILTER (WHERE NOT has_message_reference) AS unreferenced_object_count,
    count(*) FILTER (
      WHERE NOT has_message_reference AND has_unconsumed_reservation
    ) AS pending_unfinalized_object_count,
    count(*) FILTER (
      WHERE NOT has_message_reference AND NOT has_any_reservation
    ) AS unreserved_orphan_object_count,
    coalesce(sum(size_bytes) FILTER (
      WHERE NOT has_message_reference AND NOT has_any_reservation
    ), 0) AS unreserved_orphan_bytes,
    count(*) FILTER (WHERE has_path_owner_mismatch) AS path_owner_mismatch_count,
    count(*) FILTER (WHERE NOT valid_path_shape) AS invalid_path_shape_count,
    count(*) FILTER (WHERE size_bytes IS NULL) AS missing_or_invalid_size_count,
    count(*) FILTER (WHERE size_bytes > 5242880) AS over_limit_size_count,
    min(created_at) AS oldest_object_created_at,
    max(created_at) AS newest_object_created_at,
    coalesce(sum(size_bytes), 0) AS total_metadata_bytes
  FROM objects
),
reservation_summary AS (
  SELECT
    count(*) AS reservation_count,
    count(*) FILTER (
      WHERE consumed_at IS NULL AND expires_at > now()
    ) AS active_reservation_count,
    count(*) FILTER (
      WHERE consumed_at IS NULL AND expires_at <= now()
    ) AS expired_unconsumed_reservation_count,
    count(*) FILTER (
      WHERE consumed_at IS NULL
        AND expires_at > now()
        AND NOT EXISTS (
          SELECT 1 FROM storage.objects o
          WHERE o.bucket_id = 'incident-chat-attachments'
            AND o.name = r.object_path
        )
    ) AS active_reservation_without_object_count,
    count(*) FILTER (
      WHERE consumed_at IS NULL
        AND expires_at <= now()
        AND NOT EXISTS (
          SELECT 1 FROM storage.objects o
          WHERE o.bucket_id = 'incident-chat-attachments'
            AND o.name = r.object_path
        )
    ) AS expired_reservation_without_object_count,
    count(*) FILTER (
      WHERE consumed_at IS NOT NULL
        AND NOT EXISTS (
          SELECT 1 FROM public.incident_messages m
          WHERE m.attachment_path = r.object_path
        )
    ) AS consumed_reservation_without_message_count,
    coalesce(sum(size_bytes) FILTER (
      WHERE consumed_at IS NULL AND expires_at > now()
    ), 0) AS active_reserved_metadata_bytes
  FROM private.incident_chat_upload_reservations r
)
SELECT
  b.id AS bucket_id,
  b.public AS bucket_is_public,
  b.file_size_limit AS configured_max_bytes,
  b.allowed_mime_types,
  b.versioning_status,
  (b.lifecycle_configuration IS NOT NULL) AS has_lifecycle_configuration,
  o.object_count,
  o.referenced_object_count,
  o.unreferenced_object_count,
  o.pending_unfinalized_object_count,
  o.unreserved_orphan_object_count,
  o.unreserved_orphan_bytes,
  o.path_owner_mismatch_count,
  o.invalid_path_shape_count,
  o.missing_or_invalid_size_count,
  o.over_limit_size_count,
  o.total_metadata_bytes,
  o.oldest_object_created_at,
  o.newest_object_created_at,
  r.reservation_count,
  r.active_reservation_count,
  r.expired_unconsumed_reservation_count,
  r.active_reservation_without_object_count,
  r.expired_reservation_without_object_count,
  r.consumed_reservation_without_message_count,
  r.active_reserved_metadata_bytes
FROM bucket b
CROSS JOIN object_summary o
CROSS JOIN reservation_summary r;
