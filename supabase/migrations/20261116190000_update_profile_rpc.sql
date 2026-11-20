CREATE OR REPLACE FUNCTION public.api_update_profile(
    p_name text,
    p_phone text
)
RETURNS public.profiles
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_user_id uuid;
    v_profile public.profiles%ROWTYPE;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    UPDATE public.profiles
    SET name = nullif(btrim(p_name), ''),
        phone = nullif(btrim(p_phone), ''),
        updated_at = now()
    WHERE auth_user_id = v_user_id
    RETURNING * INTO v_profile;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found';
    END IF;

    RETURN v_profile;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.api_update_profile(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_update_profile(text, text) TO authenticated;

CREATE OR REPLACE FUNCTION public.api_update_password(
    p_password text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;
    IF length(btrim(p_password)) < 8 THEN
        RAISE EXCEPTION 'Password must be at least 8 characters';
    END IF;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.api_update_password(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.api_update_password(text) TO authenticated;
