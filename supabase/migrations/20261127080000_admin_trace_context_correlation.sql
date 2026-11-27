BEGIN;

-- W3C trace context is attribution-only telemetry. It is caller-influenced,
-- never an identity or authorization input. Parse only version 00 and reject
-- zero trace/span IDs. Any missing or malformed header returns NULL.
CREATE OR REPLACE FUNCTION private.tacresq_current_trace_id()
RETURNS text
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_headers jsonb;
  v_traceparent text;
  v_trace_id text;
  v_span_id text;
BEGIN
  v_headers := nullif(current_setting('request.headers', true), '')::jsonb;
  v_traceparent := lower(btrim(coalesce(v_headers ->> 'traceparent', '')));

  IF v_traceparent !~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$' THEN
    RETURN NULL;
  END IF;

  v_trace_id := split_part(v_traceparent, '-', 2);
  v_span_id := split_part(v_traceparent, '-', 3);
  IF v_trace_id = repeat('0', 32) OR v_span_id = repeat('0', 16) THEN
    RETURN NULL;
  END IF;

  RETURN v_trace_id;
EXCEPTION WHEN OTHERS THEN
  -- Telemetry parsing must never fail a business transaction.
  RETURN NULL;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_current_trace_id()
  FROM PUBLIC, anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION private.tacresq_stamp_trace_id()
RETURNS trigger
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
  -- Always derive from the transaction request header; never accept a
  -- caller-supplied trace_id column value as the source of truth.
  NEW.trace_id := private.tacresq_current_trace_id();
  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_stamp_trace_id()
  FROM PUBLIC, anon, authenticated, service_role;

ALTER TABLE public.admin_audit_logs
  ADD COLUMN IF NOT EXISTS trace_id text;
ALTER TABLE public.incident_status_logs
  ADD COLUMN IF NOT EXISTS trace_id text;
ALTER TABLE public.incident_claimability_logs
  ADD COLUMN IF NOT EXISTS trace_id text;
ALTER TABLE public.incident_request_edit_logs
  ADD COLUMN IF NOT EXISTS trace_id text;
ALTER TABLE public.incident_assignment_events
  ADD COLUMN IF NOT EXISTS trace_id text;
ALTER TABLE public.account_lifecycle_audit_logs
  ADD COLUMN IF NOT EXISTS trace_id text;
ALTER TABLE public.app_action_audit_logs
  ADD COLUMN IF NOT EXISTS trace_id text;
ALTER TABLE public.api_operation_logs
  ADD COLUMN IF NOT EXISTS trace_id text;
ALTER TABLE public.device_security_events
  ADD COLUMN IF NOT EXISTS trace_id text;

DO $triggers$
DECLARE
  v_table text;
BEGIN
  FOREACH v_table IN ARRAY ARRAY[
    'admin_audit_logs',
    'incident_status_logs',
    'incident_claimability_logs',
    'incident_request_edit_logs',
    'incident_assignment_events',
    'account_lifecycle_audit_logs',
    'app_action_audit_logs',
    'api_operation_logs',
    'device_security_events'
  ] LOOP
    EXECUTE format(
      'DROP TRIGGER IF EXISTS tacresq_stamp_trace_id ON public.%I',
      v_table
    );
    EXECUTE format(
      'CREATE TRIGGER tacresq_stamp_trace_id
       BEFORE INSERT ON public.%I
       FOR EACH ROW EXECUTE FUNCTION private.tacresq_stamp_trace_id()',
      v_table
    );
    EXECUTE format(
      'ALTER TABLE public.%I ADD CONSTRAINT %I
       CHECK (trace_id IS NULL OR trace_id ~ ''^[0-9a-f]{32}$'') NOT VALID',
      v_table, v_table || '_trace_id_format_check'
    );
    EXECUTE format(
      'ALTER TABLE public.%I VALIDATE CONSTRAINT %I',
      v_table, v_table || '_trace_id_format_check'
    );
    EXECUTE format(
      'CREATE INDEX IF NOT EXISTS %I ON public.%I (trace_id) WHERE trace_id IS NOT NULL',
      v_table || '_trace_id_idx', v_table
    );
  END LOOP;
END;
$triggers$;

COMMENT ON FUNCTION private.tacresq_current_trace_id() IS
  'Parses only valid W3C version-00 traceparent headers and returns the non-zero 32-hex trace ID. Caller-influenced tracing metadata only; never identity or authorization.';
