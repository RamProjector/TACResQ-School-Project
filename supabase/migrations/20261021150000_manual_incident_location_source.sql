-- Distinguish a GPS-derived location from a pin manually chosen on the map.
begin;

alter table public.incidents add column if not exists location_source text;
update public.incidents
   set location_source = case when latitude is null or longitude is null then 'none' else 'gps' end
 where location_source is null;
alter table public.incidents alter column location_source set default 'none';
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

commit;
