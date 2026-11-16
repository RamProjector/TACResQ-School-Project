#!/usr/bin/env bash
# Real two-session race between dispatcher claimability and requester cancellation.
# Runs only on the disposable PostgreSQL database after migration replay.
set -euo pipefail

: "${PGHOST:?PGHOST is required}"
: "${PGPORT:?PGPORT is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${PGDATABASE:?PGDATABASE is required}"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

requester_uuid="00000000-0000-4000-8000-000000000911"
dispatcher_uuid="00000000-0000-4000-8000-000000000912"

psql -q -v ON_ERROR_STOP=1 -At <<'SQL' > "$tmp_dir/incident-id"
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES
  ('00000000-0000-4000-8000-000000000911', 'claimability-race-requester@example.test', '{"name":"Claimability Race Requester","phone":"+63917000911"}'),
  ('00000000-0000-4000-8000-000000000912', 'claimability-race-dispatcher@example.test', '{"name":"Claimability Race Dispatcher","phone":"+63917000912"}');

UPDATE public.profiles
SET role = 'dispatcher', duty_status = NULL
WHERE auth_user_id = '00000000-0000-4000-8000-000000000912';

INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude,
  location_recorded_at, location_source, is_claimable
)
SELECT p.id, 'medical', 'Disposable claimability-cancellation race',
       11.2445, 125.0032, now(), 'manual', false
FROM public.profiles p
WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000911';

SELECT id
FROM public.incidents
WHERE description = 'Disposable claimability-cancellation race';
SQL

incident_id="$(cat "$tmp_dir/incident-id")"
if [[ ! "$incident_id" =~ ^[0-9]+$ ]]; then
  echo "Expected one disposable incident ID; got: $incident_id" >&2
  exit 1
fi

toggle_claimability() {
  psql -q -v ON_ERROR_STOP=1 -At >"$tmp_dir/dispatcher.out" <<SQL
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
-- Keep a successful toggle's incident lock held while cancellation competes.
SELECT pg_sleep(1);
COMMIT;
RESET ROLE;
SQL
}

cancel_request() {
  psql -q -v ON_ERROR_STOP=1 -At >"$tmp_dir/requester.out" <<SQL
SELECT set_config('request.jwt.claim.sub', '$requester_uuid', false);
SET ROLE authenticated;
BEGIN;
SELECT public.api_cancel_my_incident($incident_id);
SELECT pg_sleep(1);
COMMIT;
RESET ROLE;
SQL
}

toggle_claimability &
dispatcher_pid=$!
cancel_request &
requester_pid=$!

wait "$dispatcher_pid"
wait "$requester_pid"

psql -q -v ON_ERROR_STOP=1 -At <<'SQL' > "$tmp_dir/final-state"
SELECT status || ':' || is_claimable::text
FROM public.incidents
WHERE description = 'Disposable claimability-cancellation race';
SQL

final_state="$(cat "$tmp_dir/final-state")"
if [[ "$final_state" != "cancelled:false" ]]; then
  echo "Lifecycle invariant failed: expected cancelled:false, found '$final_state'." >&2
  echo "Dispatcher output:" >&2
  cat "$tmp_dir/dispatcher.out" >&2
  echo "Requester output:" >&2
  cat "$tmp_dir/requester.out" >&2
  exit 1
fi

echo "PASS: independent authenticated dispatcher/requester sessions raced claimability against cancellation."
echo "PASS: terminal incident state is cancelled and cannot remain claimable."