COMMENT ON FUNCTION private.tacresq_stamp_trace_id() IS
  'Overwrites event trace_id from the transaction traceparent header; invalid or missing trace context remains NULL and never blocks the event write.';

CREATE OR REPLACE FUNCTION public.api_admin_list_investigation_events(
  p_limit integer DEFAULT 25,
  p_offset integer DEFAULT 0,
  p_query text DEFAULT '',
  p_category text DEFAULT 'all',
  p_source text DEFAULT 'all',
  p_from timestamptz DEFAULT NULL,
  p_to timestamptz DEFAULT NULL,
  p_actor_user_id bigint DEFAULT NULL,
  p_target_user_id bigint DEFAULT NULL,
  p_incident_id bigint DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_query text := lower(btrim(coalesce(p_query, '')));
  v_result jsonb;
BEGIN
  PERFORM private.tacresq_require_admin_mfa();

  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 100
     OR p_offset IS NULL OR p_offset < 0 OR p_offset > 1000000 THEN
    RAISE EXCEPTION 'Investigation pagination values are outside the allowed range'
      USING ERRCODE = '22023';
  END IF;
  IF length(v_query) > 120 THEN
    RAISE EXCEPTION 'Investigation search must be 120 characters or fewer'
      USING ERRCODE = '22023';
  END IF;
  IF p_category IS NULL OR p_category NOT IN (
    'all', 'administrative', 'authentication', 'operations', 'incident', 'assignment'
  ) THEN
    RAISE EXCEPTION 'Unsupported investigation category'
      USING ERRCODE = '22023';
  END IF;
  IF p_source IS NULL OR p_source NOT IN (
    'all',
    'admin_audit_logs',
    'account_lifecycle_audit_logs',
    'supabase_auth_audit_logs',
    'incident_status_logs',
    'incident_claimability_logs',
    'incident_request_edit_logs',
    'incident_assignment_events',
    'app_action_audit_logs',
    'device_security_events',
    'api_operation_logs'
  ) THEN
    RAISE EXCEPTION 'Unsupported investigation source'
      USING ERRCODE = '22023';
  END IF;
  IF p_from IS NOT NULL AND p_to IS NOT NULL AND p_from >= p_to THEN
    RAISE EXCEPTION 'Investigation date range start must be before its end'
      USING ERRCODE = '22023';
  END IF;
  IF (p_actor_user_id IS NOT NULL AND p_actor_user_id < 1)
     OR (p_target_user_id IS NOT NULL AND p_target_user_id < 1)
     OR (p_incident_id IS NOT NULL AND p_incident_id < 1) THEN
    RAISE EXCEPTION 'Investigation profile and incident filters must be positive IDs'
      USING ERRCODE = '22023';
  END IF;

  WITH source_events AS (
    SELECT
      'admin:' || a.id::text AS event_id,
      'admin_audit_logs'::text AS source,
      'administrative'::text AS category,
      a.action AS event_type,
      a.created_at AS occurred_at,
      a.actor_user_id,
      coalesce(
        nullif(btrim(a.actor_identity), ''),
        CASE WHEN a.actor_user_id IS NULL THEN 'Trusted operator / unknown'
             ELSE 'Profile #' || a.actor_user_id::text END
      ) AS actor_label,
      a.target_user_id,
      coalesce(target.name, 'Profile #' || a.target_user_id::text) AS target_label,
      NULL::bigint AS incident_id,
      'Role changed from ' || a.old_role || ' to ' || a.new_role AS summary,
      jsonb_build_object(
        'old_role', a.old_role,
        'new_role', a.new_role,
        'reason', a.reason,
        'auth_session_id', a.auth_session_id,
        'trace_id', a.trace_id
      ) AS details
    FROM public.admin_audit_logs AS a
    LEFT JOIN public.profiles AS target ON target.id = a.target_user_id

    UNION ALL

    SELECT
      'account-lifecycle:' || l.id::text,
      'account_lifecycle_audit_logs',
      'administrative',
      l.action,
      l.created_at,
      l.actor_profile_id,
      coalesce(
        nullif(btrim(l.actor_identity), ''),
        coalesce(actor.name, 'Profile #' || l.actor_profile_id::text)
      ),
      l.target_profile_id,
      coalesce(target.name, 'Profile #' || l.target_profile_id::text),
      NULL::bigint,
      CASE l.action
        WHEN 'account_suspended' THEN 'Account suspended'
        WHEN 'account_reactivated' THEN 'Account reactivated'
        ELSE 'Account lifecycle event recorded'
      END,
      jsonb_build_object(
        'previous_status', l.previous_status,
        'new_status', l.new_status,
        'reason', l.reason,
        'correlation_id', l.correlation_id,
        'auth_session_id', l.auth_session_id,
        'trace_id', l.trace_id
      )
    FROM public.account_lifecycle_audit_logs AS l
    LEFT JOIN public.profiles AS actor ON actor.id = l.actor_profile_id
    LEFT JOIN public.profiles AS target ON target.id = l.target_profile_id

    UNION ALL

    SELECT
      'incident-status:' || s.id::text,
      'incident_status_logs',
      'incident',
      'incident_status_recorded',
      s.created_at,
      s.changed_by,
      coalesce(actor.name, CASE
        WHEN s.changed_by IS NULL THEN 'System / unknown'
        ELSE 'Profile #' || s.changed_by::text
      END),
      NULL::bigint,
      NULL::text,
      s.incident_id,
      'Incident status recorded: ' || s.status,
      jsonb_build_object(
        'status', s.status,
        'auth_session_id', s.auth_session_id,
        'trace_id', s.trace_id
      )
    FROM public.incident_status_logs AS s
    LEFT JOIN public.profiles AS actor ON actor.id = s.changed_by

    UNION ALL

    SELECT
      'claimability:' || c.id::text,
      'incident_claimability_logs',
      'incident',
      'incident_claimability_changed',
      c.created_at,
      c.actor_profile_id,
      coalesce(actor.name, CASE
        WHEN c.actor_profile_id IS NULL THEN 'System / migration'
        ELSE 'Profile #' || c.actor_profile_id::text
      END),
      NULL::bigint,
      NULL::text,
      c.incident_id,
      CASE WHEN c.new_claimable
        THEN 'Incident made available for claiming'
        ELSE 'Incident removed from claiming'
      END,
      jsonb_build_object(
        'previous_claimable', c.previous_claimable,
        'new_claimable', c.new_claimable,
        'incident_status', c.incident_status,
        'source_rpc', c.source_rpc,
        'auth_session_id', c.auth_session_id,
        'trace_id', c.trace_id
      )
    FROM public.incident_claimability_logs AS c
    LEFT JOIN public.profiles AS actor ON actor.id = c.actor_profile_id

    UNION ALL

    SELECT
      'request-edit:' || r.id::text,
      'incident_request_edit_logs',
      'incident',
      'incident_request_edited',
      r.created_at,
      r.requester_id,
      coalesce(actor.name, 'Profile #' || r.requester_id::text),
      NULL::bigint,
      NULL::text,
      r.incident_id,
      'Requester edited incident details',
      jsonb_build_object(
        'edit_number', r.edit_number,
        'changed_fields', changes.changed_fields,
        'auth_session_id', r.auth_session_id,
        'trace_id', r.trace_id
      )
    FROM public.incident_request_edit_logs AS r
    LEFT JOIN public.profiles AS actor ON actor.id = r.requester_id
    LEFT JOIN LATERAL (
      SELECT coalesce(
        jsonb_agg(DISTINCT field_label ORDER BY field_label),
        '[]'::jsonb
      ) AS changed_fields
      FROM (
        SELECT CASE field.key
          WHEN 'type' THEN 'emergency_type'
          WHEN 'description' THEN 'description'
          WHEN 'latitude' THEN 'location'
          WHEN 'longitude' THEN 'location'
          WHEN 'accuracy_m' THEN 'location_accuracy'
          WHEN 'location_notes' THEN 'location_notes'
          WHEN 'location_recorded_at' THEN 'location_timestamp'
          WHEN 'location_source' THEN 'location_source'
          ELSE 'other'
        END AS field_label
        FROM jsonb_object_keys(coalesce(r.before_values, '{}'::jsonb)) AS field(key)
        WHERE r.before_values -> field.key IS DISTINCT FROM r.after_values -> field.key
      ) AS changed
    ) AS changes ON true

    UNION ALL

    SELECT
      'assignment:' || e.id::text,
      'incident_assignment_events',
      'assignment',
      e.event_type,
      e.occurred_at,
      e.actor_user_id,
      coalesce(actor.name, CASE
        WHEN e.actor_user_id IS NULL THEN 'System / unknown'
        ELSE 'Profile #' || e.actor_user_id::text
      END),
      e.user_id,
      coalesce(target.name, 'Profile #' || e.user_id::text),
      e.incident_id,
      CASE e.event_type
        WHEN 'assigned' THEN 'Field unit assigned'
        WHEN 'released' THEN 'Field unit released'
        WHEN 'incident_closed' THEN 'Incident team closed'
        ELSE 'Assignment event recorded'
      END,
      jsonb_build_object(
        'assignment_status', e.assignment_status,
        'incident_status', e.incident_status,
        'recording_source', e.source,
        'auth_session_id', e.auth_session_id,
        'trace_id', e.trace_id
      )
    FROM public.incident_assignment_events AS e
    LEFT JOIN public.profiles AS actor ON actor.id = e.actor_user_id
    LEFT JOIN public.profiles AS target ON target.id = e.user_id

    UNION ALL

    SELECT
      'auth:' || a.id::text,
      'supabase_auth_audit_logs',
      'authentication',
      coalesce(nullif(a.payload ->> 'action', ''), 'auth_event'),
      a.created_at,
      actor.id,
      coalesce(actor.name, CASE
        WHEN a.payload ->> 'user_id' IS NULL THEN 'Unknown Auth user'
        ELSE 'Unlinked Auth user'
      END),
      NULL::bigint,
      NULL::text,
      NULL::bigint,
      'Supabase Auth event: ' || coalesce(nullif(a.payload ->> 'action', ''), 'auth_event'),
      jsonb_build_object(
        'provider_action', coalesce(nullif(a.payload ->> 'action', ''), 'auth_event')
      )
    FROM auth.audit_log_entries AS a
    LEFT JOIN public.profiles AS actor
      ON actor.auth_user_id = CASE
        WHEN coalesce(a.payload ->> 'user_id', '') ~*
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        THEN (a.payload ->> 'user_id')::uuid
        ELSE NULL
      END
      UNION ALL

    SELECT
      'app-action:' || a.id::text,
      'app_action_audit_logs',
      CASE a.action
        WHEN 'duty_status_changed' THEN 'assignment'
        ELSE 'incident'
      END,
      a.action,
      a.created_at,
      a.actor_profile_id,
      coalesce(actor.name, CASE
        WHEN a.actor_profile_id IS NULL THEN 'System / unknown'
        ELSE 'Profile #' || a.actor_profile_id::text
      END),
      a.target_user_id,
      CASE WHEN a.target_user_id IS NULL THEN NULL
           ELSE coalesce(target.name, 'Profile #' || a.target_user_id::text)
      END,
      a.incident_id,
      CASE a.action
        WHEN 'incident_message_sent' THEN 'Incident chat message sent'
        WHEN 'notification_marked_read' THEN 'Notification marked as read'
        WHEN 'duty_status_changed' THEN 'Field-unit duty status changed'
        ELSE 'Application action recorded'
      END,
      jsonb_build_object(
        'correlation_id', a.correlation_id,
        'auth_session_id', a.auth_session_id,
        'trace_id', a.trace_id,
        'actor_role', a.actor_role,
        'target_type', a.target_type,
        'target_id', a.target_id,
        'outcome', a.outcome,
        'metadata', a.metadata
      )
    FROM public.app_action_audit_logs AS a
    LEFT JOIN public.profiles AS actor ON actor.id = a.actor_profile_id
    LEFT JOIN public.profiles AS target ON target.id = a.target_user_id

    UNION ALL

    -- Device events are read through this Admin-only projection. Raw details
    -- are intentionally excluded; the recorded Auth session UUID is the
    -- cross-source join key shared with Laravel API-operation telemetry.
    SELECT
      'device-security:' || d.id::text,
      'device_security_events',
      'authentication',
      d.event_type,
      d.created_at,
      d.profile_id,
      coalesce(actor.name, CASE
        WHEN d.profile_id IS NULL THEN 'Unlinked / deleted account'
        ELSE 'Profile #' || d.profile_id::text
      END),
      NULL::bigint,
      NULL::text,
      NULL::bigint,
      'Device security event: ' || replace(d.event_type, '_', ' '),
      jsonb_build_object(
        'severity', d.severity,
        'auth_session_id', d.auth_session_id,
        'trace_id', d.trace_id
      )
    FROM public.device_security_events AS d
    LEFT JOIN public.profiles AS actor ON actor.id = d.profile_id

    UNION ALL

    SELECT
      'api-operation:' || o.id::text,
      'api_operation_logs',
      'operations',
      o.operation,
      o.created_at,
      actor.id,
      coalesce(actor.name, CASE
        WHEN o.actor_auth_user_id IS NULL THEN 'System / service operation'
        ELSE 'Unlinked Auth user'
      END),
      NULL::bigint,
      NULL::text,
      NULL::bigint,
      'API operation ' || o.operation || ' (' || o.outcome || ')',
      jsonb_build_object(
        'correlation_id', o.correlation_id,
        'auth_session_id', o.auth_session_id,
        'trace_id', o.trace_id,
        'provider_request_id', o.provider_request_id,
        'provider', o.provider,
        'operation', o.operation,
        'outcome', o.outcome,
        'http_status', o.http_status,
        'duration_ms', o.duration_ms,
        'error_code', o.error_code,
        'recording_source', o.source
      )
    FROM public.api_operation_logs AS o
    LEFT JOIN public.profiles AS actor
      ON actor.auth_user_id = o.actor_auth_user_id
  ),
  filtered AS MATERIALIZED (
    SELECT e.*
    FROM source_events AS e
    WHERE (p_category = 'all' OR e.category = p_category)
      AND (p_source = 'all' OR e.source = p_source)
      AND (p_from IS NULL OR e.occurred_at >= p_from)
      AND (p_to IS NULL OR e.occurred_at < p_to)
      AND (p_actor_user_id IS NULL OR e.actor_user_id = p_actor_user_id)
      AND (p_target_user_id IS NULL OR e.target_user_id = p_target_user_id)
      AND (p_incident_id IS NULL OR e.incident_id = p_incident_id)
      AND (
        v_query = ''
        OR (
          v_query ~ '^[0-9a-f]{32}$'
          AND e.details ->> 'trace_id' = v_query
        )
        OR (
          v_query !~ '^[0-9a-f]{32}$'
          AND (
            strpos(lower(e.event_id), v_query) > 0
            OR strpos(lower(e.source), v_query) > 0
            OR strpos(lower(e.category), v_query) > 0
            OR strpos(lower(e.event_type), v_query) > 0
            OR strpos(lower(e.actor_label), v_query) > 0
            OR strpos(lower(coalesce(e.target_label, '')), v_query) > 0
            OR strpos(lower(e.summary), v_query) > 0
            OR strpos(coalesce(e.actor_user_id::text, ''), v_query) > 0
            OR strpos(coalesce(e.target_user_id::text, ''), v_query) > 0
            OR strpos(coalesce(e.incident_id::text, ''), v_query) > 0
            OR strpos(lower(e.details::text), v_query) > 0
          )
        )
      )
  )
  SELECT jsonb_build_object(
    'events',
    coalesce(
      (
        SELECT jsonb_agg(
          to_jsonb(page_row)
          ORDER BY page_row.occurred_at DESC, page_row.event_id DESC
        )
        FROM (
          SELECT *
          FROM filtered
          ORDER BY occurred_at DESC, event_id DESC
          LIMIT p_limit OFFSET p_offset
        ) AS page_row
      ),
      '[]'::jsonb
    ),
    'total_count', (SELECT count(*) FROM filtered)
  )
  INTO v_result;

  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.api_admin_list_investigation_events(
  integer, integer, text, text, text, timestamptz, timestamptz, bigint, bigint, bigint
) FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.api_admin_list_investigation_events(
  integer, integer, text, text, text, timestamptz, timestamptz, bigint, bigint, bigint
) TO authenticated;

COMMENT ON FUNCTION public.api_admin_list_investigation_events(
  integer, integer, text, text, text, timestamptz, timestamptz, bigint, bigint, bigint
) IS
  'TOTP/AAL2-protected, bounded Admin investigation feed over role and account-lifecycle audit, authentication, operations, incident, assignment, and metadata-only app-action event sources. Signed Auth session IDs are historical attribution keys, not live-session validity assertions.';

COMMIT;
