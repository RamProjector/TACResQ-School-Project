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
      check (location_source in ('gps', 'manual', 'none'));
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


create or replace function public.api_my_incidents()
returns jsonb language sql stable security invoker set search_path = public, pg_temp as $$
  select coalesce(
    jsonb_agg(jsonb_build_object(
      'id', i.id, 'type', i.type, 'description', i.description,
      'latitude', i.latitude, 'longitude', i.longitude, 'accuracy_m', i.accuracy_m,
      'location_notes', i.location_notes, 'location_recorded_at', i.location_recorded_at, 'location_source', i.location_source,
      'status', i.status, 'requester_name', r.name, 'requester_phone', r.phone,
      'assigned_to', i.assigned_to, 'created_at', i.created_at
    ) order by i.created_at desc), '[]'::jsonb
  )
  from public.incidents i
  join public.profiles r on r.id = i.requester_id
  where i.requester_id in (select id from public.profiles where auth_user_id = auth.uid())
     or i.assigned_to in (select id from public.profiles where auth_user_id = auth.uid());
$$;

create or replace function public.api_all_incidents()
returns jsonb language plpgsql stable security invoker set search_path = public, pg_temp as $$
begin
  if not exists(select 1 from public.profiles where auth_user_id = auth.uid() and role = 'dispatcher') then
    raise exception 'Only dispatchers can view the queue' using errcode = '42501';
  end if;
  return (
    select coalesce(jsonb_agg(jsonb_build_object(
      'id', i.id, 'type', i.type, 'description', i.description,
      'latitude', i.latitude, 'longitude', i.longitude, 'accuracy_m', i.accuracy_m,
      'location_notes', i.location_notes, 'location_recorded_at', i.location_recorded_at, 'location_source', i.location_source,
      'status', i.status, 'requester_name', r.name, 'requester_phone', r.phone,
      'assigned_to', i.assigned_to, 'created_at', i.created_at
    ) order by i.created_at desc), '[]'::jsonb)
    from public.incidents i join public.profiles r on r.id = i.requester_id
  );
end $$;

create or replace function public.api_update_incident_status(p_id bigint, p_status text)
returns jsonb language plpgsql security invoker set search_path = public, pg_temp as $$
declare p public.profiles; i public.incidents; allowed boolean;
begin
  select * into p from public.profiles where auth_user_id = auth.uid();
  select * into i from public.incidents where id = p_id for update;
  if not found then raise exception 'Incident not found' using errcode = 'P0002'; end if;
  if i.assigned_to <> p.id or p.role not in ('responder', 'driver') then
    raise exception 'Incident is not assigned to this field unit' using errcode = '42501';
  end if;
  allowed := (i.status = 'assigned' and p_status = 'en_route')
    or (i.status = 'en_route' and p_status = 'on_scene')
    or (i.status = 'on_scene' and p_status in ('transporting', 'resolved'))
    or (i.status = 'transporting' and p_status = 'resolved');
  if not allowed then raise exception 'Invalid incident status transition' using errcode = '22023'; end if;
  update public.incidents set status = p_status where id = i.id returning * into i;
  return jsonb_build_object(
    'id', i.id, 'type', i.type, 'description', i.description,
    'latitude', i.latitude, 'longitude', i.longitude, 'accuracy_m', i.accuracy_m,
    'location_notes', i.location_notes, 'location_recorded_at', i.location_recorded_at, 'location_source', i.location_source,
    'status', i.status, 'requester_name', (select name from public.profiles where id = i.requester_id),
    'requester_phone', (select phone from public.profiles where id = i.requester_id),
    'assigned_to', i.assigned_to, 'created_at', i.created_at
  );
end $$;

create or replace function public.api_assign_incident(p_id bigint, p_user_id bigint)
returns jsonb language plpgsql security invoker set search_path = public, pg_temp as $$
declare i public.incidents; u public.profiles;
begin
  if not exists(select 1 from public.profiles where auth_user_id = auth.uid() and role = 'dispatcher') then
    raise exception 'Only dispatchers can assign incidents' using errcode = '42501';
  end if;
  select * into i from public.incidents where id = p_id for update;
  if not found or i.status <> 'pending' then raise exception 'Only pending incidents can be assigned' using errcode = '22023'; end if;
  select * into u from public.profiles where id = p_user_id for update;
  if u.role not in ('responder', 'driver') or u.duty_status <> 'available'
     or exists(select 1 from public.incidents where assigned_to = u.id and status in ('assigned', 'en_route', 'on_scene', 'transporting')) then
    raise exception 'Selected unit is not available' using errcode = '22023';
  end if;
  update public.incidents set status = 'assigned', assigned_to = u.id where id = i.id returning * into i;
  return jsonb_build_object(
    'id', i.id, 'type', i.type, 'description', i.description,
    'latitude', i.latitude, 'longitude', i.longitude, 'accuracy_m', i.accuracy_m,
    'location_notes', i.location_notes, 'location_recorded_at', i.location_recorded_at, 'location_source', i.location_source,
    'status', i.status, 'requester_name', (select name from public.profiles where id = i.requester_id),
    'requester_phone', (select phone from public.profiles where id = i.requester_id),
    'assigned_to', i.assigned_to, 'created_at', i.created_at
  );
end $$;


revoke all on function public.api_create_incident_v3(text, text, double precision, double precision, real, text, timestamptz, text) from public, anon;
grant execute on function public.api_create_incident_v3(text, text, double precision, double precision, real, text, timestamptz, text) to authenticated;

commit;
