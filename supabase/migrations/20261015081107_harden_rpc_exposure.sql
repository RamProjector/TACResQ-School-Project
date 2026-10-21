-- Keep the narrowly scoped definer helper in a non-API-exposed schema.
create schema if not exists private;
create or replace function private.tacresq_actor_role() returns text
language sql stable security definer set search_path = public, auth, pg_temp as $$
  select role from public.profiles where auth_user_id = auth.uid();
$$;
revoke all on function private.tacresq_actor_role() from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.tacresq_actor_role() to authenticated;

-- Re-create policies so they call the helper outside the PostgREST-exposed public schema.
drop policy if exists profiles_read_self_or_dispatcher on public.profiles;
create policy profiles_read_self_or_dispatcher on public.profiles for select to authenticated
using (auth_user_id = auth.uid() or private.tacresq_actor_role() = 'dispatcher');
drop policy if exists incidents_read_participant_or_dispatcher on public.incidents;
create policy incidents_read_participant_or_dispatcher on public.incidents for select to authenticated
using (requester_id in (select id from public.profiles where auth_user_id = auth.uid())
  or assigned_to in (select id from public.profiles where auth_user_id = auth.uid())
  or private.tacresq_actor_role() = 'dispatcher');
drop policy if exists incidents_update_dispatcher_or_assigned_unit on public.incidents;
create policy incidents_update_dispatcher_or_assigned_unit on public.incidents for update to authenticated
using (private.tacresq_actor_role() = 'dispatcher' or assigned_to in (select id from public.profiles where auth_user_id = auth.uid()))
with check (private.tacresq_actor_role() = 'dispatcher' or assigned_to in (select id from public.profiles where auth_user_id = auth.uid()));
drop policy if exists status_logs_read_participants on public.incident_status_logs;
create policy status_logs_read_participants on public.incident_status_logs for select to authenticated
using (exists(select 1 from public.incidents i join public.profiles p on p.id = i.requester_id where i.id = incident_id
  and (p.auth_user_id = auth.uid() or i.assigned_to in (select id from public.profiles where auth_user_id = auth.uid()) or private.tacresq_actor_role() = 'dispatcher')));
drop policy if exists location_logs_read_dispatcher_or_owner on public.location_logs;
create policy location_logs_read_dispatcher_or_owner on public.location_logs for select to authenticated
using (user_id in (select id from public.profiles where auth_user_id = auth.uid()) or private.tacresq_actor_role() = 'dispatcher');

-- RLS now gives dispatcher access to all unit profiles; no SECURITY DEFINER RPC is needed.
create or replace function public.api_units() returns jsonb
language plpgsql stable security invoker set search_path = public, pg_temp as $$
begin
  if private.tacresq_actor_role() <> 'dispatcher' then
    raise exception 'Only dispatchers can view field units' using errcode='42501';
  end if;
  return (select coalesce(jsonb_agg(jsonb_build_object('id',id,'name',name,'email',email,'phone',phone,'role',role,'duty_status',duty_status) order by name),'[]'::jsonb)
          from public.profiles where role in ('responder','driver'));
end $$;
revoke all on function public.api_units() from public, anon;
grant execute on function public.api_units() to authenticated;

-- Remove the previously exposed helper after all policies have been migrated.
drop function if exists public.tacresq_actor_role();
