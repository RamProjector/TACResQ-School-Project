-- Cover incident/status audit foreign keys and avoid per-row auth setting evaluation in policies.
create index if not exists incident_status_logs_incident_idx on public.incident_status_logs (incident_id);
create index if not exists incident_status_logs_changed_by_idx on public.incident_status_logs (changed_by);

drop policy if exists profiles_read_self_or_dispatcher on public.profiles;
create policy profiles_read_self_or_dispatcher on public.profiles for select to authenticated
using (auth_user_id = (select auth.uid()) or (select private.tacresq_actor_role()) = 'dispatcher');
drop policy if exists profiles_update_self on public.profiles;
create policy profiles_update_self on public.profiles for update to authenticated
using (auth_user_id = (select auth.uid()) and role in ('responder','driver'))
with check (auth_user_id = (select auth.uid()) and role in ('responder','driver'));
drop policy if exists incidents_read_participant_or_dispatcher on public.incidents;
create policy incidents_read_participant_or_dispatcher on public.incidents for select to authenticated
using (requester_id in (select id from public.profiles where auth_user_id = (select auth.uid()))
  or assigned_to in (select id from public.profiles where auth_user_id = (select auth.uid()))
  or (select private.tacresq_actor_role()) = 'dispatcher');
drop policy if exists incidents_insert_requester on public.incidents;
create policy incidents_insert_requester on public.incidents for insert to authenticated
with check (requester_id in (select id from public.profiles where auth_user_id = (select auth.uid()) and role = 'requester') and status = 'pending' and assigned_to is null);
drop policy if exists incidents_update_dispatcher_or_assigned_unit on public.incidents;
create policy incidents_update_dispatcher_or_assigned_unit on public.incidents for update to authenticated
using ((select private.tacresq_actor_role()) = 'dispatcher' or assigned_to in (select id from public.profiles where auth_user_id = (select auth.uid())))
with check ((select private.tacresq_actor_role()) = 'dispatcher' or assigned_to in (select id from public.profiles where auth_user_id = (select auth.uid())));
drop policy if exists status_logs_read_participants on public.incident_status_logs;
create policy status_logs_read_participants on public.incident_status_logs for select to authenticated
using (exists(select 1 from public.incidents i join public.profiles p on p.id = i.requester_id where i.id = incident_id
  and (p.auth_user_id = (select auth.uid()) or i.assigned_to in (select id from public.profiles where auth_user_id = (select auth.uid())) or (select private.tacresq_actor_role()) = 'dispatcher')));
drop policy if exists location_logs_read_dispatcher_or_owner on public.location_logs;
create policy location_logs_read_dispatcher_or_owner on public.location_logs for select to authenticated
using (user_id in (select id from public.profiles where auth_user_id = (select auth.uid())) or (select private.tacresq_actor_role()) = 'dispatcher');
drop policy if exists location_logs_insert_owner on public.location_logs;
create policy location_logs_insert_owner on public.location_logs for insert to authenticated
with check (user_id in (select id from public.profiles where auth_user_id = (select auth.uid()) and role in ('responder','driver')));
