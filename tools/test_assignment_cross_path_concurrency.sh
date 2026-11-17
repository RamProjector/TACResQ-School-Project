#!/usr/bin/env bash
# Deterministic cross-path concurrency tests for field claims and dispatcher assignment.
# The coordinator holds a responder profile row until both workers are observed
# waiting behind it in pg_stat_activity, then releases the competing transactions.
set -euo pipefail

: "$PGHOST"
: "$PGPORT"
: "$PGUSER"
: "$PGPASSWORD"
: "$PGDATABASE"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

dispatcher_a_uuid="00000000-0000-4000-8000-000000000930"
dispatcher_b_uuid="00000000-0000-4000-8000-000000000931"
cross_responder_uuid="00000000-0000-4000-8000-000000000940"
dispatch_responder_uuid="00000000-0000-4000-8000-000000000941"

psql -q -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES
  ('00000000-0000-4000-8000-000000000920', 'claim-race-requester-d@example.test', '{"name":"Claim Race Requester D","phone":"+63917000920"}'),
  ('00000000-0000-4000-8000-000000000921', 'claim-race-requester-e@example.test', '{"name":"Claim Race Requester E","phone":"+63917000921"}'),
  ('00000000-0000-4000-8000-000000000922', 'claim-race-requester-f@example.test', '{"name":"Claim Race Requester F","phone":"+63917000922"}'),
  ('00000000-0000-4000-8000-000000000923', 'claim-race-requester-g@example.test', '{"name":"Claim Race Requester G","phone":"+63917000923"}'),
  ('00000000-0000-4000-8000-000000000930', 'claim-race-dispatcher-a@example.test', '{"name":"Claim Race Dispatcher A","phone":"+63917000930"}'),
  ('00000000-0000-4000-8000-000000000931', 'claim-race-dispatcher-b@example.test', '{"name":"Claim Race Dispatcher B","phone":"+63917000931"}'),
  ('00000000-0000-4000-8000-000000000940', 'claim-race-responder-cross@example.test', '{"name":"Claim Race Cross-path Responder","phone":"+63917000940"}'),
  ('00000000-0000-4000-8000-000000000941', 'claim-race-responder-dispatch@example.test', '{"name":"Claim Race Dispatcher Responder","phone":"+63917000941"}');

UPDATE public.profiles SET role = 'dispatcher'
WHERE auth_user_id IN (
  '00000000-0000-4000-8000-000000000930',
  '00000000-0000-4000-8000-000000000931'
);
UPDATE public.profiles SET role = 'responder', duty_status = 'available'
WHERE auth_user_id IN (
  '00000000-0000-4000-8000-000000000940',
  '00000000-0000-4000-8000-000000000941'
);

INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude,
  location_recorded_at, location_source, is_claimable
)
SELECT p.id, 'medical', seed.description, 11.2450, 125.0050, now(), 'manual', true
FROM (VALUES
  ('00000000-0000-4000-8000-000000000920'::uuid, 'Disposable field-vs-dispatch race'),
  ('00000000-0000-4000-8000-000000000921'::uuid, 'Disposable field-vs-dispatch competitor'),
  ('00000000-0000-4000-8000-000000000922'::uuid, 'Disposable dispatcher race A'),
  ('00000000-0000-4000-8000-000000000923'::uuid, 'Disposable dispatcher race B')
) AS seed(requester_uuid, description)
JOIN public.profiles p ON p.auth_user_id = seed.requester_uuid;
SQL

profile_id_for() {
  psql -qAt -v ON_ERROR_STOP=1 -c "SELECT id FROM public.profiles WHERE auth_user_id = '$1'"
}
incident_id_for() {
  psql -qAt -v ON_ERROR_STOP=1 -c "SELECT id FROM public.incidents WHERE description = '$1'"
}

