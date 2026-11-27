from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (ROOT / "supabase/migrations/20261122120710_admin_investigation_events.sql").read_text()
action_migration = (ROOT / "supabase/migrations/20261123045611_20261122230000_app_action_audit_coverage.sql").read_text()
operation_migration = (ROOT / "supabase/migrations/20261127030000_admin_investigation_api_operation_correlation.sql").read_text()
operation_session_migration = (ROOT / "supabase/migrations/20261127050000_add_auth_session_ids_to_audit_events.sql").read_text()
event_session_migration = (ROOT / "supabase/migrations/20261127060000_admin_event_session_context.sql").read_text()
session_feed_migration = (ROOT / "supabase/migrations/20261127070000_admin_session_activity_feed.sql").read_text()
trace_migration = (ROOT / "supabase/migrations/20261127080000_admin_trace_context_correlation.sql").read_text()
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

# Laravel API telemetry joins the Admin feed through both independently
# generated request identifiers; expose only bounded operational metadata.
for marker in (
    "CREATE OR REPLACE FUNCTION public.api_admin_list_investigation_events",
    "'operations'",
    "'device_security_events'",
    "FROM public.device_security_events AS d",
    "'auth_session_id', d.auth_session_id",
    "'severity', d.severity",
    "'api_operation_logs'",
    "FROM public.api_operation_logs AS o",
    "'correlation_id', o.correlation_id",
    "'auth_session_id', o.auth_session_id",
    "'provider_request_id', o.provider_request_id",
    "'provider', o.provider",
    "'operation', o.operation",
    "'outcome', o.outcome",
    "'http_status', o.http_status",
    "'duration_ms', o.duration_ms",
    "'error_code', o.error_code",
    "ON actor.auth_user_id = o.actor_auth_user_id",
    "PERFORM private.tacresq_require_admin_mfa()",
    "SECURITY DEFINER",
    "REVOKE ALL ON FUNCTION public.api_admin_list_investigation_events",
    "GRANT EXECUTE ON FUNCTION public.api_admin_list_investigation_events",
):
    assert marker in operation_migration, f"Missing API operation correlation contract: {marker}"
# The writer's verified Auth session field must exist in the database,
# remain historical after sign-out (no FK to auth.sessions), and be indexed.
for marker in (
    "ADD COLUMN IF NOT EXISTS auth_session_id uuid",
    "ON public.api_operation_logs (auth_session_id, created_at DESC, id DESC)",
    "WHERE auth_session_id IS NOT NULL",
    "retained after session revocation",
):
    assert marker in operation_session_migration, f"Missing API session correlation migration contract: {marker}"
assert "REFERENCES auth.sessions" not in operation_session_migration

# Trusted event-table writers stamp signed Auth session claims without an FK
# to the live Auth session table.
for marker in (
    "private.tacresq_current_auth_session_id()",
    "private.tacresq_stamp_auth_session_id()",
    "BEFORE INSERT ON public.%I",
    "ADD COLUMN IF NOT EXISTS auth_session_id uuid",
    "ON public.admin_audit_logs (auth_session_id)",
    "ON public.account_lifecycle_audit_logs (auth_session_id)",
):
    assert marker in event_session_migration, f"Missing event session-context contract: {marker}"
assert "REFERENCES auth.sessions" not in event_session_migration

# The unified feed projects session IDs from every trusted source and includes
# account lifecycle transitions so a session search can find them.
for marker in (
    "'account_lifecycle_audit_logs'",
    "FROM public.account_lifecycle_audit_logs AS l",
    "'auth_session_id', a.auth_session_id",
    "'auth_session_id', l.auth_session_id",
    "'auth_session_id', s.auth_session_id",
    "'auth_session_id', c.auth_session_id",
    "'auth_session_id', r.auth_session_id",
    "'auth_session_id', e.auth_session_id",
    "PERFORM private.tacresq_require_admin_mfa()",
    "GRANT EXECUTE ON FUNCTION public.api_admin_list_investigation_events",
):
    assert marker in session_feed_migration, f"Missing session-aware feed contract: {marker}"
for forbidden in (
    "INSERT INTO public.admin_audit_logs",
    "INSERT INTO public.account_lifecycle_audit_logs",
    "INSERT INTO public.incident_status_logs",
    "INSERT INTO public.incident_assignment_events",
):
    assert forbidden not in session_feed_migration, f"Feed migration must remain read-only: {forbidden}"

