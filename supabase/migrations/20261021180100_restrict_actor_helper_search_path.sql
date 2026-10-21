-- The private actor-role helper is SECURITY DEFINER and its body schema-qualifies
-- public.profiles and auth.uid(), so it does not need public/auth/pg_temp in its lookup path.
ALTER FUNCTION private.tacresq_actor_role()
  SET search_path = pg_catalog;
