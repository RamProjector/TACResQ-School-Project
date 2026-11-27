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
SELECT pg_sleep(30);
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
  for attempt in $(seq 1 200); do
    observed="$(psql -qAt -v ON_ERROR_STOP=1 -c "
      WITH workers AS (
        SELECT pid, pg_blocking_pids(pid) AS blockers
        FROM pg_stat_activity
        WHERE application_name IN ('$app_a', '$app_b')
          AND wait_event_type = 'Lock'
      )
      SELECT CASE
        WHEN count(*) = 2 AND bool_and(
          EXISTS (
            SELECT 1
            FROM unnest(workers.blockers) AS blocker(pid)
            WHERE blocker.pid = $coordinator_pid
               OR blocker.pid IN (SELECT pid FROM workers)
          )
        ) THEN 2
        ELSE 0
      END
      FROM workers
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


# Race C: a real dispatcher assignment obtains the responder FOR SHARE lock
# first. The real Admin suspension RPC must wait, then reject after assignment
# commits with SQLSTATE 55006. No simulated profile writes or test-only locks
# stand in for either production path.
suspension_admin_uuid="00000000-0000-4000-8000-000000000962"
suspension_admin_session_id="10000000-0000-4000-8000-000000000962"
assignment_first_responder_uuid="00000000-0000-4000-8000-000000000963"
suspension_first_responder_uuid="00000000-0000-4000-8000-000000000964"
assignment_first_requester_uuid="00000000-0000-4000-8000-000000000960"
suspension_first_requester_uuid="00000000-0000-4000-8000-000000000961"

psql -q -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES
  ('00000000-0000-4000-8000-000000000960', 'suspension-race-requester-a@example.test', '{"name":"Suspension Race Requester A","phone":"+63917000960"}'),
  ('00000000-0000-4000-8000-000000000961', 'suspension-race-requester-b@example.test', '{"name":"Suspension Race Requester B","phone":"+63917000961"}'),
  ('00000000-0000-4000-8000-000000000962', 'suspension-race-admin@example.test', '{"name":"Suspension Race Admin","phone":"+63917000962"}'),
  ('00000000-0000-4000-8000-000000000963', 'suspension-race-assignment-first@example.test', '{"name":"Assignment First Responder","phone":"+63917000963"}'),
  ('00000000-0000-4000-8000-000000000964', 'suspension-race-suspension-first@example.test', '{"name":"Suspension First Responder","phone":"+63917000964"}');

UPDATE public.profiles SET role = 'admin', duty_status = NULL
WHERE auth_user_id = '00000000-0000-4000-8000-000000000962';
INSERT INTO auth.sessions (id, user_id)
VALUES (
  '10000000-0000-4000-8000-000000000962',
  '00000000-0000-4000-8000-000000000962'
);
UPDATE public.profiles SET role = 'responder', duty_status = 'available'
WHERE auth_user_id IN (
  '00000000-0000-4000-8000-000000000963',
  '00000000-0000-4000-8000-000000000964'
);

INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude,
  location_recorded_at, location_source, is_claimable
)
SELECT p.id, 'medical', seed.description, 11.2460, 125.0060, now(), 'manual', false
FROM (VALUES
  ('00000000-0000-4000-8000-000000000960'::uuid, 'Disposable assignment-first suspension race'),
  ('00000000-0000-4000-8000-000000000961'::uuid, 'Disposable suspension-first assignment race')
) AS seed(requester_uuid, description)
JOIN public.profiles p ON p.auth_user_id = seed.requester_uuid;
SQL

suspension_admin_id="$(profile_id_for "$suspension_admin_uuid")"
assignment_first_responder_id="$(profile_id_for "$assignment_first_responder_uuid")"
suspension_first_responder_id="$(profile_id_for "$suspension_first_responder_uuid")"
assignment_first_incident_id="$(incident_id_for 'Disposable assignment-first suspension race')"
suspension_first_incident_id="$(incident_id_for 'Disposable suspension-first assignment race')"
for required_id in "$suspension_admin_id" "$assignment_first_responder_id" \
  "$suspension_first_responder_id" "$assignment_first_incident_id" "$suspension_first_incident_id"; do
  if [[ -z "$required_id" ]]; then
    echo "Failed to create an account-suspension concurrency fixture." >&2
    exit 1
  fi
