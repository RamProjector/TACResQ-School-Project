BEGIN;

-- API operation telemetry is written by the Laravel bridge/worker using a
-- server-generated request correlation ID and provider response request ID.
-- Expose only allowlisted metadata through the existing MFA-protected feed.
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
        'reason', a.reason
      ) AS details
    FROM public.admin_audit_logs AS a
    LEFT JOIN public.profiles AS target ON target.id = a.target_user_id

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
      jsonb_build_object('status', s.status)
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
        'source_rpc', c.source_rpc
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
        'changed_fields', changes.changed_fields
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
        'recording_source', e.source
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
    -- are intentionally excluded; the verified Auth session UUID is the
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
        'auth_session_id', d.auth_session_id
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
        OR strpos(lower(e.event_id), v_query) > 0
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
  'TOTP/AAL2-protected, bounded Admin investigation feed over existing administrative, authentication, operations, incident, assignment, and metadata-only app-action/API operation event sources.';

COMMIT;
