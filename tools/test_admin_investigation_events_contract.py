from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "supabase/migrations/20261122160000_admin_investigation_events.sql").read_text()
action_migration = (ROOT / "supabase/migrations/20261122230000_app_action_audit_coverage.sql").read_text()
page = (ROOT / "backend/web/src/pages/AuditLogsPage.tsx").read_text()
client = (ROOT / "backend/web/src/lib/supabase.ts").read_text()

required_sql = (
    "CREATE INDEX IF NOT EXISTS incident_status_logs_created_idx",
    "CREATE INDEX IF NOT EXISTS incident_assignment_events_created_idx",
    "CREATE INDEX IF NOT EXISTS incident_claimability_logs_created_idx",
    "CREATE INDEX IF NOT EXISTS incident_request_edit_logs_created_idx",
    "CREATE POLICY incident_assignment_events_admin_require_aal2",
    "AS RESTRICTIVE",
    "private.tacresq_actor_role()",
    "auth.jwt() ->> 'aal'",
    "method.value ->> 'method' = 'totp'",
    "CREATE OR REPLACE FUNCTION public.api_admin_list_investigation_events",
    "PERFORM private.tacresq_require_admin_mfa()",
    "SECURITY DEFINER",
    "SET search_path = pg_catalog",
    "FROM public.admin_audit_logs AS a",
    "FROM public.incident_status_logs AS s",
    "FROM public.incident_claimability_logs AS c",
    "FROM public.incident_request_edit_logs AS r",
    "FROM public.incident_assignment_events AS e",
    "FROM auth.audit_log_entries AS a",
    "p_incident_id",
    "p_source",
    "LIMIT p_limit OFFSET p_offset",
    "REVOKE ALL ON FUNCTION public.api_admin_list_investigation_events",
    "GRANT EXECUTE ON FUNCTION public.api_admin_list_investigation_events",
    "incident_claimability_logs",
    "incident_request_edit_logs",
    "changed_fields",
)
for marker in required_sql:
    assert marker in migration, f"Missing investigation SQL contract: {marker}"

# New committed app-action sources must be server-written and metadata-only.
for marker in (
    "CREATE TABLE public.app_action_audit_logs",
    "correlation_id uuid NOT NULL DEFAULT gen_random_uuid()",
    "actor_profile_id bigint",
    "actor_role text",
    "outcome text NOT NULL DEFAULT 'success'",
    "metadata jsonb NOT NULL DEFAULT '{}'::jsonb",
    "octet_length(metadata::text) <= 1024",
    "REVOKE ALL PRIVILEGES ON TABLE public.app_action_audit_logs",
    "private.tacresq_record_app_action_audit()",
    "tacresq_app_action_audit_immutable",
    "BEFORE UPDATE OR DELETE ON public.app_action_audit_logs",
    "AFTER INSERT ON public.incident_messages",
    "AFTER UPDATE OF read_at ON public.user_notifications",
    "AFTER UPDATE OF duty_status ON public.profiles",
    "'incident_message_sent'",
    "'notification_marked_read'",
    "'duty_status_changed'",
    "FROM public.app_action_audit_logs AS a",
    "'correlation_id', a.correlation_id",
    "'actor_role', a.actor_role",
    "'target_type', a.target_type",
    "'target_id', a.target_id",
    "'app_action_audit_logs'",
    "PERFORM private.tacresq_require_admin_mfa()",
):
    assert marker in action_migration, f"Missing app-action audit contract: {marker}"

audit_trigger = action_migration.split(
    "CREATE OR REPLACE FUNCTION private.tacresq_record_app_action_audit()", 1
)[1].split("$function$;", 1)[0]
for forbidden in (
    "NEW.message",
    "NEW.latitude",
    "NEW.longitude",
    "jsonb_build_object('attachment_path'",
    "NEW.token",
    "access_token",
    "refresh_token",
):
    assert forbidden not in audit_trigger, (
        f"Sensitive content must not be copied into app-action audit: {forbidden}"
    )

assert "app_action_audit_logs" in client
assert 'app_action_audit_logs: "App actions"' in page

# Auth rows are projected through allowlisted fields only. Do not expose raw
# provider payloads, IP addresses, or user-agent metadata in the Admin feed.
auth_projection = migration.split("FROM auth.audit_log_entries AS a", 1)[0].split("UNION ALL")[-1]
assert "payload ->> 'action'" in auth_projection
assert "payload ->> 'user_id'" in auth_projection
assert "'ip_address'" not in auth_projection
assert "user_agent" not in auth_projection
assert "to_jsonb(a)" not in auth_projection

# Requester edit history contains location and narrative snapshots. The
# investigation feed may reveal changed-field names, never snapshot values.
assert "'before_values'" not in migration
assert "'after_values'" not in migration
assert "changed_fields" in migration
assert "r.before_values -> field.key IS DISTINCT FROM r.after_values -> field.key" in migration

# The feed reads existing event sources; it must not create a duplicate ledger
# or grant browser clients direct access to protected event tables.
for forbidden in (
    "INSERT INTO public.admin_audit_logs",
    "INSERT INTO public.incident_status_logs",
    "INSERT INTO public.incident_assignment_events",
    "GRANT SELECT ON TABLE public.incident_claimability_logs",
    "GRANT SELECT ON TABLE public.api_operation_logs",
):
    assert forbidden not in migration, f"Unexpected write or broad grant: {forbidden}"

for marker in (
    "api_admin_list_investigation_events",
    "AdminInvestigationSource",
    "p_source: source",
    "administrative",
    "authentication",
    "incident",
    "assignment",
    "incident_id",
    "occurred_at",
    "source",
):
    assert marker in client, f"Missing client event contract: {marker}"

for marker in (
    "getAdminInvestigationEventsPage",
    "Authentication",
    "Incident",
    "Assignment",
    "Source",
    "sourceLabels",
    "All sources",
    "Coverage boundaries",
    "Feed reader: integrated.",
    "Capture is conditional on the Supabase project’s optional Auth Audit Logs Postgres storage setting",
    "A zero-row result means no matching records were returned, not that no activity occurred.",
    "database event writers: integrated",
    "date boundaries and displayed times use Asia/Manila",
):
    assert marker in page, f"Missing Admin investigation UI contract: {marker}"

print("PASS: Admin investigation source, authorization, privacy, and UI contracts are present.")
