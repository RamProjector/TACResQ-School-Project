BEGIN;

ALTER TABLE public.incident_messages
  ADD COLUMN IF NOT EXISTS attachment_path text,
  ADD COLUMN IF NOT EXISTS attachment_mime_type text,
  ADD COLUMN IF NOT EXISTS attachment_size_bytes bigint;

-- Keep existing text messages valid while allowing an image-only message.
ALTER TABLE public.incident_messages
  DROP CONSTRAINT IF EXISTS incident_messages_message_check;
ALTER TABLE public.incident_messages
  DROP CONSTRAINT IF EXISTS incident_messages_content_check;
ALTER TABLE public.incident_messages
  ADD CONSTRAINT incident_messages_content_check
  CHECK (
    (length(btrim(message)) BETWEEN 1 AND 2000)
    OR (
      btrim(message) = ''
      AND attachment_path IS NOT NULL
      AND attachment_mime_type IN ('image/jpeg', 'image/png', 'image/webp')
      AND attachment_size_bytes BETWEEN 1 AND 5242880
    )
  );

ALTER TABLE public.incident_messages
  DROP CONSTRAINT IF EXISTS incident_messages_attachment_metadata_check;
ALTER TABLE public.incident_messages
  ADD CONSTRAINT incident_messages_attachment_metadata_check
  CHECK (
    (attachment_path IS NULL AND attachment_mime_type IS NULL AND attachment_size_bytes IS NULL)
    OR
    (attachment_path IS NOT NULL AND attachment_mime_type IS NOT NULL AND attachment_size_bytes IS NOT NULL)
  );

CREATE OR REPLACE FUNCTION private.tacresq_auth_can_access_incident_chat(
  p_incident_id bigint
)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
BEGIN
  SELECT p.id
    INTO actor_profile_id
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid();

  IF actor_profile_id IS NULL THEN
    RETURN false;
  END IF;

  RETURN private.tacresq_can_access_incident_chat(p_incident_id, actor_profile_id);
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_auth_can_access_incident_chat(bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tacresq_auth_can_access_incident_chat(bigint) TO authenticated;

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
  expected_extension text;
  recent_message_count integer;
BEGIN
  SELECT p.id
    INTO actor_profile_id
    FROM public.profiles p
   WHERE p.auth_user_id = auth.uid();

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

  IF to_regclass('storage.objects') IS NULL OR NOT EXISTS (
    SELECT 1
      FROM storage.objects o
     WHERE o.bucket_id = 'incident-chat-attachments'
       AND o.name = p_attachment_path
  ) THEN
    RAISE EXCEPTION 'Uploaded image could not be verified'
      USING ERRCODE = '22023';
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

  SELECT count(*) INTO recent_message_count
    FROM public.incident_messages m
   WHERE m.sender_id = actor_profile_id
     AND m.created_at >= now() - interval '1 minute';

  IF recent_message_count >= 20 THEN
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

REVOKE ALL ON FUNCTION public.api_send_incident_image_message(bigint, text, text, text, bigint, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_send_incident_image_message(bigint, text, text, text, bigint, uuid) TO authenticated;

-- Supabase Storage is managed by Supabase and is absent from the lightweight
-- PostgreSQL migration-replay harness. Apply bucket/policies only when its schema exists.
DO $storage_setup$
BEGIN
  IF to_regclass('storage.buckets') IS NOT NULL
     AND to_regclass('storage.objects') IS NOT NULL THEN
    EXECUTE $sql$
      INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
      VALUES (
        'incident-chat-attachments',
        'incident-chat-attachments',
        false,
        5242880,
        ARRAY['image/jpeg', 'image/png', 'image/webp']::text[]
      )
      ON CONFLICT (id) DO UPDATE
        SET public = false,
            file_size_limit = 5242880,
            allowed_mime_types = ARRAY['image/jpeg', 'image/png', 'image/webp']::text[]
    $sql$;

    EXECUTE 'DROP POLICY IF EXISTS tacresq_incident_chat_attachment_insert ON storage.objects';
    EXECUTE $sql$
      CREATE POLICY tacresq_incident_chat_attachment_insert
      ON storage.objects
      FOR INSERT
      TO authenticated
      WITH CHECK (
        bucket_id = 'incident-chat-attachments'
        AND name ~ '^[1-9][0-9]*/[1-9][0-9]*/[0-9a-f-]{36}[.](jpg|png|webp)$'
        AND (storage.foldername(name))[2] = (
          SELECT p.id::text
            FROM public.profiles p
           WHERE p.auth_user_id = auth.uid()
        )
        AND private.tacresq_auth_can_access_incident_chat(
          ((storage.foldername(name))[1])::bigint
        )
      )
    $sql$;

    EXECUTE 'DROP POLICY IF EXISTS tacresq_incident_chat_attachment_read ON storage.objects';
    EXECUTE $sql$
      CREATE POLICY tacresq_incident_chat_attachment_read
      ON storage.objects
      FOR SELECT
      TO authenticated
      USING (
        bucket_id = 'incident-chat-attachments'
        AND private.tacresq_auth_can_access_incident_chat(
          ((storage.foldername(name))[1])::bigint
        )
      )
    $sql$;
  END IF;
END;
$storage_setup$;

COMMIT;
