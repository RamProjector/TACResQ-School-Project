-- End-to-end incident-image authorization regression against the Storage
-- schema shim in test_supabase_migration_replay.sh.
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
  ('00000000-0000-4000-8000-000000000160', 'image-chat-requester-160@example.test',
   '{"name":"Image Chat Requester","phone":"+63917000160","role":"requester"}'::jsonb),
  ('00000000-0000-4000-8000-000000000161', 'image-chat-responder-161@example.test',
   '{"name":"Image Chat Responder","phone":"+63917000161","role":"requester"}'::jsonb),
  ('00000000-0000-4000-8000-000000000162', 'image-chat-dispatcher-162@example.test',
   '{"name":"Image Chat Dispatcher","phone":"+63917000162","role":"requester"}'::jsonb),
  ('00000000-0000-4000-8000-000000000163', 'image-chat-unrelated-163@example.test',
   '{"name":"Image Chat Unrelated","phone":"+63917000154","role":"requester"}'::jsonb);

DO $image_chat_fixture$
DECLARE
  requester_id bigint;
  responder_id bigint;
  dispatcher_id bigint;
  incident_id bigint;
BEGIN
  SELECT id INTO requester_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000160';
  SELECT id INTO responder_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000161';
  SELECT id INTO dispatcher_id FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000162';

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

  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000162', true);
  PERFORM public.api_assign_incident_team(incident_id, ARRAY[responder_id]);
  PERFORM set_config('test.incident_id', incident_id::text, false);
  PERFORM set_config('test.requester_profile_id', requester_id::text, false);
  PERFORM set_config('test.responder_profile_id', responder_id::text, false);
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

DO $reservation_rollout_default$
DECLARE
  enforcement_enabled boolean;
BEGIN
  SELECT reservation_enforcement_enabled
    INTO enforcement_enabled
    FROM private.incident_chat_upload_policy
   WHERE singleton = true;
  IF enforcement_enabled IS DISTINCT FROM false THEN
    RAISE EXCEPTION 'Reservation enforcement must default off for a safe legacy-client rollout';
  END IF;
  IF has_table_privilege('authenticated', 'private.incident_chat_upload_policy', 'UPDATE')
     OR has_table_privilege('authenticated', 'private.incident_chat_upload_policy', 'SELECT')
     OR has_table_privilege('anon', 'private.incident_chat_upload_policy', 'UPDATE') THEN
    RAISE EXCEPTION 'Client roles must not inspect or change the private rollout switch';
  END IF;
END;
$reservation_rollout_default$;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000161', false);
SET ROLE authenticated;

DO $legacy_image_compatibility$
DECLARE
  incident_id bigint := current_setting('test.incident_id')::bigint;
  responder_profile_id bigint := private.tacresq_auth_profile_id();
  legacy_path text;
  response jsonb;
BEGIN
  legacy_path := format(
    '%s/%s/00000000-0000-4000-8000-000000000203.jpg',
    incident_id,
    responder_profile_id
  );

  -- The legacy Storage binary upload probe carries contentLength but no size.
  -- The temporary compatibility mode must permit this preflight and final write.
  BEGIN
    INSERT INTO storage.objects (bucket_id, name, metadata)
    VALUES (
      'incident-chat-attachments',
      legacy_path,
      '{"mimetype":"image/jpeg","contentLength":"4"}'::jsonb
    );
    RAISE EXCEPTION 'Rollback successful legacy upload permission probe';
  EXCEPTION WHEN raise_exception THEN
    NULL;
  END;

  INSERT INTO storage.objects (bucket_id, name, metadata)
  VALUES (
    'incident-chat-attachments',
    legacy_path,
    '{"mimetype":"image/jpeg","size":"4"}'::jsonb
  );

  -- Old clients upload first and finalize without creating a reservation.
  response := public.api_send_incident_image_message(
    incident_id,
    'Legacy compatibility',
    legacy_path,
    'image/jpeg',
    4,
    '00000000-0000-4000-8000-000000000203'::uuid
  );
  IF response->>'attachment_path' IS DISTINCT FROM legacy_path THEN
    RAISE EXCEPTION 'Legacy image finalization must remain compatible while enforcement is disabled';
  END IF;
END;
$legacy_image_compatibility$;

RESET ROLE;
UPDATE private.incident_chat_upload_policy
   SET reservation_enforcement_enabled = true,
       updated_at = clock_timestamp()
 WHERE singleton = true;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000161', false);
SET ROLE authenticated;