cross_responder_id="$(profile_id_for "$cross_responder_uuid")"
dispatch_responder_id="$(profile_id_for "$dispatch_responder_uuid")"
cross_claim_incident_id="$(incident_id_for 'Disposable field-vs-dispatch race')"
cross_dispatch_incident_id="$(incident_id_for 'Disposable field-vs-dispatch competitor')"
dispatch_incident_a_id="$(incident_id_for 'Disposable dispatcher race A')"
dispatch_incident_b_id="$(incident_id_for 'Disposable dispatcher race B')"

for required_id in "$cross_responder_id" "$dispatch_responder_id" "$cross_claim_incident_id" "$cross_dispatch_incident_id" "$dispatch_incident_a_id" "$dispatch_incident_b_id"; do
  if [[ -z "$required_id" ]]; then
    echo "Failed to create a cross-path concurrency fixture." >&2
    exit 1
  fi
done

hold_profile_lock() {
  local profile_id="$1"
  local app_name="$2"
  PGAPPNAME="$app_name" psql -q -v ON_ERROR_STOP=1 >/dev/null <<SQL
SELECT set_config('application_name', '$app_name', false);
BEGIN;
SELECT id FROM public.profiles WHERE id = $profile_id FOR UPDATE;
SELECT pg_sleep(12);
COMMIT;
SQL
}

wait_for_coordinator_pid() {
  local app_name="$1"
  local observed_pid=""
  local attempt
  for attempt in $(seq 1 100); do
    observed_pid="$(psql -qAt -v ON_ERROR_STOP=1 -c "
      SELECT pid FROM pg_stat_activity
      WHERE application_name = '$app_name'
        AND wait_event_type = 'Timeout'
        AND wait_event = 'PgSleep'
      LIMIT 1
    ")"
    if [[ "$observed_pid" =~ ^[0-9]+$ ]]; then
      printf '%s\n' "$observed_pid"
      return 0
    fi
    sleep 0.05
  done
  echo "Coordinator $app_name did not reach its lock-holding sleep." >&2
  return 1
}

wait_for_two_lock_waiters() {
  local app_a="$1"
  local app_b="$2"
  local coordinator_pid="$3"
  local observed=0
  local attempt
  for attempt in $(seq 1 100); do
    observed="$(psql -qAt -v ON_ERROR_STOP=1 -c "
      SELECT count(*)
      FROM pg_stat_activity a
      WHERE a.application_name IN ('$app_a', '$app_b')
        AND a.wait_event_type = 'Lock'
        AND $coordinator_pid = ANY(pg_blocking_pids(a.pid))
    ")"
    if [[ "$observed" -eq 2 ]]; then return 0; fi
    sleep 0.1
  done
  echo "Expected both workers to be blocked by coordinator PID $coordinator_pid; observed $observed." >&2
  psql -x -v ON_ERROR_STOP=1 -c "
    SELECT pid, application_name, state, wait_event_type, wait_event,
           pg_blocking_pids(pid) AS blockers, left(query, 200) AS query
    FROM pg_stat_activity
    WHERE application_name LIKE 'tacresq-%'
    ORDER BY application_name
  " >&2
  return 1
}

run_claim_worker() {
  local app_name="$1"
  local actor_uuid="$2"
  local incident_id="$3"
  local output_file="$4"
  local error_file="$5"
  PGAPPNAME="$app_name" psql -qAt -v ON_ERROR_STOP=1 >"$output_file" 2>"$error_file" <<SQL
SELECT set_config('application_name', '$app_name', false);
SELECT set_config('request.jwt.claim.sub', '$actor_uuid', false);
SET ROLE authenticated;
BEGIN;
SELECT public.api_claim_incident($incident_id);
COMMIT;
RESET ROLE;
SQL
}

run_dispatch_worker() {
  local app_name="$1"
  local dispatcher_uuid="$2"
  local incident_id="$3"
  local responder_id="$4"
  local output_file="$5"
  local error_file="$6"
  PGAPPNAME="$app_name" psql -qAt -v ON_ERROR_STOP=1 >"$output_file" 2>"$error_file" <<SQL
SELECT set_config('application_name', '$app_name', false);
SELECT set_config('request.jwt.claim.sub', '$dispatcher_uuid', false);
SET ROLE authenticated;
BEGIN;
SELECT public.api_assign_incident_team($incident_id, ARRAY[$responder_id]::bigint[]);
COMMIT;
RESET ROLE;
SQL
}

