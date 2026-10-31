-- End-to-end incident-image authorization regression against the Storage
-- schema shim in test_supabase_migration_replay.sh.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000151', 'image-chat-requester@example.test',
   '{"name":"Image Chat Requester","phone":"+63917000151","role":"requester"}'::jsonb),
  ('00000000-0000-4000-8000-000000000152', 'image-chat-responder@example.test',
   '{"name":"Image Chat Responder","phone":"+63917000152","role":"requester"}'::jsonb),
  ('00000000-0000-4000-8000-000000000153', 'image-chat-dispatcher@example.test',
   '{"name":"Image Chat Dispatcher","phone":"+63917000153","role":"requester"}'::jsonb),
  ('00000000-0000-4000-8000-000000000154', 'image-chat-unrelated@example.test',
   '{"name":"Image Chat Unrelated","phone":"+63917000154","role":"requester"}'::jsonb);

DO $image_chat_fixture$
DECLARE
  requester_id bigint;
  responder_id bigint;
  dispatcher_id bigint;
  incident_id bigint;
BEGIN
  SELECT id INTO requester_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000151';
  SELECT id INTO responder_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000152';
  SELECT id INTO dispatcher_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000153';

  UPDATE public.profiles SET role = 'requester', duty_status = NULL WHERE id = requester_id;
  UPDATE public.profiles SET role = 'responder', duty_status = 'available' WHERE id = responder_id;
  UPDATE public.profiles SET role = 'dispatcher', duty_status = NULL WHERE id = dispatcher_id;

  INSERT INTO public.incidents (
    requester_id, type, description, latitude, longitude,
    location_recorded_at, location_source
  ) VALUES (
    requester_id, 'medical', 'Synthetic private image-chat authorization test',
    11.244, 125.003, now(), 'manual'
  ) RETURNING id INTO incident_id;

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000153', true);
  PERFORM public.api_assign_incident_team(incident_id, ARRAY[responder_id]);
  PERFORM set_config('test.incident_id', incident_id::text, false);
END;
$image_chat_fixture$;

DO $image_chat_bucket_test$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM storage.buckets
     WHERE id = 'incident-chat-attachments'
       AND public = false
       AND file_size_limit = 5242880
       AND allowed_mime_types = ARRAY['image/jpeg', 'image/png', 'image/webp']::text[]
  ) THEN
    RAISE EXCEPTION 'Incident chat image bucket must be private and enforce the expected size/MIME limits';
  END IF;
END;
$image_chat_bucket_test$;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000152', false);
SET ROLE authenticated;

DO $image_chat_storage_authorization$
DECLARE
  incident_id bigint := current_setting('test.incident_id')::bigint;
  responder_profile_id bigint := private.tacresq_auth_profile_id();
  image_path text;
  unrelated_profile_id bigint;
  unrelated_path text;
  response jsonb;
  visible_count integer;
  denied boolean;
BEGIN
  image_path := format(
    '%s/%s/00000000-0000-4000-8000-000000000204.jpg',
    incident_id,
    responder_profile_id
  );

  INSERT INTO storage.objects (bucket_id, name, metadata)
  VALUES (
    'incident-chat-attachments',
    image_path,
    '{"mimetype":"image/jpeg","size":"4"}'::jsonb
  );

  response := public.api_send_incident_image_message(
    incident_id,
    'Entrance photo',
    image_path,
    'image/jpeg',
    4,
    '00000000-0000-4000-8000-000000000204'::uuid
  );
  IF response->>'attachment_path' <> image_path
     OR response->>'attachment_mime_type' <> 'image/jpeg'
     OR response->>'attachment_size_bytes' <> '4' THEN
    RAISE EXCEPTION 'Image-message RPC returned unexpected attachment metadata: %', response;
  END IF;

  -- The requester who owns this incident can read the responder's image.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000151', true);
  SELECT count(*) INTO visible_count
    FROM storage.objects
   WHERE bucket_id = 'incident-chat-attachments'
     AND name = image_path;
  IF visible_count <> 1 THEN
    RAISE EXCEPTION 'The incident requester must be able to download an assigned responder image';
  END IF;

  response := public.api_my_incident_messages(incident_id, 50, NULL);
  IF jsonb_array_length(response) <> 1
     OR response->0->>'attachment_path' <> image_path
     OR response->0->>'sender_name' <> 'Image Chat Responder' THEN
    RAISE EXCEPTION 'Authorized message history must include sender and attachment metadata: %', response;
  END IF;

  -- An unrelated authenticated profile cannot list or download the image.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000154', true);
  SELECT count(*) INTO visible_count
    FROM storage.objects
   WHERE bucket_id = 'incident-chat-attachments'
     AND name = image_path;
  IF visible_count <> 0 THEN
    RAISE EXCEPTION 'An unrelated account must not read incident image objects';
  END IF;

  unrelated_profile_id := private.tacresq_auth_profile_id();
  unrelated_path := format(
    '%s/%s/00000000-0000-4000-8000-000000000205.jpg',
    incident_id,
    unrelated_profile_id
  );

  denied := false;
  BEGIN
    INSERT INTO storage.objects (bucket_id, name, metadata)
    VALUES (
      'incident-chat-attachments',
      unrelated_path,
      '{"mimetype":"image/jpeg","size":"4"}'::jsonb
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'An unrelated account must not upload into another incident conversation';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_send_incident_image_message(
      incident_id,
      '',
      unrelated_path,
      'image/jpeg',
      4,
      '00000000-0000-4000-8000-000000000205'::uuid
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'The image-message RPC must independently reject an unrelated account';
  END IF;
END;
$image_chat_storage_authorization$;

RESET ROLE;

SELECT 'PASS: private incident image storage permits requester/responder participants and denies unrelated users.' AS result;
