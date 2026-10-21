-- Distinguish a GPS-derived location from a pin manually chosen on the map.
begin;

alter table public.incidents add column if not exists location_source text;
update public.incidents
   set location_source = case when latitude is null or longitude is null then 'none' else 'gps' end
 where location_source is null;
alter table public.incidents alter column location_source set default 'gps';
alter table public.incidents alter column location_source set not null;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'incidents_location_source_check'
      and conrelid = 'public.incidents'::regclass
  ) then
    alter table public.incidents
      add constraint incidents_location_source_check
      check (
        (location_source = 'none' and latitude is null and longitude is null)
        or (location_source in ('gps', 'manual') and latitude is not null and longitude is not null)
      );
  end if;
end $$;

create or replace function public.api_create_incident_v3(
  p_type text,
  p_description text,
  p_latitude double precision,
  p_longitude double precision,
  p_accuracy_m real,
  p_location_notes text,
  p_location_recorded_at timestamptz,
  p_location_source text
) returns jsonb
language plpgsql security invoker set search_path = public, pg_temp as $$
declare p public.profiles; i public.incidents;
begin
  select * into p from public.profiles where auth_user_id = auth.uid();
  if p.id is null or p.role <> 'requester' then
    raise exception 'Only requesters can submit incidents' using errcode = '42501';
  end if;
  if p_location_source is null or p_location_source not in ('gps', 'manual', 'none') then
    raise exception 'Invalid location source' using errcode = '22023';
  end if;
  if p_type is null or p_type not in ('medical', 'fire', 'vehicular', 'disaster', 'other') then
    raise exception 'Invalid incident type' using errcode = '22023';
  end if;
  if p_description is not null and length(p_description) > 2000 then
    raise exception 'Incident details exceed 2000 characters' using errcode = '22023';
  end if;
  if p_location_notes is not null and length(p_location_notes) > 500 then
    raise exception 'Location notes exceed 500 characters' using errcode = '22023';
  end if;
  if (p_latitude is null) <> (p_longitude is null) then
    raise exception 'Latitude and longitude must be supplied together' using errcode = '22023';
  end if;
  if p_latitude is null and (p_accuracy_m is not null or p_location_recorded_at is not null or p_location_source <> 'none') then
    raise exception 'A missing location must use source none and have no GPS metadata' using errcode = '22023';
  end if;
  if p_latitude is not null and p_location_source = 'none' then
    raise exception 'Coordinates require a GPS or manual location source' using errcode = '22023';
  end if;
  if p_latitude is not null and p_location_recorded_at is null then
    raise exception 'A location timestamp is required with coordinates' using errcode = '22023';
  end if;
  if p_location_source = 'manual' and p_accuracy_m is not null then
    raise exception 'A manually selected pin cannot claim GPS accuracy' using errcode = '22023';
  end if;
  if p_accuracy_m is not null and (p_accuracy_m < 0 or p_accuracy_m > 100000) then
    raise exception 'GPS accuracy is outside the supported range' using errcode = '22023';
  end if;

  insert into public.incidents(
    requester_id, type, description, latitude, longitude, accuracy_m,
    location_notes, location_recorded_at, location_source
  ) values (
    p.id, p_type, nullif(trim(p_description), ''), p_latitude, p_longitude, p_accuracy_m,
    nullif(trim(p_location_notes), ''), p_location_recorded_at, p_location_source
  ) returning * into i;

  return jsonb_build_object(
    'id', i.id, 'type', i.type, 'description', i.description,
    'latitude', i.latitude, 'longitude', i.longitude, 'accuracy_m', i.accuracy_m,
    'location_notes', i.location_notes, 'location_recorded_at', i.location_recorded_at, 'location_source', i.location_source,
    'status', i.status, 'requester_name', p.name, 'requester_phone', p.phone,
    'assigned_to', i.assigned_to, 'created_at', i.created_at
  );
end $$;


revoke all on function public.api_create_incident_v3(text, text, double precision, double precision, real, text, timestamptz, text) from public, anon;
grant execute on function public.api_create_incident_v3(text, text, double precision, double precision, real, text, timestamptz, text) to authenticated;

CREATE OR REPLACE FUNCTION public.tacresq_guard_incident_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog'
AS $function$
DECLARE
  actor_role text;
  actor_id bigint;
  unit_status text;
BEGIN
  SELECT role, id
    INTO actor_role, actor_id
    FROM public.profiles
   WHERE auth_user_id = auth.uid();

  -- Responders and drivers may progress an assigned incident, but must not alter
  -- the requester's report, original coordinates, or original report timestamps.
  IF actor_role IN ('responder', 'driver') AND (
       NEW.id IS DISTINCT FROM OLD.id
    OR NEW.requester_id IS DISTINCT FROM OLD.requester_id
    OR NEW.type IS DISTINCT FROM OLD.type
    OR NEW.description IS DISTINCT FROM OLD.description
    OR NEW.latitude IS DISTINCT FROM OLD.latitude
    OR NEW.longitude IS DISTINCT FROM OLD.longitude
    OR NEW.accuracy_m IS DISTINCT FROM OLD.accuracy_m
    OR NEW.location_notes IS DISTINCT FROM OLD.location_notes
    OR NEW.location_recorded_at IS DISTINCT FROM OLD.location_recorded_at
    OR NEW.location_source IS DISTINCT FROM OLD.location_source
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
  ) THEN
    RAISE EXCEPTION 'Assigned field units cannot modify the original emergency report'
      USING ERRCODE = '42501';
  END IF;

  IF NEW.assigned_to IS DISTINCT FROM OLD.assigned_to THEN
    SELECT duty_status INTO unit_status
      FROM public.profiles
     WHERE id = NEW.assigned_to;

    IF actor_role <> 'dispatcher'
       OR OLD.status <> 'pending'
       OR NEW.status <> 'assigned'
       OR unit_status <> 'available' THEN
      RAISE EXCEPTION 'Only a dispatcher may assign a pending incident to an available unit'
        USING ERRCODE = '42501';
    END IF;
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NEW.status = 'assigned' THEN
      IF actor_role <> 'dispatcher'
         OR OLD.status <> 'pending'
         OR NEW.assigned_to IS NULL THEN
        RAISE EXCEPTION 'Invalid assignment' USING ERRCODE = '42501';
      END IF;
    ELSIF actor_id <> OLD.assigned_to
       OR actor_role NOT IN ('responder', 'driver')
       OR NOT (
            (OLD.status = 'assigned' AND NEW.status = 'en_route')
         OR (OLD.status = 'en_route' AND NEW.status = 'on_scene')
         OR (OLD.status = 'on_scene' AND NEW.status IN ('transporting', 'resolved'))
         OR (OLD.status = 'transporting' AND NEW.status = 'resolved')
       ) THEN
      RAISE EXCEPTION 'Invalid incident status transition' USING ERRCODE = '42501';
    END IF;
  END IF;

  RETURN NEW;
END;
$function$;

commit;