# Race A: field self-claim versus dispatcher assignment, on different incidents.
cross_coordinator_app="tacresq-cross-coordinator"
cross_worker_a_app="tacresq-cross-field-worker"
cross_worker_b_app="tacresq-cross-dispatch-worker"
hold_profile_lock "$cross_responder_id" "$cross_coordinator_app" &
cross_coordinator_shell_pid=$!
if ! cross_coordinator_backend_pid="$(wait_for_coordinator_pid "$cross_coordinator_app")"; then
  wait "$cross_coordinator_shell_pid" || true
  exit 1
fi
run_claim_worker "$cross_worker_a_app" "$cross_responder_uuid" "$cross_claim_incident_id" "$tmp_dir/cross-field.out" "$tmp_dir/cross-field.err" &
cross_worker_a_pid=$!
run_dispatch_worker "$cross_worker_b_app" "$dispatcher_a_uuid" "$cross_dispatch_incident_id" "$cross_responder_id" "$tmp_dir/cross-dispatch.out" "$tmp_dir/cross-dispatch.err" &
cross_worker_b_pid=$!
if ! wait_for_two_lock_waiters "$cross_worker_a_app" "$cross_worker_b_app" "$cross_coordinator_backend_pid"; then
  wait "$cross_worker_a_pid" || true
  wait "$cross_worker_b_pid" || true
  wait "$cross_coordinator_shell_pid" || true
  exit 1
fi
if wait "$cross_worker_a_pid"; then cross_field_status=0; else cross_field_status=1; fi
if wait "$cross_worker_b_pid"; then cross_dispatch_status=0; else cross_dispatch_status=1; fi
wait "$cross_coordinator_shell_pid"

cross_claimed="$(grep -c '"status": "claimed"' "$tmp_dir/cross-field.out" || true)"
cross_busy="$(grep -c '"code": "UNIT_BUSY"' "$tmp_dir/cross-field.out" || true)"
if ! {
  [[ "$cross_field_status" -eq 0 && "$cross_dispatch_status" -eq 1 && "$cross_claimed" -eq 1 ]] ||
  [[ "$cross_field_status" -eq 0 && "$cross_dispatch_status" -eq 0 && "$cross_busy" -eq 1 ]]
}; then
  echo "Field-vs-dispatch race did not produce exactly one winner." >&2
  cat "$tmp_dir/cross-field.out" "$tmp_dir/cross-dispatch.out" "$tmp_dir/cross-dispatch.err" >&2
  exit 1
fi
if [[ "$cross_dispatch_status" -eq 1 ]] && ! grep -q 'no longer available' "$tmp_dir/cross-dispatch.err"; then
  echo "Dispatcher lost field-vs-dispatch race for an unexpected reason." >&2
  cat "$tmp_dir/cross-dispatch.err" >&2
  exit 1
fi

psql -qAt -v ON_ERROR_STOP=1 <<SQL >"$tmp_dir/cross-final-counts"
SELECT count(*) FROM public.incident_assignments
WHERE user_id = $cross_responder_id
  AND incident_id IN ($cross_claim_incident_id, $cross_dispatch_incident_id);
SELECT count(*) FROM public.incidents
WHERE id IN ($cross_claim_incident_id, $cross_dispatch_incident_id)
  AND status = 'assigned';
SQL
cross_memberships="$(sed -n '1p' "$tmp_dir/cross-final-counts")"
cross_assigned_incidents="$(sed -n '2p' "$tmp_dir/cross-final-counts")"
if [[ "$cross_memberships" -ne 1 || "$cross_assigned_incidents" -ne 1 ]]; then
  echo "Field-vs-dispatch race violated the one-active-incident invariant." >&2
  cat "$tmp_dir/cross-final-counts" >&2
  exit 1
fi

