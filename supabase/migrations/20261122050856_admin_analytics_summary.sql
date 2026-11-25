BEGIN;

-- Admin-only aggregate analytics. The browser receives counts and time buckets
-- only; requester identities, incident narratives, and precise coordinates are
-- deliberately excluded from this response.
CREATE INDEX IF NOT EXISTS incidents_created_at_id_idx
  ON public.incidents (created_at, id);

CREATE INDEX IF NOT EXISTS incident_status_logs_incident_status_time_idx
  ON public.incident_status_logs (incident_id, status, created_at);

CREATE OR REPLACE FUNCTION public.api_admin_analytics_summary(
  p_from timestamptz,
  p_to timestamptz
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  result jsonb;
BEGIN
  IF auth.uid() IS NULL OR NOT EXISTS (
    SELECT 1
    FROM public.profiles p
    WHERE p.auth_user_id = auth.uid()
      AND p.role = 'admin'
  ) THEN
    RAISE EXCEPTION 'Administrator access required'
      USING ERRCODE = '42501';
  END IF;

  IF p_from IS NULL OR p_to IS NULL OR p_to <= p_from
     OR p_to - p_from > interval '366 days' THEN
    RAISE EXCEPTION 'Choose a valid reporting period of up to 366 days'
      USING ERRCODE = '22023';
  END IF;

  WITH incident_scope AS (
    SELECT i.id, i.status, i.created_at
    FROM public.incidents i
    WHERE i.created_at >= p_from
      AND i.created_at < p_to
  ),
  first_assignment AS (
    SELECT l.incident_id, min(l.created_at) AS assigned_at
    FROM public.incident_status_logs l
    JOIN incident_scope i ON i.id = l.incident_id
    WHERE l.status = 'assigned'
    GROUP BY l.incident_id
  ),
  daily AS (
    SELECT d.day::date AS day,
           count(i.id)::integer AS requests
    FROM generate_series(
      date_trunc('day', p_from AT TIME ZONE 'Asia/Manila'),
      date_trunc('day', (p_to - interval '1 microsecond') AT TIME ZONE 'Asia/Manila'),
      interval '1 day'
    ) AS d(day)
    LEFT JOIN incident_scope i
      ON i.created_at AT TIME ZONE 'Asia/Manila' >= d.day
     AND i.created_at AT TIME ZONE 'Asia/Manila' < d.day + interval '1 day'
    GROUP BY d.day
    ORDER BY d.day
  ),
  assignment_times AS (
    SELECT extract(epoch FROM (a.assigned_at - i.created_at)) / 60.0 AS minutes
    FROM incident_scope i
    JOIN first_assignment a ON a.incident_id = i.id
    WHERE a.assigned_at >= i.created_at
  )
  SELECT jsonb_build_object(
    'period_start', p_from,
    'period_end', p_to,
    'reporting_timezone', 'Asia/Manila',
    'total_requests', (SELECT count(*)::integer FROM incident_scope),
    'pending', (SELECT count(*)::integer FROM incident_scope WHERE status = 'pending'),
    'active', (SELECT count(*)::integer FROM incident_scope WHERE status IN ('assigned', 'en_route', 'on_scene', 'transporting')),
    'resolved', (SELECT count(*)::integer FROM incident_scope WHERE status = 'resolved'),
    'cancelled', (SELECT count(*)::integer FROM incident_scope WHERE status = 'cancelled'),
    'average_assignment_minutes', (
      SELECT round(avg(minutes)::numeric, 1) FROM assignment_times
    ),
    'median_assignment_minutes', (
      SELECT round((percentile_cont(0.5) WITHIN GROUP (ORDER BY minutes))::numeric, 1)
      FROM assignment_times
    ),
    'assigned_request_count', (SELECT count(*)::integer FROM assignment_times),
    'daily_requests', coalesce((
      SELECT jsonb_agg(jsonb_build_object('day', day, 'requests', requests) ORDER BY day)
      FROM daily
    ), '[]'::jsonb)
  )
  INTO result;

  RETURN result;
END;
$function$;

REVOKE ALL ON FUNCTION public.api_admin_analytics_summary(timestamptz, timestamptz)
  FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.api_admin_analytics_summary(timestamptz, timestamptz)
  FROM authenticated;
GRANT EXECUTE ON FUNCTION public.api_admin_analytics_summary(timestamptz, timestamptz)
  TO authenticated;

COMMIT;