done

wait_for_single_lock_waiter() {
  local app_name="$1"
  local blocker_pid="$2"
  local observed=""
  local attempt
  for attempt in $(seq 1 200); do
    observed="$(psql -qAt -v ON_ERROR_STOP=1 -c "
      SELECT pid FROM pg_stat_activity
      WHERE application_name = '$app_name'
        AND wait_event_type = 'Lock'
        AND $blocker_pid = ANY(pg_blocking_pids(pid))
      LIMIT 1
    ")"
    if [[ "$observed" =~ ^[0-9]+$ ]]; then
      printf '%s\n' "$observed"
      return 0
    fi
    sleep 0.05
  done
  echo "Worker $app_name did not wait on expected blocker PID $blocker_pid." >&2
  psql -x -v ON_ERROR_STOP=1 -c "
    SELECT pid, application_name, state, wait_event_type, wait_event,
           pg_blocking_pids(pid) AS blockers, left(query, 240) AS query
    FROM pg_stat_activity
    WHERE application_name LIKE 'tacresq-suspension-%'
    ORDER BY application_name
  " >&2
  return 1
}

run_assignment_and_hold() {
  local app_name="$1"
  local dispatcher_uuid="$2"
  local incident_id="$3"
  local responder_id="$4"
  local output_file="$5"
  local error_file="$6"
  PGAPPNAME="$app_name" psql -qAt -v ON_ERROR_STOP=1 >"$output_file" 2>"$error_file" <<SQL
\set VERBOSITY verbose
SELECT set_config('application_name', '$app_name', false);
SELECT set_config('request.jwt.claim.sub', '$dispatcher_uuid', false);
SET ROLE authenticated;
BEGIN;
SELECT public.api_assign_incident_team($incident_id, ARRAY[$responder_id]::bigint[]);
SELECT pg_sleep(4);
COMMIT;
RESET ROLE;
SQL
}

run_assignment_once() {
  local app_name="$1"
  local dispatcher_uuid="$2"
  local incident_id="$3"
  local responder_id="$4"
  local output_file="$5"
  local error_file="$6"
  PGAPPNAME="$app_name" psql -qAt -v ON_ERROR_STOP=1 >"$output_file" 2>"$error_file" <<SQL
\set VERBOSITY verbose
SELECT set_config('application_name', '$app_name', false);
SELECT set_config('request.jwt.claim.sub', '$dispatcher_uuid', false);
SET ROLE authenticated;
BEGIN;
SELECT public.api_assign_incident_team($incident_id, ARRAY[$responder_id]::bigint[]);
COMMIT;
RESET ROLE;
SQL
}

run_admin_suspension_and_hold() {
  local app_name="$1"
  local target_profile_id="$2"
  local output_file="$3"
  local error_file="$4"
  # This disposable replay runs as the migration owner to coordinate the
  # two-connection race. The function still validates auth.uid(), AAL2, TOTP
  # AMR, active Admin role, and target eligibility.
  PGAPPNAME="$app_name" psql -qAt -v ON_ERROR_STOP=1 >"$output_file" 2>"$error_file" <<SQL
\set VERBOSITY verbose
SELECT set_config('application_name', '$app_name', false);
SELECT set_config('request.jwt.claim.sub', '$suspension_admin_uuid', false);
SELECT set_config('request.jwt.claims', '{"session_id":"$suspension_admin_session_id","aal":"aal2","amr":[{"method":"totp"}]}', false);
BEGIN;
SELECT public.api_admin_set_account_status($target_profile_id, 'active', 'suspended', 'Disposable concurrency regression');
SELECT pg_sleep(4);
COMMIT;
SQL
}