# Race B: two different dispatchers assign one responder to different incidents.
dispatch_coordinator_app="tacresq-dispatch-coordinator"
dispatch_worker_a_app="tacresq-dispatcher-a-worker"
dispatch_worker_b_app="tacresq-dispatcher-b-worker"
hold_profile_lock "$dispatch_responder_id" "$dispatch_coordinator_app" &
dispatch_coordinator_shell_pid=$!
if ! dispatch_coordinator_backend_pid="$(wait_for_coordinator_pid "$dispatch_coordinator_app")"; then
  wait "$dispatch_coordinator_shell_pid" || true
  exit 1
fi
run_dispatch_worker "$dispatch_worker_a_app" "$dispatcher_a_uuid" "$dispatch_incident_a_id" "$dispatch_responder_id" "$tmp_dir/dispatch-a.out" "$tmp_dir/dispatch-a.err" &
dispatch_worker_a_pid=$!
run_dispatch_worker "$dispatch_worker_b_app" "$dispatcher_b_uuid" "$dispatch_incident_b_id" "$dispatch_responder_id" "$tmp_dir/dispatch-b.out" "$tmp_dir/dispatch-b.err" &
dispatch_worker_b_pid=$!
if ! wait_for_two_lock_waiters "$dispatch_worker_a_app" "$dispatch_worker_b_app" "$dispatch_coordinator_backend_pid"; then
  wait "$dispatch_worker_a_pid" || true
  wait "$dispatch_worker_b_pid" || true
  wait "$dispatch_coordinator_shell_pid" || true
  exit 1
fi
if wait "$dispatch_worker_a_pid"; then dispatch_a_status=0; else dispatch_a_status=1; fi
if wait "$dispatch_worker_b_pid"; then dispatch_b_status=0; else dispatch_b_status=1; fi
wait "$dispatch_coordinator_shell_pid"

if [[ $((dispatch_a_status + dispatch_b_status)) -ne 1 ]]; then
  echo "Expected exactly one dispatcher assignment to succeed and one to be rejected." >&2
  cat "$tmp_dir/dispatch-a.out" "$tmp_dir/dispatch-b.out" "$tmp_dir/dispatch-a.err" "$tmp_dir/dispatch-b.err" >&2
  exit 1
fi
if [[ "$dispatch_a_status" -eq 1 ]] && ! grep -q 'no longer available' "$tmp_dir/dispatch-a.err"; then
  echo "Dispatcher A lost the race for an unexpected reason." >&2
  cat "$tmp_dir/dispatch-a.err" >&2
  exit 1
fi
if [[ "$dispatch_b_status" -eq 1 ]] && ! grep -q 'no longer available' "$tmp_dir/dispatch-b.err"; then
  echo "Dispatcher B lost the race for an unexpected reason." >&2
  cat "$tmp_dir/dispatch-b.err" >&2
  exit 1
fi

psql -qAt -v ON_ERROR_STOP=1 <<SQL >"$tmp_dir/dispatch-final-counts"
SELECT count(*) FROM public.incident_assignments
WHERE user_id = $dispatch_responder_id
  AND incident_id IN ($dispatch_incident_a_id, $dispatch_incident_b_id);
SELECT count(*) FROM public.incidents
WHERE id IN ($dispatch_incident_a_id, $dispatch_incident_b_id)
  AND status = 'assigned';
SQL
dispatch_memberships="$(sed -n '1p' "$tmp_dir/dispatch-final-counts")"
dispatch_assigned_incidents="$(sed -n '2p' "$tmp_dir/dispatch-final-counts")"
if [[ "$dispatch_memberships" -ne 1 || "$dispatch_assigned_incidents" -ne 1 ]]; then
  echo "Dispatcher-vs-dispatcher race violated the one-active-incident invariant." >&2
  cat "$tmp_dir/dispatch-final-counts" >&2
  exit 1
fi

echo "PASS: deterministic field-claim versus dispatcher-assignment race; exactly one incident was assigned."
echo "PASS: deterministic two-dispatcher race; exactly one assignment committed."
