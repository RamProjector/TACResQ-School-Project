# PR #342 — authenticated Storage HTTP acceptance

The guarded harness `tools/test_incident_chat_storage_http_acceptance.py` exercises the actual Supabase HTTP path used by Android, rather than only a PostgreSQL mock of `storage.objects`.

## Safety boundaries

- Restricted in code to the dedicated non-production project ref `kyomsyprjdseaghdxhdr` and its default Supabase URL.
- Requires an explicit `TACRESQ_ALLOW_REMOTE_STORAGE_TEST=1` opt-in.
- Uses only a publishable/anon key and a normal authenticated test-user access token. It does not accept or need a service-role key.
- Requires a pre-created synthetic incident to which the test user has legitimate incident-chat access.
- Uploads a valid 1x1 synthetic PNG, finalizes one image message, retries finalization, and downloads the object using the same authenticated Storage endpoint as Android.
- Optionally verifies that a second unrelated authenticated account cannot download the object.
- Intentionally does not delete the test message or Storage object. Run only in a dedicated test incident and account; do not use real incident data.

## Prerequisites

1. Reconcile the test project's schema/migration-history drift through the reviewed, approved forward-only migration process. The read-only schema contract must pass first.
2. Ensure the test project has the PR #342 migration and private bucket/policies applied through the normal migration process.
3. Confirm the test account is a requester, assigned responder/driver, or dispatcher with legitimate access to the synthetic incident. Do not grant broad access just to make the test pass.
4. Keep `reservation_enforcement_enabled` in the intended staged rollout state; this harness tests the reservation-capable Android flow, so strict enforcement must be enabled only after the separate rollout decision and legacy-client compatibility checks.
5. Use a dedicated synthetic incident with no real people, messages, coordinates, or attachments.

## Run

Set values locally in the shell; never commit them or paste tokens into issues/chat:

```sh
export TACRESQ_ALLOW_REMOTE_STORAGE_TEST=1
export TACRESQ_STORAGE_TEST_PROJECT_REF=kyomsyprjdseaghdxhdr
export SUPABASE_URL=https://kyomsyprjdseaghdxhdr.supabase.co
export SUPABASE_ANON_KEY='your publishable-or-anon-key'
export SUPABASE_ACCESS_TOKEN='short-lived access token for the synthetic test user'
export TACRESQ_TEST_INCIDENT_ID='the synthetic incident id'
# Optional: a second authenticated account that is not a participant
export TACRESQ_UNRELATED_ACCESS_TOKEN='short-lived unrelated-account access token'

python3 tools/test_incident_chat_storage_http_acceptance.py
```

The script prints pass/fail checkpoints and HTTP status only. It does not print access tokens or response bodies. It exits nonzero on the first failed required check.

## What this proves — and does not prove

A successful run proves, for the tested project release and account/incident combination:

- the authenticated binary Storage POST succeeds with the real client headers and MIME type;
- the reservation and finalization RPCs work over PostgREST;
- retrying finalization returns the original message;
- the authenticated Storage GET returns the exact uploaded bytes;
- optionally, a nonparticipant cannot download the object.

It does **not** prove every role/assignment transition, expired-reservation rejection, size-mismatch rejection, unsupported MIME rejection, duplicate-object POST behavior, client UPDATE/DELETE denial, Android Photo Picker behavior, or physical-device network recovery. Those require separate acceptance cases. SQL migration replay remains useful for database-policy regression coverage but is not a substitute for this HTTP test.

## Current execution status

The harness is committed as test infrastructure only. It has not been run against the remote project. The known schema/history discrepancy must be reconciled first, and an authorized operator must provision the synthetic test account/incident and supply credentials locally. No remote writes were made by adding this harness.
