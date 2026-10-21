-- Keep the privileged location write implementation outside the PostgREST-exposed
-- public schema. The public RPC becomes an invoker wrapper; the private function still
-- validates identity, assignment, incident state, coordinates, timestamps, and write rate.
create or replace function private.tacresq_record_location_v2(
  p_incident_id bigint,
  p_latitude double precision,
  p_longitude double precision,
  p_accuracy_m real,
  p_recorded_at timestamptz
) returns bigint
language plpgsql security definer set search_path = pg_catalog as $function$
declare
  actor_id bigint;
  incident_status text;
  result_id bigint;
  recorded_at_value timestamptz := coalesce(p_recorded_at, now());
begin
  if p_latitude is null or p_longitude is null
     or p_latitude < -90 or p_latitude > 90
     or p_longitude < -180 or p_longitude > 180 then
    raise exception 'Location coordinates are invalid' using errcode = '22023';
  end if;
  if p_accuracy_m is not null and (p_accuracy_m < 0 or p_accuracy_m > 100000) then
    raise exception 'Location accuracy is outside the supported range' using errcode = '22023';
  end if;
  if recorded_at_value > now() + interval '5 minutes' then
    raise exception 'Location timestamp is in the future' using errcode = '22023';
  end if;
  if recorded_at_value < now() - interval '15 minutes' then
    raise exception 'Location timestamp is too old' using errcode = '22023';
  end if;

  select p.id, i.status
    into actor_id, incident_status
    from public.profiles p
    join public.incidents i on i.assigned_to = p.id
   where p.auth_user_id = auth.uid()
     and p.role in ('responder', 'driver')
     and i.id = p_incident_id;

  if actor_id is null or incident_status <> 'en_route' then
    raise exception 'Location tracking is only available to the assigned en-route unit' using errcode = '42501';
  end if;

  perform pg_advisory_xact_lock(actor_id);

  if exists (
    select 1 from public.location_logs l
     where l.user_id = actor_id
       and l.incident_id = p_incident_id
       and l.created_at > now() - interval '8 seconds'
  ) then
    raise exception 'Location updates are arriving too quickly' using errcode = '22023';
  end if;

  insert into public.location_logs(user_id, incident_id, latitude, longitude, accuracy_m, recorded_at)
  values (actor_id, p_incident_id, p_latitude, p_longitude, p_accuracy_m, recorded_at_value)
  returning id into result_id;

  return result_id;
end;
$function$;

revoke all on function private.tacresq_record_location_v2(bigint, double precision, double precision, real, timestamptz) from public, anon;
grant execute on function private.tacresq_record_location_v2(bigint, double precision, double precision, real, timestamptz) to authenticated;

create or replace function public.api_record_location_v2(
  p_incident_id bigint,
  p_latitude double precision,
  p_longitude double precision,
  p_accuracy_m real,
  p_recorded_at timestamptz
) returns bigint
language sql security invoker set search_path = pg_catalog as $function$
  select private.tacresq_record_location_v2(
    p_incident_id, p_latitude, p_longitude, p_accuracy_m, p_recorded_at
  );
$function$;

revoke all on function public.api_record_location_v2(bigint, double precision, double precision, real, timestamptz) from public, anon;
grant execute on function public.api_record_location_v2(bigint, double precision, double precision, real, timestamptz) to authenticated;
