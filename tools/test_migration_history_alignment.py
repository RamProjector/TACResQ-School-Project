#!/usr/bin/env python3
"""Keep repository migration filenames aligned with the applied TACResQ project history."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase" / "migrations"

EXPECTED = {
    "20261015080806_tacresq_core_schema.sql",
    "20261015081107_harden_rpc_exposure.sql",
    "20261015081241_rls_and_index_tuning.sql",
    "20261015092225_location_and_push.sql",
    "20261019214218_incident_one_time_location.sql",
    "20261020062532_responder_location_tracking.sql",
    "20261021103127_protect_incident_report_fields.sql",
    "20261021103137_restrict_location_rpc_search_path.sql",
    "20261021103144_restrict_trigger_search_paths.sql",
    "20261021103158_restrict_actor_helper_search_path.sql",
    "20261021103206_restrict_anonymous_rpc_execution.sql",
    "20261021103213_isolate_location_writer.sql",
    "20261021113046_revoke_anon_table_privileges.sql",
    "20261021155540_manual_incident_location_source.sql",
    "20261022040223_fix_incident_status_audit_trigger.sql",
    "20261023031801_durable_sos_idempotency.sql",
    "20261023031810_requester_single_active_and_cancel.sql",
}
actual = {path.name for path in MIGRATIONS.glob("*.sql")}
assert actual == EXPECTED, (
    f"Supabase migration history drift. Missing={sorted(EXPECTED - actual)}; "
    f"unexpected={sorted(actual - EXPECTED)}"
)

core = (MIGRATIONS / "20261015080806_tacresq_core_schema.sql").read_text()
for required in (
    "create or replace function public.rls_auto_enable()",
    "create event trigger ensure_rls",
    "set search_path = pg_catalog",
):
    assert required in core, f"Missing Supabase RLS baseline object: {required}"

push = (MIGRATIONS / "20261015092225_location_and_push.sql").read_text()
for required in (
    "create table if not exists public.fcm_tokens",
    "fcm_tokens_token_check",
    "fcm_tokens_profile_idx",
    "create policy fcm_tokens_self",
    "create policy fcm_tokens_insert_self",
    "create policy fcm_tokens_delete_self",
    "create or replace function public.api_register_fcm_token",
    "create or replace function public.api_unregister_fcm_token",
    "grant usage, select on sequence public.fcm_tokens_id_seq to authenticated",
):
    assert required in push, f"Missing baseline push/location object: {required}"

audit_fix = (MIGRATIONS / "20261022040223_fix_incident_status_audit_trigger.sql").read_text()
assert "AFTER INSERT OR UPDATE ON public.incidents" in audit_fix
assert "BEFORE UPDATE ON public.incidents" in audit_fix
assert "INSERT INTO public.incident_status_logs(incident_id, status, changed_by)" in audit_fix

print(f"PASS: {len(EXPECTED)} local Supabase migrations match applied version history.")
print("PASS: missing location_and_push migration recreates FCM storage, RLS, and RPCs.")
print("PASS: incident status logging runs after insert to satisfy the incident foreign key.")