# W3C trace context is parsed as caller-influenced telemetry only, stored
# on trusted event writes, and exposed as a separate Admin technical reference.
for marker in (
    "private.tacresq_current_trace_id()",
    "v_traceparent !~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}
# share Auth session IDs with API-operation telemetry for cross-source search.
device_projection = operation_migration.split("FROM public.device_security_events AS d", 1)[0].split("UNION ALL")[-1]
assert "jsonb_build_object(" in device_projection
assert "'auth_session_id', d.auth_session_id" in device_projection
assert "'severity', d.severity" in device_projection
assert "d.details" not in device_projection
assert "'device_security_events'" in operation_migration

for forbidden in ("request_body", "response_body", "access_token", "refresh_token", "user_agent", "ip_address"):
    assert forbidden not in operation_migration.lower(), f"Sensitive API telemetry field exposed: {forbidden}"
assert "api_operation_logs" in client
assert 'new Set(["administrative", "authentication", "operations", "incident", "assignment"])' in client
assert 'api_operation_logs: "Laravel API operations"' in page
assert 'account_lifecycle_audit_logs: "Account lifecycle"' in page
assert 'device_security_events: "Device security events"' in page
assert 'operations: "API operations"' in page
assert '<option value="operations">API operations</option>' in page

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
    "Laravel API operations",
    "No trusted server-side writer currently creates these rows",
    "an empty result is not evidence that devices are safe",
    "This best-effort source does not cover direct Android/browser Supabase or Storage calls.",
    "FCM delivery attempts",
    "Provider acceptance does not prove device receipt or user acknowledgement.",
    "Retention and integrity policy",
    "cross-source integrity/restore standard is not established",
):
    assert marker in page, f"Missing Admin investigation UI contract: {marker}"

# Audit metadata is database-backed JSON and must not cause unbounded UI work.
for marker in (
    "MAX_AUDIT_DETAIL_DEPTH = 3",
    "MAX_AUDIT_DETAIL_ITEMS = 20",
    "MAX_AUDIT_DETAIL_TEXT = 500",
    "depth >= MAX_AUDIT_DETAIL_DEPTH",
    ".slice(0, MAX_AUDIT_DETAIL_ITEMS)",
    "boundedAuditText(value)",
    "Additional nested details omitted",
):
    assert marker in page, f"Missing bounded audit-detail rendering guard: {marker}"

print("PASS: Admin investigation source, authorization, privacy, and UI contracts are present.")
",
    "v_trace_id = repeat('0', 32)",
    "v_span_id = repeat('0', 16)",
    "NEW.trace_id := private.tacresq_current_trace_id()",
    "ADD COLUMN IF NOT EXISTS trace_id text",
    "CREATE TRIGGER tacresq_stamp_trace_id",
    "trace_id ~ ''^[0-9a-f]{32}
# share Auth session IDs with API-operation telemetry for cross-source search.
device_projection = operation_migration.split("FROM public.device_security_events AS d", 1)[0].split("UNION ALL")[-1]
assert "jsonb_build_object(" in device_projection
assert "'auth_session_id', d.auth_session_id" in device_projection
assert "'severity', d.severity" in device_projection
assert "d.details" not in device_projection
assert "'device_security_events'" in operation_migration

for forbidden in ("request_body", "response_body", "access_token", "refresh_token", "user_agent", "ip_address"):
    assert forbidden not in operation_migration.lower(), f"Sensitive API telemetry field exposed: {forbidden}"
assert "api_operation_logs" in client
assert 'new Set(["administrative", "authentication", "operations", "incident", "assignment"])' in client
assert 'api_operation_logs: "Laravel API operations"' in page
assert 'account_lifecycle_audit_logs: "Account lifecycle"' in page
assert 'device_security_events: "Device security events"' in page
assert 'operations: "API operations"' in page
assert '<option value="operations">API operations</option>' in page

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
    "Laravel API operations",
    "No trusted server-side writer currently creates these rows",
    "an empty result is not evidence that devices are safe",
    "This best-effort source does not cover direct Android/browser Supabase or Storage calls.",
    "FCM delivery attempts",
    "Provider acceptance does not prove device receipt or user acknowledgement.",
    "Retention and integrity policy",
    "cross-source integrity/restore standard is not established",
):
    assert marker in page, f"Missing Admin investigation UI contract: {marker}"

# Audit metadata is database-backed JSON and must not cause unbounded UI work.
for marker in (
    "MAX_AUDIT_DETAIL_DEPTH = 3",
    "MAX_AUDIT_DETAIL_ITEMS = 20",
    "MAX_AUDIT_DETAIL_TEXT = 500",
    "depth >= MAX_AUDIT_DETAIL_DEPTH",
    ".slice(0, MAX_AUDIT_DETAIL_ITEMS)",
    "boundedAuditText(value)",
    "Additional nested details omitted",
):
    assert marker in page, f"Missing bounded audit-detail rendering guard: {marker}"

print("PASS: Admin investigation source, authorization, privacy, and UI contracts are present.")
'",
    "'trace_id', a.trace_id",
    "'trace_id', l.trace_id",
    "'trace_id', s.trace_id",
    "'trace_id', c.trace_id",
    "'trace_id', r.trace_id",
    "'trace_id', e.trace_id",
    "'trace_id', d.trace_id",
    "'trace_id', o.trace_id",
    "e.details ->> 'trace_id' = v_query",
    "REVOKE ALL ON FUNCTION private.tacresq_current_trace_id()",
):
    assert marker in trace_migration, f"Missing W3C trace context migration contract: {marker}"