run_admin_suspension_once() {
  local app_name="$1"
  local target_profile_id="$2"
  local output_file="$3"
  local error_file="$4"
  PGAPPNAME="$app_name" psql -qAt -v ON_ERROR_STOP=1 >"$output_file" 2>"$error_file" <<SQL
\set VERBOSITY verbose
SELECT set_config('application_name', '$app_name', false);
SELECT set_config('request.jwt.claim.sub', '$suspension_admin_uuid', false);
SELECT set_config('request.jwt.claims', '{"session_id":"$suspension_admin_session_id","aal":"aal2","amr":[{"method":"totp"}]}', false);
BEGIN;
SELECT public.api_admin_set_account_status($target_profile_id, 'active', 'suspended', 'Disposable concurrency regression');
COMMIT;
SQL
}

# Assignment wins first: hold the actual assignment RPC transaction open after
# its BEFORE INSERT trigger has acquired FOR SHARE. The Admin RPC must block.
assignment_first_audit_before="$(psql -qAt -v ON_ERROR_STOP=1 -c "
  SELECT count(*) FROM public.account_lifecycle_audit_logs
  WHERE target_profile_id = $assignment_first_responder_id
    AND action = 'account_suspended'
")"
assignment_first_app="tacresq-suspension-assignment-first"
assignment_first_admin_app="tacresq-suspension-admin-waits"
run_assignment_and_hold "$assignment_first_app" "$dispatcher_a_uuid" \
  "$assignment_first_incident_id" "$assignment_first_responder_id" \
  "$tmp_dir/suspension-assignment-first.out" "$tmp_dir/suspension-assignment-first.err" &
assignment_first_shell_pid=$!
if ! assignment_first_backend_pid="$(wait_for_coordinator_pid "$assignment_first_app")"; then
  wait "$assignment_first_shell_pid" || true
  exit 1
fi
run_admin_suspension_once "$assignment_first_admin_app" "$assignment_first_responder_id" \
  "$tmp_dir/suspension-admin-waits.out" "$tmp_dir/suspension-admin-waits.err" &
assignment_first_admin_shell_pid=$!
if ! wait_for_single_lock_waiter "$assignment_first_admin_app" "$assignment_first_backend_pid" >/dev/null; then
  wait "$assignment_first_shell_pid" || true
  wait "$assignment_first_admin_shell_pid" || true
  exit 1
fi
if ! wait "$assignment_first_shell_pid"; then
  echo "Assignment-first worker unexpectedly failed." >&2
  cat "$tmp_dir/suspension-assignment-first.err" >&2
  wait "$assignment_first_admin_shell_pid" || true
  exit 1
fi
if wait "$assignment_first_admin_shell_pid"; then
  echo "Admin suspension unexpectedly succeeded after active assignment committed." >&2
  exit 1
fi
if ! grep -q '55006' "$tmp_dir/suspension-admin-waits.err"; then
  echo "Admin suspension did not reject the assignment-first race with SQLSTATE 55006." >&2
  cat "$tmp_dir/suspension-admin-waits.err" >&2
  exit 1
fi

psql -qAt -v ON_ERROR_STOP=1 <<SQL >"$tmp_dir/suspension-assignment-first-final"
SELECT account_status FROM public.profiles WHERE id = $assignment_first_responder_id;
SELECT count(*) FROM public.incident_assignments
 WHERE user_id = $assignment_first_responder_id AND incident_id = $assignment_first_incident_id;
SELECT status FROM public.incidents WHERE id = $assignment_first_incident_id;
SELECT count(*) FROM public.account_lifecycle_audit_logs
 WHERE target_profile_id = $assignment_first_responder_id AND action = 'account_suspended';
SQL
if [[ "$(sed -n '1p' "$tmp_dir/suspension-assignment-first-final")" != "active" ||
      "$(sed -n '2p' "$tmp_dir/suspension-assignment-first-final")" -ne 1 ||
      "$(sed -n '3p' "$tmp_dir/suspension-assignment-first-final")" != "assigned" ||
      "$(sed -n '4p' "$tmp_dir/suspension-assignment-first-final")" != "$assignment_first_audit_before" ]]; then
  echo "Assignment-first race violated profile, assignment, incident, or audit atomicity." >&2
  cat "$tmp_dir/suspension-assignment-first-final" >&2
  exit 1
