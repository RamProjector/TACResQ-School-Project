BEGIN;

-- This helper is used by RLS policies to resolve the caller's profile role.
-- Its query already schema-qualifies public.profiles and auth.uid(), so public,
-- auth, and pg_temp are unnecessary in a SECURITY DEFINER lookup path.
ALTER FUNCTION public.tacresq_actor_role()
  SET search_path = pg_catalog;

COMMIT;
