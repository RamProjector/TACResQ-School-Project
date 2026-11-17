#!/usr/bin/env bash
# Deterministic two-session race between dispatcher claimability and requester cancellation.
# Runs only on the disposable PostgreSQL database after migration replay.
set -euo pipefail

: "${PGHOST:?PGHOST is required}"
: "${PGPORT:?PGPORT is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${PGDATABASE:?PGDATABASE is required}"

tmp_dir="$(mktemp -d)"
coordinator_pid=""
dispatcher_pid=""
requester_pid=""
cleanup() {
  if [[ -n "$coordinator_pid" ]]; then
    psql -q -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend($coordinator_pid) WHERE EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = $coordinator_pid)" >/dev/null 2>&1 || true
  fi
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

requester_uuid="00000000-0000-4000-8000-000000000974"
dispatcher_uuid="00000000-0000-4000-8000-000000000975"

psql -q -v ON_ERROR_STOP=1 -At <<'SQL' > "$tmp_dir/incident-id"
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES
  ('00000000-0000-4000-8000-000000000974', 'claimability-race-requester-974@example.test', '{"name":"Claimability Race Requester","phone":"+63917000974"}'),
  ('00000000-0000-4000-8000-000000000975', 'claimability-race-dispatcher-975@example.test', '{"name":"Claimability Race Dispatcher","phone":"+63917000975"}');

UPDATE public.profiles
SET role = 'dispatcher', duty_status = NULL
WHERE auth_user_id = '00000000-0000-4000-8000-000000000975';

INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude,
  location_recorded_at, location_source, is_claimable
)
SELECT p.id, 'medical', 'Disposable claimability-cancellation race',
       11.2445, 125.0032, now(), 'manual', false
FROM public.profiles p
WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000974';

SELECT id
FROM public.incidents
WHERE description = 'Disposable claimability-cancellation race';
SQL

incident_id="$(cat "$tmp_dir/incident-id")"
if [[ ! "$incident_id" =~ ^[0-9]+$ ]]; then
  echo "Expected one disposable incident ID; got: $incident_id" >&2
  exit 1
fi

hold_incident_lock() {
  local app_name="$1"
  PGAPPNAME="$app_name" psql -q -v ON_ERROR_STOP=1 >/dev/null <<SQL
SELECT set_config('application_name', '$app_name', false);
BEGIN;
SELECT id FROM public.incidents WHERE id = $incident_id FOR UPDATE;
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
  local lock_pid="$3"
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
            WHERE blocker.pid = $lock_pid
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
  echo "Expected both dispatcher/requester workers in the incident lock queue; observed $observed." >&2
  psql -x -v ON_ERROR_STOP=1 -c "
    SELECT pid, application_name, state, wait_event_type, wait_event,
           pg_blocking_pids(pid) AS blockers, left(query, 200) AS query
    FROM pg_stat_activity
    WHERE application_name LIKE 'tacresq-claimability-%'
    ORDER BY application_name
  " >&2
  return 1
}

toggle_claimability() {
  local app_name="tacresq-claimability-dispatcher"
  PGAPPNAME="$app_name" psql -q -v ON_ERROR_STOP=1 -At >"$tmp_dir/dispatcher.out" 2>"$tmp_dir/dispatcher.err" <<SQL
SELECT set_config('application_name', '$app_name', false);
SELECT set_config('request.jwt.claim.sub', '$dispatcher_uuid', false);
SET ROLE authenticated;
BEGIN;
DO \$claimability_race\$
BEGIN
  PERFORM public.api_set_incident_claimable($incident_id, true);
EXCEPTION WHEN SQLSTATE '22000' THEN
  RAISE NOTICE 'Claimability correctly rejected after terminal transition.';
END;
\$claimability_race\$;
COMMIT;
RESET ROLE;
SQL
}

cancel_request() {
  local app_name="tacresq-claimability-requester"
  PGAPPNAME="$app_name" psql -q -v ON_ERROR_STOP=1 -At >"$tmp_dir/requester.out" 2>"$tmp_dir/requester.err" <<SQL
SELECT set_config('application_name', '$app_name', false);
SELECT set_config('request.jwt.claim.sub', '$requester_uuid', false);
SET ROLE authenticated;
BEGIN;
SELECT public.api_cancel_my_incident($incident_id);
COMMIT;
RESET ROLE;
SQL
}

coordinator_app="tacresq-claimability-coordinator"
dispatcher_app="tacresq-claimability-dispatcher"
requester_app="tacresq-claimability-requester"
hold_incident_lock "$coordinator_app" &
coordinator_shell_pid=$!
if ! coordinator_pid="$(wait_for_coordinator_pid "$coordinator_app")"; then
  wait "$coordinator_shell_pid" || true
  exit 1
fi

toggle_claimability &
dispatcher_pid=$!
cancel_request &
requester_pid=$!

if ! wait_for_two_lock_waiters "$dispatcher_app" "$requester_app" "$coordinator_pid"; then
  psql -q -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend($coordinator_pid)" >/dev/null || true
  wait "$dispatcher_pid" || true
  wait "$requester_pid" || true
  wait "$coordinator_shell_pid" || true
  exit 1
fi

wait "$dispatcher_pid"
wait "$requester_pid"
wait "$coordinator_shell_pid"
coordinator_pid=""

psql -q -v ON_ERROR_STOP=1 -At <<'SQL' > "$tmp_dir/final-state"
SELECT status || ':' || is_claimable::text
FROM public.incidents
WHERE description = 'Disposable claimability-cancellation race';
SQL

final_state="$(cat "$tmp_dir/final-state")"
if [[ "$final_state" != "cancelled:false" ]]; then
  echo "Lifecycle invariant failed: expected cancelled:false, found '$final_state'." >&2
  echo "Dispatcher output:" >&2
  cat "$tmp_dir/dispatcher.out" "$tmp_dir/dispatcher.err" >&2
  echo "Requester output:" >&2
  cat "$tmp_dir/requester.out" "$tmp_dir/requester.err" >&2
  exit 1
fi

echo "PASS: dispatcher and requester sessions were both observed waiting on the same incident lock before release."
echo "PASS: dispatcher claimability versus requester cancellation preserves terminal state cancelled:false."
