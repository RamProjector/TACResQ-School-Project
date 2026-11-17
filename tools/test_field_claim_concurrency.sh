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
responder_b_uuid="00000000-0000-4000-8000-000000000911"
responder_c_uuid="00000000-0000-4000-8000-000000000912"

psql -q -v ON_ERROR_STOP=1 -At <<'SQL' > "$tmp_dir/ids"
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES
  ('00000000-0000-4000-8000-000000000901', 'claim-race-requester@example.test', '{"name":"Claim Race Requester","phone":"+63917000901"}'),
  ('00000000-0000-4000-8000-000000000902', 'claim-race-responder@example.test', '{"name":"Claim Race Responder","phone":"+63917000902"}'),
  ('00000000-0000-4000-8000-000000000903', 'claim-race-requester-b@example.test', '{"name":"Claim Race Requester B","phone":"+63917000903"}'),
  ('00000000-0000-4000-8000-000000000910', 'claim-race-requester-c@example.test', '{"name":"Claim Race Requester C","phone":"+63917000910"}'),
  ('00000000-0000-4000-8000-000000000911', 'claim-race-responder-b@example.test', '{"name":"Claim Race Responder B","phone":"+63917000911"}'),
  ('00000000-0000-4000-8000-000000000912', 'claim-race-responder-c@example.test', '{"name":"Claim Race Responder C","phone":"+63917000912"}');

UPDATE public.profiles
SET role = 'responder', duty_status = 'available'
WHERE auth_user_id IN (
  '00000000-0000-4000-8000-000000000902',
  '00000000-0000-4000-8000-000000000911',
  '00000000-0000-4000-8000-000000000912'
);

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

INSERT INTO public.incidents (
  requester_id, type, description, latitude, longitude,
  location_recorded_at, location_source, is_claimable
)
SELECT p.id, 'medical', 'Disposable same-incident multi-unit claim',
       11.2445, 125.0032, now(), 'manual', true
FROM public.profiles p
WHERE p.auth_user_id = '00000000-0000-4000-8000-000000000910';

SELECT id
FROM public.incidents
WHERE description IN (
  'Disposable concurrent field-claim race A',
  'Disposable concurrent field-claim race B',
  'Disposable same-incident multi-unit claim'
)
ORDER BY description;
SQL

mapfile -t incident_ids < "$tmp_dir/ids"
if [[ "${#incident_ids[@]}" -ne 3 ]]; then
  echo "Expected three disposable incidents; found ${#incident_ids[@]}" >&2
  exit 1
fi

run_claim_as() {
  local actor_uuid="$1"
  local incident_id="$2"
  local output_file="$3"
  psql -v ON_ERROR_STOP=1 -At >"$output_file" <<SQL
SELECT set_config('request.jwt.claim.sub', '$actor_uuid', false);
SET ROLE authenticated;
BEGIN;
SELECT public.api_claim_incident($incident_id);
-- Hold the row locks long enough for the competing session to contend.
SELECT pg_sleep(1);
COMMIT;
RESET ROLE;
SQL
}

run_claim() {
  run_claim_as "$responder_uuid" "$1" "$2"
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

# A second concurrency dimension is intentional: multiple available units may
# join the same incident team. The incident lock must serialize, not reject,
# the second valid member after the first advances pending -> assigned.
run_claim_as "$responder_b_uuid" "${incident_ids[2]}" "$tmp_dir/same-incident-b.out" &
pid_b=$!
run_claim_as "$responder_c_uuid" "${incident_ids[2]}" "$tmp_dir/same-incident-c.out" &
pid_c=$!
wait "$pid_b"
wait "$pid_c"

same_incident_output="$(cat "$tmp_dir/same-incident-b.out" "$tmp_dir/same-incident-c.out")"
same_incident_claimed="$(grep -c '"status": "claimed"' <<<"$same_incident_output" || true)"
if [[ "$same_incident_claimed" -ne 2 ]]; then
  echo "Expected two successful field-unit memberships on one incident." >&2
  echo "$same_incident_output" >&2
  exit 1
fi

psql -v ON_ERROR_STOP=1 -At <<'SQL' > "$tmp_dir/same-incident-counts"
SELECT count(*)
FROM public.incident_assignments a
JOIN public.incidents i ON i.id = a.incident_id
WHERE i.description = 'Disposable same-incident multi-unit claim';
SELECT count(*)
FROM public.incidents
WHERE description = 'Disposable same-incident multi-unit claim'
  AND status = 'assigned';
SQL

mapfile -t same_incident_counts < "$tmp_dir/same-incident-counts"
if [[ "${same_incident_counts[0]}" -ne 2 || "${same_incident_counts[1]}" -ne 1 ]]; then
  echo "Invariant failure: expected two team members and one assigned incident." >&2
  cat "$tmp_dir/same-incident-counts" >&2
  exit 1
fi

echo "PASS: two independent authenticated PostgreSQL sessions raced to claim different incidents; one claim succeeded and the other was rejected as UNIT_BUSY."
echo "PASS: final state contains exactly one membership and one assigned incident for the responder."
echo "PASS: two independent responders concurrently joined the same incident; both memberships persisted and the incident advanced only once."
