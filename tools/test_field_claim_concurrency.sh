#!/usr/bin/env bash
# Real two-session PostgreSQL race test for the field-claim profile lock.
# Runs only after the disposable migration replay has applied the PR migration.
set -euo pipefail

: "${PGHOST:?PGHOST is required}"
: "${PGPORT:?PGPORT is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${PGDATABASE:?PGDATABASE is required}"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

requester_uuid="00000000-0000-4000-8000-000000000901"
responder_uuid="00000000-0000-4000-8000-000000000902"

psql -q -v ON_ERROR_STOP=1 -At <<'SQL' > "$tmp_dir/ids"
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES
  ('00000000-0000-4000-8000-000000000901', 'claim-race-requester@example.test', '{"name":"Claim Race Requester","phone":"+63917000901"}'),
  ('00000000-0000-4000-8000-000000000902', 'claim-race-responder@example.test', '{"name":"Claim Race Responder","phone":"+63917000902"}'),
  ('00000000-0000-4000-8000-000000000903', 'claim-race-requester-b@example.test', '{"name":"Claim Race Requester B","phone":"+63917000903"}');

UPDATE public.profiles
SET role = 'responder', duty_status = 'available'
WHERE auth_user_id = '00000000-0000-4000-8000-000000000902';

INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude,
  location_recorded_at, location_source, is_claimable
)
SELECT p.id, 'medical', 'Disposable concurrent field-claim race A',
       11.2443, 125.0030, now(), 'manual', true
FROM public.profiles p
WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000901';

INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude,
  location_recorded_at, location_source, is_claimable
)
SELECT p.id, 'medical', 'Disposable concurrent field-claim race B',
       11.2444, 125.0031, now(), 'manual', true
FROM public.profiles p
WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000903';

SELECT id
FROM public.incidents
WHERE description IN (
  'Disposable concurrent field-claim race A',
  'Disposable concurrent field-claim race B'
)
ORDER BY description;
SQL

mapfile -t incident_ids < "$tmp_dir/ids"
if [[ "${#incident_ids[@]}" -ne 2 ]]; then
  echo "Expected two disposable incidents; found ${#incident_ids[@]}" >&2
  exit 1
fi

run_claim() {
  local incident_id="$1"
  local output_file="$2"
  psql -v ON_ERROR_STOP=1 -At >"$output_file" <<SQL
SELECT set_config('request.jwt.claim.sub', '$responder_uuid', false);
SET ROLE authenticated;
BEGIN;
SELECT public.api_claim_incident($incident_id);
-- Hold the profile lock long enough for the competing session to contend.
SELECT pg_sleep(1);
COMMIT;
RESET ROLE;
SQL
}

run_claim "${incident_ids[0]}" "$tmp_dir/claim-a.out" &
pid_a=$!
run_claim "${incident_ids[1]}" "$tmp_dir/claim-b.out" &
pid_b=$!

wait "$pid_a"
wait "$pid_b"

combined="$(cat "$tmp_dir/claim-a.out" "$tmp_dir/claim-b.out")"
claimed_count="$(grep -c '"status": "claimed"' <<<"$combined" || true)"
busy_count="$(grep -c '"code": "UNIT_BUSY"' <<<"$combined" || true)"

if [[ "$claimed_count" -ne 1 || "$busy_count" -ne 1 ]]; then
  echo "Expected exactly one claimed and one UNIT_BUSY result." >&2
  echo "$combined" >&2
  exit 1
fi

psql -v ON_ERROR_STOP=1 -At <<'SQL' > "$tmp_dir/final-counts"
SELECT count(*)
FROM public.incident_assignments a
JOIN public.profiles p ON p.id = a.user_id
WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000902'
  AND a.incident_id IN (
    SELECT id FROM public.incidents
    WHERE description IN (
      'Disposable concurrent field-claim race A',
      'Disposable concurrent field-claim race B'
    )
  );
SELECT count(*)
FROM public.incidents
WHERE description IN (
    'Disposable concurrent field-claim race A',
    'Disposable concurrent field-claim race B'
  )
  AND status = 'assigned';
SQL

mapfile -t final_counts < "$tmp_dir/final-counts"
if [[ "${final_counts[0]}" -ne 1 || "${final_counts[1]}" -ne 1 ]]; then
  echo "Invariant failure: expected one active membership and one assigned incident." >&2
  cat "$tmp_dir/final-counts" >&2
  exit 1
fi

echo "PASS: two independent authenticated PostgreSQL sessions raced to claim different incidents; one claim succeeded and the other was rejected as UNIT_BUSY."
echo "PASS: final state contains exactly one membership and one assigned incident for the responder."
