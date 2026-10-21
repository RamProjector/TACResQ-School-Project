-- The private actor-role helper is SECURITY DEFINER and its body schema-qualifies
-- public.profiles and auth.uid(), so it does not need extra schemas in its lookup path.
ALTER FUNCTION private.tacresq_actor_role()
  SET search_path = pg_catalog;
