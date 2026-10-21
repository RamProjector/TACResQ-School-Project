-- SECURITY DEFINER functions must not resolve attacker-controlled objects from public or pg_temp.
-- The function body schema-qualifies its application and auth objects, so pg_catalog is sufficient.
ALTER FUNCTION public.api_record_location_v2(
  bigint,
  double precision,
  double precision,
  real,
  timestamp with time zone
) SET search_path = pg_catalog;
