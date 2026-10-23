-- Durable, requester-scoped idempotency for SOS submissions.
-- A repeated request key + identical payload returns the original incident response.
create table if not exists public.incident_submission_keys (
  requester_id bigint not null references public.profiles(id) on delete cascade,
  client_request_id uuid not null,
  request_payload jsonb not null,
  response_payload jsonb,
  created_at timestamptz not null default now(),
  primary key (requester_id, client_request_id)
);
alter table public.incident_submission_keys enable row level security;
revoke all on public.incident_submission_keys from public, anon, authenticated;

create or replace function public.api_create_incident_v4(
  p_type text,
  p_description text,
  p_latitude double precision,
  p_longitude double precision,
  p_accuracy_m real,
  p_location_notes text,
  p_location_recorded_at timestamptz,
  p_location_source text,
  p_client_request_id uuid
) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog
as $function$
declare
  profile_row public.profiles;
  saved_payload jsonb;
  saved_response jsonb;
  current_payload jsonb;
begin
  if auth.uid() is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;
  select * into profile_row
    from public.profiles
   where auth_user_id = auth.uid()
   for share;
  if profile_row.id is null or profile_row.role <> 'requester' then
    raise exception 'Only requesters can submit incidents' using errcode = '42501';
  end if;
  if p_client_request_id is null then
    raise exception 'A request identifier is required' using errcode = '22023';
  end if;

  current_payload := jsonb_build_object(
    'type', p_type,
    'description', nullif(trim(p_description), ''),
    'latitude', p_latitude,
    'longitude', p_longitude,
    'accuracy_m', p_accuracy_m,
    'location_notes', nullif(trim(p_location_notes), ''),
    'location_recorded_at', p_location_recorded_at,
    'location_source', p_location_source
  );

  -- The unique key serializes concurrent retries. The conflicting INSERT waits for
  -- the first transaction; SELECT FOR UPDATE then sees its committed response.
  insert into public.incident_submission_keys(
    requester_id, client_request_id, request_payload
  ) values (
    profile_row.id, p_client_request_id, current_payload
  ) on conflict (requester_id, client_request_id) do nothing;

  select request_payload, response_payload
    into saved_payload, saved_response
    from public.incident_submission_keys
   where requester_id = profile_row.id
     and client_request_id = p_client_request_id
   for update;

  if saved_payload is distinct from current_payload then
    raise exception 'This request identifier is already tied to a different SOS. Start a new request.' using errcode = '22023';
  end if;
  if saved_response is not null then
    return saved_response;
  end if;

  saved_response := public.api_create_incident_v3(
    p_type, p_description, p_latitude, p_longitude, p_accuracy_m,
    p_location_notes, p_location_recorded_at, p_location_source
  );

  update public.incident_submission_keys
     set response_payload = saved_response
   where requester_id = profile_row.id
     and client_request_id = p_client_request_id;

  return saved_response;
end;
$function$;

revoke all on function public.api_create_incident_v4(text, text, double precision, double precision, real, text, timestamptz, text, uuid) from public, anon;
grant execute on function public.api_create_incident_v4(text, text, double precision, double precision, real, text, timestamptz, text, uuid) to authenticated;