assert "REFERENCES auth.sessions" not in trace_migration
assert "auth.uid()" not in trace_migration, "Trace IDs must not become an identity or authorization input."
for marker in (
    "trace IDs, correlation IDs, Auth session IDs, and provider request IDs remain distinct",
    'placeholder="Search people, incidents, events, trace IDs, correlation IDs or session IDs"',
    'getTechnicalReference(event, "trace_id")',
    "W3C trace ID",
):
    assert marker in page, f"Missing W3C trace Admin UI contract: {marker}"

trace_context = (ROOT / "backend/src/app/TraceContext.php").read_text()
bridge = (ROOT / "backend/src/app/SupabaseBridge.php").read_text()
audit_logger = (ROOT / "backend/src/app/ApiOperationAuditLogger.php").read_text()
android_trace = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/W3CTraceContext.kt").read_text()
android_api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
android_trace_test = (ROOT / "app/src/test/java/com/tacresq/app/data/remote/W3CTraceContextTest.kt").read_text()
for marker in (
    "request()->header('traceparent')",
    "'\\\\A00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})\\\\z/iD'",
    "str_repeat('0', 32)",
    "str_repeat('0', 16)",
):
    assert marker in trace_context, f"Missing Laravel trace-context validation: {marker}"
assert "TraceContext::currentTraceparent()" in bridge
assert "TraceContext::currentTraceparent()" in audit_logger
assert "$headers['traceparent'] = $traceparent" in bridge
assert "$additionalHeaders['traceparent'] = $traceparent" in audit_logger

# Browser and Android shared transports emit fresh W3C context per request.
for marker in (
    "function createTraceparent(): string | null",
    "globalThis.crypto.getRandomValues(bytes)",
    'headers.set("traceparent", traceparent)',
    "global: {",
    "fetch: traceAwareFetch",
    "Tracing is optional; an unavailable random source must not block an API call.",
):
    assert marker in client, f"Missing browser trace propagation: {marker}"
for marker in (
    "object W3CTraceContext",
    "SecureRandom()",
    '"00-",
    "class TraceparentInterceptor",
    'builder.header("traceparent", W3CTraceContext.newTraceparent())',
    "Optional telemetry must never prevent SOS or another API request.",
):
    assert marker in android_trace, f"Missing Android trace generation/interceptor: {marker}"
assert android_api.count("addInterceptor(TraceparentInterceptor())") == 2
assert "everyHttpRequestGetsFreshTraceContext" in android_trace_test
assert "generatedTraceparentUsesVersionZeroAndNonZeroIdentifiers" in android_trace_test

# Device security rows are projected without arbitrary JSON details and
# share Auth session IDs with API-operation telemetry for cross-source search.
device_projection = operation_migration.split("FROM public.device_security_events AS d", 1)[0].split("UNION ALL")[-1]
assert "jsonb_build_object(" in device_projection
assert "'auth_session_id', d.auth_session_id" in device_projection
assert "'severity', d.severity" in device_projection
assert "d.details" not in device_projection
assert "'device_security_events'" in operation_migration

for forbidden in ("request_body", "response_body", "access_token", "refresh_token", "user_agent", "ip_address"):
    assert forbidden not in operation_migration.lower(), f"Sensitive API telemetry field exposed: {forbidden}"
assert "api_operation_logs" in client
assert 'new Set(["administrative", "authentication", "operations", "incident", "assignment"])' in client
assert 'api_operation_logs: "Laravel API operations"' in page
assert 'account_lifecycle_audit_logs: "Account lifecycle"' in page
assert 'device_security_events: "Device security events"' in page
assert 'operations: "API operations"' in page
assert '<option value="operations">API operations</option>' in page

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
    "Laravel API operations",
    "No trusted server-side writer currently creates these rows",
    "an empty result is not evidence that devices are safe",
    "This best-effort source does not cover direct Android/browser Supabase or Storage calls.",
    "FCM delivery attempts",
    "Provider acceptance does not prove device receipt or user acknowledgement.",
    "Retention and integrity policy",
    "cross-source integrity/restore standard is not established",
):
    assert marker in page, f"Missing Admin investigation UI contract: {marker}"

# Audit metadata is database-backed JSON and must not cause unbounded UI work.
for marker in (
    "MAX_AUDIT_DETAIL_DEPTH = 3",
    "MAX_AUDIT_DETAIL_ITEMS = 20",
    "MAX_AUDIT_DETAIL_TEXT = 500",
    "depth >= MAX_AUDIT_DETAIL_DEPTH",
    ".slice(0, MAX_AUDIT_DETAIL_ITEMS)",
    "boundedAuditText(value)",
    "Additional nested details omitted",
):
    assert marker in page, f"Missing bounded audit-detail rendering guard: {marker}"

print("PASS: Admin investigation source, authorization, privacy, and UI contracts are present.")
