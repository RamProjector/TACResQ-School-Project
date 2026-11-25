-- Regression: profile verification and ordinary RLS reads must work in
-- a PostgreSQL READ ONLY transaction while still hiding suspended profiles.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES (
  '00000000-0000-4000-8000-000000000997',
  'readonly-profile-contract@example.test',
  '{"name":"Read Only Profile Contract","phone":"+63917000997","role":"requester"}'::jsonb
);

BEGIN READ ONLY;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000997', true);
SET LOCAL ROLE authenticated;

DO $active_read_only_profile_contract$
DECLARE
  profile_count bigint;
BEGIN
  IF private.tacresq_current_account_active_read() IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'Active account read predicate must work in a read-only transaction';
  END IF;
  IF public.api_me() IS NULL THEN
    RAISE EXCEPTION 'api_me profile verification must work in a read-only transaction';
  END IF;
  SELECT count(*) INTO profile_count
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000997';
  IF profile_count <> 1 THEN
    RAISE EXCEPTION 'Active profile must remain visible through restrictive RLS in a read-only transaction';
  END IF;
END;
$active_read_only_profile_contract$;
COMMIT;

-- Change only the synthetic fixture to suspended, then prove the same JWT is
-- denied in READ ONLY with the same still-valid JWT subject, without refreshing or revoking it.
RESET ROLE;
SELECT set_config('request.jwt.claim.sub', '', false);
UPDATE public.profiles
   SET account_status = 'suspended',
       suspended_at = clock_timestamp(),
       suspended_by = id,
       suspension_reason = 'Synthetic read-only denial fixture'
 WHERE auth_user_id = '00000000-0000-4000-8000-000000000997';

BEGIN READ ONLY;
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000997', true);
SET LOCAL ROLE authenticated;

DO $suspended_read_only_profile_contract$
DECLARE
  profile_count bigint;
BEGIN
  IF private.tacresq_current_account_active_read() IS DISTINCT FROM false THEN
    RAISE EXCEPTION 'Suspended account read predicate must fail closed in a read-only transaction';
  END IF;
  IF public.api_me() IS NOT NULL THEN
    RAISE EXCEPTION 'Suspended profile must not verify through api_me in a read-only transaction';
  END IF;
  SELECT count(*) INTO profile_count
    FROM public.profiles
   WHERE auth_user_id = '00000000-0000-4000-8000-000000000997';
  IF profile_count <> 0 THEN
    RAISE EXCEPTION 'Suspended profile must remain hidden by restrictive RLS in a read-only transaction';
  END IF;
END;
$suspended_read_only_profile_contract$;
COMMIT;
