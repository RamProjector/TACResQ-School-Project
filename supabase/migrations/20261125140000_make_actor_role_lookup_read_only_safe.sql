BEGIN;

-- Actor-role checks are used by RLS predicates and read-only API functions.
-- They must not acquire row locks: PostgREST executes stable/read RPCs in
-- read-only transactions. Mutating entry points retain their explicit
-- SELECT ... FOR SHARE guards and account-lifecycle serialization.
CREATE OR REPLACE FUNCTION private.tacresq_actor_role()
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
  SELECT p.role
    FROM public.profiles AS p
   WHERE p.auth_user_id = auth.uid()
     AND p.account_status = 'active'
   LIMIT 1
$function$;

COMMENT ON FUNCTION private.tacresq_actor_role() IS
  'Non-locking stable role lookup for RLS predicates and read-only APIs. Write entry points must retain explicit locking account-state guards.';

COMMIT;