fi

# Suspension wins first: commit the real suspension RPC only after the
# assignment trigger is observed waiting for the target profile FOR SHARE.
suspension_first_audit_before="$(psql -qAt -v ON_ERROR_STOP=1 -c "
  SELECT count(*) FROM public.account_lifecycle_audit_logs
  WHERE target_profile_id = $suspension_first_responder_id
    AND action = 'account_suspended'
")"
suspension_first_admin_app="tacresq-suspension-admin-first"
suspension_first_assignment_app="tacresq-suspension-assignment-waits"
run_admin_suspension_and_hold "$suspension_first_admin_app" "$suspension_first_responder_id" \
  "$tmp_dir/suspension-admin-first.out" "$tmp_dir/suspension-admin-first.err" &
suspension_first_admin_shell_pid=$!
if ! suspension_first_backend_pid="$(wait_for_coordinator_pid "$suspension_first_admin_app")"; then
  wait "$suspension_first_admin_shell_pid" || true
  exit 1
fi
run_assignment_once "$suspension_first_assignment_app" "$dispatcher_a_uuid" \
  "$suspension_first_incident_id" "$suspension_first_responder_id" \
  "$tmp_dir/suspension-assignment-waits.out" "$tmp_dir/suspension-assignment-waits.err" &
suspension_first_assignment_shell_pid=$!
if ! wait_for_single_lock_waiter "$suspension_first_assignment_app" "$suspension_first_backend_pid" >/dev/null; then
  wait "$suspension_first_admin_shell_pid" || true
  wait "$suspension_first_assignment_shell_pid" || true
  exit 1
fi
if ! wait "$suspension_first_admin_shell_pid"; then
  echo "Suspension-first Admin worker unexpectedly failed." >&2
  cat "$tmp_dir/suspension-admin-first.err" >&2
  wait "$suspension_first_assignment_shell_pid" || true
  exit 1
fi
if wait "$suspension_first_assignment_shell_pid"; then
  echo "Assignment unexpectedly succeeded after suspension committed." >&2
  exit 1
fi
if ! grep -q '55006' "$tmp_dir/suspension-assignment-waits.err"; then
  echo "Assignment did not reject the suspension-first race with SQLSTATE 55006." >&2
  cat "$tmp_dir/suspension-assignment-waits.err" >&2
  exit 1
fi

psql -qAt -v ON_ERROR_STOP=1 <<SQL >"$tmp_dir/suspension-first-final"
SELECT account_status FROM public.profiles WHERE id = $suspension_first_responder_id;
SELECT count(*) FROM public.incident_assignments
 WHERE user_id = $suspension_first_responder_id AND incident_id = $suspension_first_incident_id;
SELECT status FROM public.incidents WHERE id = $suspension_first_incident_id;
SELECT count(*) FROM public.account_lifecycle_audit_logs
 WHERE target_profile_id = $suspension_first_responder_id AND action = 'account_suspended';
SQL
if [[ "$(sed -n '1p' "$tmp_dir/suspension-first-final")" != "suspended" ||
      "$(sed -n '2p' "$tmp_dir/suspension-first-final")" -ne 0 ||
      "$(sed -n '3p' "$tmp_dir/suspension-first-final")" != "pending" ||
      "$(sed -n '4p' "$tmp_dir/suspension-first-final")" -ne "$((suspension_first_audit_before + 1))" ]]; then
  echo "Suspension-first race violated profile, assignment, incident, or audit atomicity." >&2
  cat "$tmp_dir/suspension-first-final" >&2
  exit 1
fi

echo "PASS: real assignment-first versus Admin suspension race; suspension waited and rejected with SQLSTATE 55006."
echo "PASS: real suspension-first versus assignment race; assignment waited and rejected with SQLSTATE 55006."
echo "PASS: both race outcomes preserve final profile state, assignment rows, incident state, and audit atomicity."
