BEGIN;

-- Resolve the current active profile before validation, idempotency replay,
-- reservation lookup, or message-history reads. This prevents a suspended
-- account from using a still-valid JWT to replay prior chat operations.
CREATE OR REPLACE FUNCTION public.api_reserve_incident_image_upload(
  p_incident_id bigint,
  p_mime_type text,
  p_size_bytes bigint,
  p_client_message_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
  expected_extension text;
  expected_path text;
  existing_reservation private.incident_chat_upload_reservations%ROWTYPE;
  ttl_seconds integer;
  uploads_limit integer;
  recent_message_count integer;
  recent_reservation_count integer;
  reservation_found boolean;
BEGIN
  actor_profile_id := private.tacresq_auth_profile_id();

  IF actor_profile_id IS NULL THEN
    RAISE EXCEPTION 'Authenticated profile is required'
      USING ERRCODE = '42501';
  END IF;

  IF p_incident_id IS NULL OR p_incident_id < 1
     OR p_client_message_id IS NULL
     OR p_mime_type IS NULL
     OR p_mime_type NOT IN ('image/jpeg', 'image/png', 'image/webp')
     OR p_size_bytes IS NULL
     OR p_size_bytes NOT BETWEEN 1 AND 5242880 THEN
    RAISE EXCEPTION 'Image upload reservation parameters are invalid'
      USING ERRCODE = '22023';
  END IF;

  IF NOT private.tacresq_can_access_incident_chat(p_incident_id, actor_profile_id) THEN
    RAISE EXCEPTION 'Incident chat is not available to this account'
      USING ERRCODE = '42501';
  END IF;

  expected_extension := CASE p_mime_type
    WHEN 'image/jpeg' THEN 'jpg'
    WHEN 'image/png' THEN 'png'
    WHEN 'image/webp' THEN 'webp'
  END;
  expected_path := format(
    '%s/%s/%s.%s',
    p_incident_id,
    actor_profile_id,
    p_client_message_id,
    expected_extension
  );

  -- Use the same per-profile transaction lock as text/image message creation.
  -- This makes the pre-upload rate check atomic with concurrent sends.
  PERFORM pg_advisory_xact_lock(actor_profile_id);

  SELECT r.*
    INTO existing_reservation
    FROM private.incident_chat_upload_reservations r
   WHERE r.profile_id = actor_profile_id
     AND r.client_message_id = p_client_message_id
   FOR UPDATE;
  reservation_found := FOUND;

  IF reservation_found THEN
    IF existing_reservation.incident_id <> p_incident_id
       OR existing_reservation.object_path <> expected_path
       OR existing_reservation.mime_type <> p_mime_type
       OR existing_reservation.size_bytes <> p_size_bytes THEN
      RAISE EXCEPTION 'Client message ID was already reserved for different attachment data'
        USING ERRCODE = '23505';
    END IF;

    IF existing_reservation.consumed_at IS NOT NULL
       OR existing_reservation.expires_at > now() THEN
      RETURN jsonb_build_object(
        'reservation_id', existing_reservation.reservation_id,
        'object_path', existing_reservation.object_path,
        'expires_at', existing_reservation.expires_at,
        'consumed', existing_reservation.consumed_at IS NOT NULL
      );
    END IF;
  ELSE
    IF EXISTS (
      SELECT 1
        FROM public.incident_messages m
       WHERE m.sender_id = actor_profile_id
         AND m.client_message_id = p_client_message_id
    ) THEN
      RAISE EXCEPTION 'Client message ID is already used by a text or image message'
        USING ERRCODE = '23505';
    END IF;
  END IF;

  SELECT policy.reservation_ttl_seconds, policy.uploads_per_minute
    INTO ttl_seconds, uploads_limit
    FROM private.incident_chat_upload_policy policy
   WHERE policy.singleton = true;

  IF ttl_seconds IS NULL OR uploads_limit IS NULL THEN
    RAISE EXCEPTION 'Incident image upload policy is not configured'
      USING ERRCODE = '55000';
  END IF;

  SELECT count(*) INTO recent_message_count
    FROM public.incident_messages m
   WHERE m.sender_id = actor_profile_id
     AND m.created_at >= now() - interval '1 minute';

  SELECT count(*) INTO recent_reservation_count
    FROM private.incident_chat_upload_reservations r
   WHERE r.profile_id = actor_profile_id
     AND r.created_at >= now() - interval '1 minute'
     AND r.consumed_at IS NULL
     AND r.expires_at > now()
     AND r.client_message_id <> p_client_message_id;

  IF recent_message_count + recent_reservation_count >= uploads_limit THEN
    RAISE EXCEPTION 'Image upload rate limit reached; wait before trying again'
      USING ERRCODE = '54000';
  END IF;

  IF reservation_found THEN
    UPDATE private.incident_chat_upload_reservations
       SET created_at = now(),
           expires_at = now() + make_interval(secs => ttl_seconds)
     WHERE reservation_id = existing_reservation.reservation_id
    RETURNING * INTO existing_reservation;
  ELSE
    INSERT INTO private.incident_chat_upload_reservations (
      reservation_id, profile_id, incident_id, client_message_id,
      object_path, mime_type, size_bytes, expires_at
    ) VALUES (
      gen_random_uuid(), actor_profile_id, p_incident_id, p_client_message_id,
      expected_path, p_mime_type, p_size_bytes,
      now() + make_interval(secs => ttl_seconds)
    )
    RETURNING * INTO existing_reservation;
  END IF;

  RETURN jsonb_build_object(
    'reservation_id', existing_reservation.reservation_id,
    'object_path', existing_reservation.object_path,
    'expires_at', existing_reservation.expires_at,
    'consumed', false
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.api_reserve_incident_image_upload(bigint, text, bigint, uuid)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_reserve_incident_image_upload(bigint, text, bigint, uuid)
  TO authenticated;

CREATE OR REPLACE FUNCTION public.api_send_incident_message(
  p_incident_id bigint,
  p_message text,
  p_client_message_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
  normalized_message text;
  existing_message public.incident_messages%ROWTYPE;
  created_message public.incident_messages%ROWTYPE;
  recent_message_count integer;
  recent_reservation_count integer;
  uploads_limit integer;
BEGIN
  actor_profile_id := private.tacresq_auth_profile_id();

  IF actor_profile_id IS NULL THEN
    RAISE EXCEPTION 'Authenticated profile is required'
      USING ERRCODE = '42501';
  END IF;

  IF p_incident_id IS NULL OR p_incident_id < 1 THEN
    RAISE EXCEPTION 'Incident ID is invalid'
      USING ERRCODE = '22023';
  END IF;

  normalized_message := btrim(coalesce(p_message, ''));
  IF length(normalized_message) < 1 OR length(normalized_message) > 2000 THEN
    RAISE EXCEPTION 'Message must contain 1 to 2000 characters'
      USING ERRCODE = '22023';
  END IF;

  IF p_client_message_id IS NULL THEN
    RAISE EXCEPTION 'Client message ID is required for safe retries'
      USING ERRCODE = '22023';
  END IF;

  IF NOT private.tacresq_can_access_incident_chat(p_incident_id, actor_profile_id) THEN
    RAISE EXCEPTION 'Incident chat is not available to this account'
      USING ERRCODE = '42501';
  END IF;

  PERFORM pg_advisory_xact_lock(actor_profile_id);

  SELECT m.*
    INTO existing_message
    FROM public.incident_messages m
   WHERE m.sender_id = actor_profile_id
     AND m.client_message_id = p_client_message_id;

  IF FOUND THEN
    IF existing_message.incident_id <> p_incident_id
       OR existing_message.message <> normalized_message
       OR existing_message.attachment_path IS NOT NULL THEN
      RAISE EXCEPTION 'Client message ID was already used for different content or message type'
        USING ERRCODE = '23505';
    END IF;
    RETURN jsonb_build_object(
      'id', existing_message.id,
      'incident_id', existing_message.incident_id,
      'sender_id', existing_message.sender_id,
      'message', existing_message.message,
      'created_at', existing_message.created_at
    );
  END IF;

  IF EXISTS (
    SELECT 1
      FROM private.incident_chat_upload_reservations r
     WHERE r.profile_id = actor_profile_id
       AND r.client_message_id = p_client_message_id
  ) THEN
    RAISE EXCEPTION 'Client message ID is already reserved for an image attachment'
      USING ERRCODE = '23505';
  END IF;

  SELECT policy.uploads_per_minute
    INTO uploads_limit
    FROM private.incident_chat_upload_policy policy
   WHERE policy.singleton = true;

  IF uploads_limit IS NULL THEN
    RAISE EXCEPTION 'Incident image upload policy is not configured'
      USING ERRCODE = '55000';
  END IF;

  SELECT count(*) INTO recent_message_count
    FROM public.incident_messages m
   WHERE m.sender_id = actor_profile_id
     AND m.created_at >= now() - interval '1 minute';

  SELECT count(*) INTO recent_reservation_count
    FROM private.incident_chat_upload_reservations r
   WHERE r.profile_id = actor_profile_id
     AND r.created_at >= now() - interval '1 minute'
     AND r.consumed_at IS NULL
     AND r.expires_at > now();

  IF recent_message_count + recent_reservation_count >= uploads_limit THEN
    RAISE EXCEPTION 'Message rate limit reached; wait before sending again'
      USING ERRCODE = '54000';
  END IF;

  INSERT INTO public.incident_messages (
    incident_id, sender_id, client_message_id, message
  ) VALUES (
    p_incident_id, actor_profile_id, p_client_message_id, normalized_message
  )
  RETURNING * INTO created_message;

  RETURN jsonb_build_object(
    'id', created_message.id,
    'incident_id', created_message.incident_id,
    'sender_id', created_message.sender_id,
    'message', created_message.message,
    'created_at', created_message.created_at
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.api_send_incident_message(bigint, text, uuid)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_send_incident_message(bigint, text, uuid)
  TO authenticated;

CREATE OR REPLACE FUNCTION public.api_send_incident_image_message(
  p_incident_id bigint,
  p_message text,
  p_attachment_path text,
  p_attachment_mime_type text,
  p_attachment_size_bytes bigint,
  p_client_message_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
  normalized_message text;
  existing_message public.incident_messages%ROWTYPE;
  created_message public.incident_messages%ROWTYPE;
  existing_reservation private.incident_chat_upload_reservations%ROWTYPE;
  expected_extension text;
  recent_message_count integer;
  recent_reservation_count integer;
  uploads_limit integer;
  reservation_found boolean;
  enforcement_enabled boolean;
BEGIN
  actor_profile_id := private.tacresq_auth_profile_id();

  IF actor_profile_id IS NULL THEN
    RAISE EXCEPTION 'Authenticated profile is required'
      USING ERRCODE = '42501';
  END IF;

  IF p_incident_id IS NULL OR p_incident_id < 1 THEN
    RAISE EXCEPTION 'Incident ID is invalid'
      USING ERRCODE = '22023';
  END IF;

  normalized_message := btrim(coalesce(p_message, ''));
  IF length(normalized_message) > 2000 THEN
    RAISE EXCEPTION 'Image caption must be 2000 characters or fewer'
      USING ERRCODE = '22023';
  END IF;

  IF p_client_message_id IS NULL
     OR p_attachment_path IS NULL
     OR p_attachment_mime_type IS NULL
     OR p_attachment_mime_type NOT IN ('image/jpeg', 'image/png', 'image/webp')
     OR p_attachment_size_bytes IS NULL
     OR p_attachment_size_bytes NOT BETWEEN 1 AND 5242880 THEN
    RAISE EXCEPTION 'Image attachment metadata is invalid'
      USING ERRCODE = '22023';
  END IF;

  IF NOT private.tacresq_can_access_incident_chat(p_incident_id, actor_profile_id) THEN
    RAISE EXCEPTION 'Incident chat is not available to this account'
      USING ERRCODE = '42501';
  END IF;

  expected_extension := CASE p_attachment_mime_type
    WHEN 'image/jpeg' THEN 'jpg'
    WHEN 'image/png' THEN 'png'
    WHEN 'image/webp' THEN 'webp'
  END;

  IF p_attachment_path <> format(
       '%s/%s/%s.%s',
       p_incident_id,
       actor_profile_id,
       p_client_message_id,
       expected_extension
     ) THEN
    RAISE EXCEPTION 'Image attachment path is invalid'
      USING ERRCODE = '22023';
  END IF;

  -- Serialize finalization with reservation and other message sends.
  PERFORM pg_advisory_xact_lock(actor_profile_id);

  SELECT m.*
    INTO existing_message
    FROM public.incident_messages m
   WHERE m.sender_id = actor_profile_id
     AND m.client_message_id = p_client_message_id;

  IF FOUND THEN
    IF existing_message.incident_id <> p_incident_id
       OR existing_message.message <> normalized_message
       OR existing_message.attachment_path IS DISTINCT FROM p_attachment_path
       OR existing_message.attachment_mime_type IS DISTINCT FROM p_attachment_mime_type
       OR existing_message.attachment_size_bytes IS DISTINCT FROM p_attachment_size_bytes THEN
      RAISE EXCEPTION 'Client message ID was already used for different content'
        USING ERRCODE = '23505';
    END IF;

    RETURN jsonb_build_object(
      'id', existing_message.id,
      'incident_id', existing_message.incident_id,
      'sender_id', existing_message.sender_id,
      'message', existing_message.message,
      'created_at', existing_message.created_at,
      'attachment_path', existing_message.attachment_path,
      'attachment_mime_type', existing_message.attachment_mime_type,
      'attachment_size_bytes', existing_message.attachment_size_bytes
    );
  END IF;

  SELECT policy.reservation_enforcement_enabled
    INTO enforcement_enabled
    FROM private.incident_chat_upload_policy AS policy
   WHERE policy.singleton = true;
  IF enforcement_enabled IS NULL THEN
    RAISE EXCEPTION 'Incident image upload policy is not configured'
      USING ERRCODE = '55000';
  END IF;

  SELECT r.*
    INTO existing_reservation
    FROM private.incident_chat_upload_reservations r
   WHERE r.profile_id = actor_profile_id
     AND r.client_message_id = p_client_message_id
   FOR UPDATE;
  reservation_found := FOUND;

  IF reservation_found THEN
    IF existing_reservation.incident_id <> p_incident_id
       OR existing_reservation.object_path <> p_attachment_path
       OR existing_reservation.mime_type <> p_attachment_mime_type
       OR existing_reservation.size_bytes <> p_attachment_size_bytes
       OR existing_reservation.consumed_at IS NOT NULL THEN
      RAISE EXCEPTION 'Image upload reservation does not match this message'
        USING ERRCODE = '42501';
    END IF;
  ELSIF enforcement_enabled THEN
    RAISE EXCEPTION 'A matching unconsumed image upload reservation is required'
      USING ERRCODE = '42501';
  END IF;

  -- Expiry only blocks a new Storage INSERT. If the object was uploaded while
  -- the reservation was valid, a delayed finalization may still complete.
  IF to_regclass('storage.objects') IS NULL OR NOT EXISTS (
    SELECT 1
      FROM storage.objects o
     WHERE o.bucket_id = 'incident-chat-attachments'
       AND o.name = p_attachment_path
       AND o.metadata->>'mimetype' = p_attachment_mime_type
       AND o.metadata->>'size' ~ '^[0-9]+$'
       AND (o.metadata->>'size')::bigint = p_attachment_size_bytes
  ) THEN
    RAISE EXCEPTION 'Uploaded image could not be verified'
      USING ERRCODE = '22023';
  END IF;

  SELECT policy.uploads_per_minute
    INTO uploads_limit
    FROM private.incident_chat_upload_policy policy
   WHERE policy.singleton = true;

  IF uploads_limit IS NULL THEN
    RAISE EXCEPTION 'Incident image upload policy is not configured'
      USING ERRCODE = '55000';
  END IF;

  SELECT count(*) INTO recent_message_count
    FROM public.incident_messages m
   WHERE m.sender_id = actor_profile_id
     AND m.created_at >= now() - interval '1 minute';

  SELECT count(*) INTO recent_reservation_count
    FROM private.incident_chat_upload_reservations r
   WHERE r.profile_id = actor_profile_id
     AND r.created_at >= now() - interval '1 minute'
     AND r.consumed_at IS NULL
     AND r.expires_at > now()
     AND r.client_message_id <> p_client_message_id;

  IF recent_message_count + recent_reservation_count >= uploads_limit THEN
    RAISE EXCEPTION 'Message rate limit reached; wait before sending again'
      USING ERRCODE = '54000';
  END IF;

  INSERT INTO public.incident_messages (
    incident_id,
    sender_id,
    client_message_id,
    message,
    attachment_path,
    attachment_mime_type,
    attachment_size_bytes
  ) VALUES (
    p_incident_id,
    actor_profile_id,
    p_client_message_id,
    normalized_message,
    p_attachment_path,
    p_attachment_mime_type,
    p_attachment_size_bytes
  )
  RETURNING * INTO created_message;

  IF reservation_found THEN
    UPDATE private.incident_chat_upload_reservations
       SET consumed_at = now()
     WHERE reservation_id = existing_reservation.reservation_id
       AND consumed_at IS NULL;

    IF NOT FOUND THEN
      RAISE EXCEPTION 'Image upload reservation was already consumed'
        USING ERRCODE = '23505';
    END IF;
  END IF;

  RETURN jsonb_build_object(
    'id', created_message.id,
    'incident_id', created_message.incident_id,
    'sender_id', created_message.sender_id,
    'message', created_message.message,
    'created_at', created_message.created_at,
    'attachment_path', created_message.attachment_path,
    'attachment_mime_type', created_message.attachment_mime_type,
    'attachment_size_bytes', created_message.attachment_size_bytes
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.api_send_incident_image_message(bigint, text, text, text, bigint, uuid)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_send_incident_image_message(bigint, text, text, text, bigint, uuid)
  TO authenticated;

COMMIT;
