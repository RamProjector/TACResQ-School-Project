-- Incident-bound responder location contract.
-- This migration defines the server boundary for a future foreground-only client.
-- It does not enable tracking by itself and must not be applied until the client lifecycle is ready.

begin;

alter table public.location_logs
  add column if not exists incident_id bigint references public.incidents(id) on delete cascade;

create index if not exists location_logs_incident_recorded_idx
  on public.location_logs (incident_id, recorded_at desc);

-- New writes must use the incident-bound RPC below. Existing rows from the
-- previously reserved location table may remain unbound for migration safety.
drop policy if exists location_logs_read_dispatcher_or_owner on public.location_logs;
create policy location_logs_read_dispatcher_or_owner on public.location_logs for select to authenticated
using (
  (
    user_id in (select id from public.profiles where auth_user_id = (select auth.uid()))
    and (
      incident_id is null
      or exists (
        select 1 from public.incidents i
        where i.id = location_logs.incident_id
          and i.assigned_to = user_id
      )
    )
  )
  or (
    (select private.tacresq_actor_role()) = 'dispatcher'
    and (
      incident_id is null
      or exists (select 1 from public.incidents i where i.id = location_logs.incident_id)
    )
  )
);

drop policy if exists location_logs_insert_owner on public.location_logs;
create policy location_logs_insert_owner on public.location_logs for insert to authenticated
with check (
  user_id in (select id from public.profiles where auth_user_id = (select auth.uid()) and role in ('responder', 'driver'))
  and incident_id is not null
  and exists (
    select 1 from public.incidents i
    where i.id = location_logs.incident_id
      and i.assigned_to = user_id
      and i.status = 'en_route'
  )
);

-- Disable the old unbound future endpoint. No checked-in client currently uses it.
revoke all on function public.api_record_location(double precision, double precision, real, timestamptz) from public, anon, authenticated;

create or replace function public.api_record_location_v2(
  p_incident_id bigint,
  p_latitude double precision,
  p_longitude double precision,
  p_accuracy_m real,
  p_recorded_at timestamptz
) returns bigint
language plpgsql security invoker set search_path = public, pg_temp as $$
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
$$;

revoke all on function public.api_record_location_v2(bigint, double precision, double precision, real, timestamptz) from public, anon;
grant execute on function public.api_record_location_v2(bigint, double precision, double precision, real, timestamptz) to authenticated;

grant select on public.location_logs to authenticated;
grant insert on public.location_logs to authenticated;

comment on function public.api_record_location_v2(bigint, double precision, double precision, real, timestamptz)
is 'Writes a validated responder GPS fix only for the authenticated unit assigned to this incident while it is en route.';

commit;
