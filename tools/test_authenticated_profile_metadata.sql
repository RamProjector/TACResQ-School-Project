-- Exercise profile immutability as the actual authenticated database role.
-- This runs only in the disposable PostgreSQL migration-replay database.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000142',
  'profile-metadata-authenticated@example.test',
  '{"name":"Profile Metadata Authenticated","phone":"+63917000042","role":"requester"}'::jsonb
);

UPDATE public.profiles
SET role = 'responder', duty_status = 'available'
WHERE auth_user_id = '00000000-0000-4000-8000-000000000142';

-- Switch to the same database role used by PostgREST's authenticated JWTs.
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000142', false);

DO $profile_metadata_authenticated$
DECLARE
  affected integer;
  denied boolean;
BEGIN
  -- A profile primary key must remain immutable even when no FK would block it.
  denied := false;
  BEGIN
    UPDATE public.profiles
       SET id = id + 1000000
     WHERE auth_user_id = auth.uid();
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 0 THEN
      RAISE EXCEPTION 'Profile ID mutation unexpectedly affected % rows', affected;
    END IF;
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Authenticated profile ID mutation was not rejected by the trigger';
  END IF;

  denied := false;
  BEGIN
    UPDATE public.profiles
       SET created_at = created_at - interval '1 day'
     WHERE auth_user_id = auth.uid();
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 0 THEN
      RAISE EXCEPTION 'Profile created_at mutation unexpectedly affected % rows', affected;
    END IF;
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Authenticated profile created_at mutation was not rejected by the trigger';
  END IF;

  -- Auth identity and contact fields are immutable through profile table updates.
  denied := false;
  BEGIN
    UPDATE public.profiles
       SET auth_user_id = '00000000-0000-4000-8000-000000000143'
     WHERE auth_user_id = auth.uid();
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Authenticated auth_user_id mutation was not rejected by the trigger';
  END IF;

  denied := false;
  BEGIN
    UPDATE public.profiles
       SET name = 'Tampered Profile Name'
     WHERE auth_user_id = auth.uid();
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Authenticated profile name mutation was not rejected by the trigger';
  END IF;

  denied := false;
  BEGIN
    UPDATE public.profiles
       SET email = 'tampered-profile@example.test'
     WHERE auth_user_id = auth.uid();
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Authenticated profile email mutation was not rejected by the trigger';
  END IF;

  denied := false;
  BEGIN
    UPDATE public.profiles
       SET phone = '+63917000999'
     WHERE auth_user_id = auth.uid();
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Authenticated profile phone mutation was not rejected by the trigger';
  END IF;

  -- Field-unit users must not be able to change their own role.
  denied := false;
  BEGIN
    UPDATE public.profiles
       SET role = 'requester'
     WHERE auth_user_id = auth.uid();
  EXCEPTION WHEN insufficient_privilege THEN
    denied := true;
  END;
  IF NOT denied THEN
    RAISE EXCEPTION 'Authenticated field-unit role mutation was not rejected by the trigger';
  END IF;

  -- A supported self-service duty update must still work for a field unit.
  UPDATE public.profiles
     SET duty_status = 'off_duty'
   WHERE auth_user_id = auth.uid();
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected <> 1 THEN
    RAISE EXCEPTION 'Expected the authenticated field unit to update its own duty status; affected % rows', affected;
  END IF;
END;
$profile_metadata_authenticated$;

RESET ROLE;
SELECT set_config('request.jwt.claim.sub', '', false);