DO $image_chat_storage_authorization$
DECLARE
  incident_id bigint := current_setting('test.incident_id')::bigint;
  responder_profile_id bigint := private.tacresq_auth_profile_id();
  image_path text;
  unrelated_profile_id bigint;
  unrelated_path text;
  response jsonb;
  reservation jsonb;
  reservation_retry jsonb;
  unreserved_path text;
  visible_count integer;
  denied boolean;
BEGIN
  image_path := format(
    '%s/%s/00000000-0000-4000-8000-000000000204.jpg',
    incident_id,
    responder_profile_id
  );

  reservation := public.api_reserve_incident_image_upload(
    incident_id,
    'image/jpeg',
    4,
    '00000000-0000-4000-8000-000000000204'::uuid
  );
  IF reservation->>'object_path' IS DISTINCT FROM image_path
     OR reservation->'consumed' IS DISTINCT FROM 'false'::jsonb
     OR reservation->>'reservation_id' IS NULL THEN
    RAISE EXCEPTION 'Reservation RPC returned unexpected authorization: %', reservation;
  END IF;

  reservation_retry := public.api_reserve_incident_image_upload(
    incident_id,
    'image/jpeg',
    4,
    '00000000-0000-4000-8000-000000000204'::uuid
  );
  IF reservation->>'reservation_id' IS NULL
     OR reservation_retry->>'reservation_id' IS DISTINCT FROM reservation->>'reservation_id' THEN
    RAISE EXCEPTION 'Retrying a reservation must return the same operation identity';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      incident_id,
      'Entrance photo',
      '00000000-0000-4000-8000-000000000204'::uuid
    );
  EXCEPTION WHEN unique_violation THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'A pending image reservation must not be reused as a text-message idempotency key';
  END IF;

  -- Supabase Storage performs an INSERT permission probe before the final
  -- object metadata is written. The probe exposes contentLength, not size.
  BEGIN
    INSERT INTO storage.objects (bucket_id, name, metadata)
    VALUES (
      'incident-chat-attachments',
      image_path,
      '{"mimetype":"image/jpeg","contentLength":"4"}'::jsonb
    );
    RAISE EXCEPTION 'Rollback successful pre-upload permission probe';
  EXCEPTION WHEN raise_exception THEN
    NULL;
  END;

  INSERT INTO storage.objects (bucket_id, name, metadata)
  VALUES (
    'incident-chat-attachments',
    image_path,
    '{"mimetype":"image/jpeg","size":"4"}'::jsonb
  );

  denied := false;
  BEGIN
    INSERT INTO storage.objects (bucket_id, name, metadata)
    VALUES (
      'incident-chat-attachments',
      image_path,
      '{"mimetype":"image/jpeg","size":"4"}'::jsonb
    );
  EXCEPTION WHEN unique_violation THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'A duplicate object path must not overwrite the existing attachment';
  END IF;

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

  reservation_retry := public.api_reserve_incident_image_upload(
    incident_id,
    'image/jpeg',
    4,
    '00000000-0000-4000-8000-000000000204'::uuid
  );
  IF reservation_retry->'consumed' IS DISTINCT FROM 'true'::jsonb THEN
    RAISE EXCEPTION 'Finalized image reservation must remain idempotently consumed';
  END IF;

  -- A second object path cannot be inserted without its own reservation.
  unreserved_path := format(
    '%s/%s/00000000-0000-4000-8000-000000000206.jpg',
    incident_id,
    responder_profile_id
  );
  denied := false;
  BEGIN
    INSERT INTO storage.objects (bucket_id, name, metadata)
    VALUES (
      'incident-chat-attachments',
      unreserved_path,
      '{"mimetype":"image/jpeg","size":"4"}'::jsonb
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'A participant must not upload an object without a matching reservation';
  END IF;

  -- The requester who owns this incident can read the responder's image.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000160', true);
  SELECT count(*) INTO visible_count
    FROM storage.objects
   WHERE bucket_id = 'incident-chat-attachments'
     AND name = image_path;
  IF visible_count <> 1 THEN
    RAISE EXCEPTION 'The incident requester must be able to download an assigned responder image';
  END IF;

  response := public.api_my_incident_messages(incident_id, 50, NULL);
  IF jsonb_array_length(response) <> 2
     OR NOT EXISTS (
       SELECT 1
         FROM jsonb_array_elements(response) AS message_row(value)
        WHERE value->>'attachment_path' = image_path
          AND value->>'sender_name' = 'Image Chat Responder'
     ) THEN
    RAISE EXCEPTION 'Authorized message history must include legacy and reserved attachment metadata: %', response;
  END IF;

  -- An unrelated authenticated profile cannot list or download the image.
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000163', true);
  SELECT count(*) INTO visible_count
    FROM storage.objects
   WHERE bucket_id = 'incident-chat-attachments'
     AND name = image_path;
  IF visible_count <> 0 THEN
    RAISE EXCEPTION 'An unrelated account must not read incident image objects';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_reserve_incident_image_upload(
      incident_id,
      'image/jpeg',
      4,
      '00000000-0000-4000-8000-000000000207'::uuid
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'An unrelated account must not reserve an upload for another incident';
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

-- The finalization RPC independently enforces reservations after the switch
-- is enabled, even if an object somehow exists outside the Storage INSERT path.
INSERT INTO storage.objects (bucket_id, name, metadata)
VALUES (
  'incident-chat-attachments',
  format(
    '%s/%s/00000000-0000-4000-8000-000000000210.jpg',
    current_setting('test.incident_id')::bigint,
    private.tacresq_auth_profile_id()
  ),
  '{"mimetype":"image/jpeg","size":"4"}'::jsonb
);
SET ROLE authenticated;
DO $strict_image_finalization$
DECLARE
  denied boolean := false;
  incident_id bigint := current_setting('test.incident_id')::bigint;
  responder_profile_id bigint := private.tacresq_auth_profile_id();
  object_path text;
BEGIN
  object_path := format(
    '%s/%s/00000000-0000-4000-8000-000000000210.jpg',
    incident_id,
    responder_profile_id
  );
  BEGIN
    PERFORM public.api_send_incident_image_message(
      incident_id,
      'No reservation',
      object_path,
      'image/jpeg',
      4,
      '00000000-0000-4000-8000-000000000210'::uuid
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Strict finalization must reject an object without a reservation';
  END IF;
END;
$strict_image_finalization$;
RESET ROLE;

-- An object uploaded while its reservation is valid may be finalized after
-- the lease expires; expiry blocks new uploads, not delayed message finalization.
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000161', false);
SET ROLE authenticated;
SELECT public.api_reserve_incident_image_upload(
  current_setting('test.incident_id')::bigint,
  'image/jpeg',
  4,
  '00000000-0000-4000-8000-000000000208'::uuid
);
INSERT INTO storage.objects (bucket_id, name, metadata)
VALUES (
  'incident-chat-attachments',
  format(
    '%s/%s/00000000-0000-4000-8000-000000000208.jpg',
    current_setting('test.incident_id')::bigint,
    private.tacresq_auth_profile_id()
  ),
  '{"mimetype":"image/jpeg","size":"4"}'::jsonb
);
RESET ROLE;
UPDATE private.incident_chat_upload_reservations
   SET expires_at = now() - interval '1 second'
 WHERE client_message_id = '00000000-0000-4000-8000-000000000208'::uuid;
SET ROLE authenticated;
SELECT public.api_send_incident_image_message(
  current_setting('test.incident_id')::bigint,
  'Delayed finalization',
  format(
    '%s/%s/00000000-0000-4000-8000-000000000208.jpg',
    current_setting('test.incident_id')::bigint,
    private.tacresq_auth_profile_id()
  ),
  'image/jpeg',
  4,
  '00000000-0000-4000-8000-000000000208'::uuid
);
RESET ROLE;

-- An expired reservation must not authorize a new Storage INSERT.
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000161', false);
SET ROLE authenticated;
SELECT public.api_reserve_incident_image_upload(
  current_setting('test.incident_id')::bigint,
  'image/jpeg',
  4,
  '00000000-0000-4000-8000-000000000209'::uuid
);
RESET ROLE;
UPDATE private.incident_chat_upload_reservations
   SET expires_at = now() - interval '1 second'
 WHERE client_message_id = '00000000-0000-4000-8000-000000000209'::uuid;
SET ROLE authenticated;
DO $expired_reservation_test$
DECLARE
  denied boolean := false;
  object_path text;
BEGIN
  object_path := format(
    '%s/%s/00000000-0000-4000-8000-000000000209.jpg',
    current_setting('test.incident_id')::bigint,
    private.tacresq_auth_profile_id()
  );
  BEGIN
    INSERT INTO storage.objects (bucket_id, name, metadata)
    VALUES (
      'incident-chat-attachments',
      object_path,
      '{"mimetype":"image/jpeg","size":"4"}'::jsonb
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'An expired reservation must not authorize a new Storage INSERT';
  END IF;
END;
$expired_reservation_test$;
RESET ROLE;

-- The reservation flood guard uses the existing 20-message-per-minute ceiling.
-- Use the incident owner, who has not sent messages in this fixture, to isolate
-- reservation counting from message counting.
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000160', false);
SET ROLE authenticated;
DO $reservation_rate_limit_test$
DECLARE
  i integer;
  denied boolean := false;
  incident_id bigint := current_setting('test.incident_id')::bigint;
  operation_id uuid;
BEGIN
  PERFORM public.api_send_incident_message(
    incident_id,
    'Existing text for idempotency test',
    '00000000-0000-4000-8000-000000000322'::uuid
  );

  denied := false;
  BEGIN
    PERFORM public.api_reserve_incident_image_upload(
      incident_id,
      'image/jpeg',
      4,
      '00000000-0000-4000-8000-000000000322'::uuid
    );
  EXCEPTION WHEN unique_violation THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'An image reservation must not reuse an existing text-message key';
  END IF;

  FOR i IN 300..318 LOOP
    operation_id := ('00000000-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid;
    PERFORM public.api_reserve_incident_image_upload(
      incident_id,
      'image/jpeg',
      4,
      operation_id
    );
  END LOOP;

  denied := false;
  BEGIN
    PERFORM public.api_reserve_incident_image_upload(
      incident_id,
      'image/jpeg',
      4,
      '00000000-0000-4000-8000-000000000319'::uuid
    );
  EXCEPTION WHEN SQLSTATE '54000' THEN
    denied := true;
  END;

  IF NOT denied THEN
    RAISE EXCEPTION 'The 21st image reservation within one minute must be rejected';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_send_incident_message(
      incident_id,
      'Text message must share the upload budget',
      '00000000-0000-4000-8000-000000000321'::uuid
    );
  EXCEPTION WHEN SQLSTATE '54000' THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Text messages must not bypass pending image reservations';
  END IF;
END;
$reservation_rate_limit_test$;
RESET ROLE;

-- Client message UUIDs are scoped to the sender. Two participants may use
-- the same UUID without colliding on the reservation's internal primary key.
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000161', false);
DO $same_client_uuid_reservations$
DECLARE
  responder_reservation jsonb;
  dispatcher_reservation jsonb;
  incident_id bigint := current_setting('test.incident_id')::bigint;
BEGIN
  responder_reservation := public.api_reserve_incident_image_upload(
    incident_id, 'image/jpeg', 4,
    '00000000-0000-4000-8000-000000000220'::uuid
  );
  PERFORM set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000162', true);
  dispatcher_reservation := public.api_reserve_incident_image_upload(
    incident_id, 'image/jpeg', 4,
    '00000000-0000-4000-8000-000000000220'::uuid
  );
  IF responder_reservation->>'reservation_id' IS NULL
     OR dispatcher_reservation->>'reservation_id' IS NULL
     OR responder_reservation->>'reservation_id' = dispatcher_reservation->>'reservation_id' THEN
    RAISE EXCEPTION 'Different senders must receive independent reservation identities for the same client UUID';
  END IF;
END;
$same_client_uuid_reservations$;
RESET ROLE;

SELECT 'PASS: private incident image storage permits authorized uploads, requires reservations, and supports delayed finalization.' AS result;


-- Finalization must reject an object whose stored byte size differs from
-- the matching reservation, even if the object exists in Storage.
INSERT INTO storage.objects (bucket_id, name, metadata)
VALUES (
  'incident-chat-attachments',
  format(
    '%s/%s/00000000-0000-4000-8000-000000000222.jpg',
    current_setting('test.incident_id')::bigint,
    current_setting('test.responder_profile_id')::bigint
  ),
  '{"mimetype":"image/jpeg","size":"5"}'::jsonb
);
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000161', false);
SET ROLE authenticated;
DO $stored_size_mismatch_test$
DECLARE
  denied boolean := false;
  incident_id bigint := current_setting('test.incident_id')::bigint;
  responder_profile_id bigint := private.tacresq_auth_profile_id();
BEGIN
  PERFORM public.api_reserve_incident_image_upload(
    incident_id,
    'image/jpeg',
    4,
    '00000000-0000-4000-8000-000000000222'::uuid
  );
  BEGIN
    PERFORM public.api_send_incident_image_message(
      incident_id,
      'Stored-size mismatch must fail',
      format(
        '%s/%s/00000000-0000-4000-8000-000000000222.jpg',
        incident_id,
        responder_profile_id
      ),
      'image/jpeg',
      4,
      '00000000-0000-4000-8000-000000000222'::uuid
    );
  EXCEPTION WHEN invalid_parameter_value THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Finalization must reject stored object metadata with a mismatched byte size';
  END IF;
END;
$stored_size_mismatch_test$;
RESET ROLE;

SELECT 'PASS: stored object size mismatch is rejected during image-message finalization.' AS result;

-- A requester with the same still-valid JWT must lose chat and Storage access
-- immediately after the trusted profile-state change commits.
SELECT set_config('request.jwt.claim.sub', '', false);
UPDATE public.profiles
   SET account_status = 'suspended',
       suspended_at = clock_timestamp(),
       suspended_by = (
         SELECT id FROM public.profiles
          WHERE auth_user_id = '00000000-0000-4000-8000-000000000162'
       ),
       suspension_reason = 'Synthetic Storage suspension contract'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000160';
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000160', false);
SET ROLE authenticated;
DO $suspended_storage_and_chat_denial$
DECLARE
  incident_id bigint := current_setting('test.incident_id')::bigint;
  requester_profile_id bigint := private.tacresq_auth_profile_id();
  existing_image_path text;
  new_image_path text;
  visible_count integer;
  denied boolean;
BEGIN
  IF requester_profile_id IS NOT NULL THEN
    RAISE EXCEPTION 'Suspended requester must not resolve an active profile ID';
  END IF;

  existing_image_path := format(
    '%s/%s/00000000-0000-4000-8000-000000000204.jpg',
    incident_id,
    current_setting('test.responder_profile_id')::bigint
  );
  SELECT count(*) INTO visible_count
    FROM storage.objects
   WHERE bucket_id = 'incident-chat-attachments'
     AND name = existing_image_path;
  IF visible_count <> 0 THEN
    RAISE EXCEPTION 'Suspended requester must not download previously uploaded incident images';
  END IF;

  denied := false;
  BEGIN
    PERFORM public.api_my_incident_messages(incident_id, 50, NULL);
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Suspended requester must not read incident chat history';
  END IF;

  new_image_path := format(
    '%s/%s/00000000-0000-4000-8000-000000000223.jpg',
    incident_id,
    current_setting('test.requester_profile_id')::bigint
  );
  denied := false;
  BEGIN
    INSERT INTO storage.objects (bucket_id, name, metadata)
    VALUES (
      'incident-chat-attachments',
      new_image_path,
      '{"mimetype":"image/jpeg","size":"4"}'::jsonb
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Suspended requester must not upload new incident images';
  END IF;

END;
$suspended_storage_and_chat_denial$;
RESET ROLE;

-- Restore only this disposable fixture so later replay checks are independent.
SELECT set_config('request.jwt.claim.sub', '', false);
UPDATE public.profiles
   SET account_status = 'active',
       suspended_at = NULL,
       suspended_by = NULL,
       suspension_reason = NULL
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000160';

SELECT 'PASS: suspended requester with an unexpired JWT is denied chat and Storage access.' AS result;

-- A dispatcher is also an incident-chat participant. Use its independent,
-- unsaturated message budget to prove suspended message writes hit the row
-- trigger rather than a rate-limit/idempotency branch.
SELECT set_config('request.jwt.claim.sub', '', false);
UPDATE public.profiles
   SET account_status = 'suspended',
       suspended_at = clock_timestamp(),
       suspended_by = id,
       suspension_reason = 'Synthetic suspended dispatcher chat contract'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000162';
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000162', false);
SET ROLE authenticated;
DO $suspended_dispatcher_chat_write$
DECLARE
  denied boolean := false;
BEGIN
  BEGIN
    PERFORM public.api_send_incident_message(
      current_setting('test.incident_id')::bigint,
      'Suspended dispatcher message must fail',
      '00000000-0000-4000-8000-000000000224'::uuid
    );
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Suspended dispatcher must not send incident chat messages';
  END IF;
END;
$suspended_dispatcher_chat_write$;
RESET ROLE;
SELECT set_config('request.jwt.claim.sub', '', false);
UPDATE public.profiles
   SET account_status = 'active',
       suspended_at = NULL,
       suspended_by = NULL,
       suspension_reason = NULL
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000162';

SELECT 'PASS: suspended dispatcher chat write is denied.' AS result;
