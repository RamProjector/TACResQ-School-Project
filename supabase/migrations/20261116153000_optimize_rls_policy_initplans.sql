BEGIN;

-- Evaluate request identity and the no-argument actor-role helper once per
-- statement (InitPlan), rather than once for every candidate row.
-- Keep tacresq_is_incident_assignee(id) row-dependent: its incident ID must
-- continue to be evaluated against the row being checked.

ALTER POLICY incident_assignments_read_participants
ON public.incident_assignments
USING (
  private.tacresq_is_incident_assignee(incident_id)
  OR (SELECT private.tacresq_actor_role()) = 'dispatcher'
  OR EXISTS (
    SELECT 1
    FROM public.incidents i
    JOIN public.profiles requester ON requester.id = i.requester_id
    WHERE i.id = incident_assignments.incident_id
      AND requester.auth_user_id = (SELECT auth.uid())
  )
);

ALTER POLICY incidents_read_participant_or_dispatcher
ON public.incidents
USING (
  requester_id IN (
    SELECT p.id
    FROM public.profiles p
    WHERE p.auth_user_id = (SELECT auth.uid())
  )
  OR assigned_to IN (
    SELECT p.id
    FROM public.profiles p
    WHERE p.auth_user_id = (SELECT auth.uid())
  )
  OR private.tacresq_is_incident_assignee(id)
  OR (SELECT private.tacresq_actor_role()) = 'dispatcher'
);

ALTER POLICY incidents_update_dispatcher_or_assigned_unit
ON public.incidents
USING (
  (SELECT private.tacresq_actor_role()) = 'dispatcher'
  OR assigned_to IN (
    SELECT p.id
    FROM public.profiles p
    WHERE p.auth_user_id = (SELECT auth.uid())
  )
  OR private.tacresq_is_incident_assignee(id)
)
WITH CHECK (
  (SELECT private.tacresq_actor_role()) = 'dispatcher'
  OR assigned_to IN (
    SELECT p.id
    FROM public.profiles p
    WHERE p.auth_user_id = (SELECT auth.uid())
  )
  OR private.tacresq_is_incident_assignee(id)
);

ALTER POLICY location_logs_read_dispatcher_or_owner
ON public.location_logs
USING (
  EXISTS (
    SELECT 1
    FROM public.incidents i
    JOIN public.incident_assignments a ON a.incident_id = i.id
    WHERE i.id = location_logs.incident_id
      AND i.status = 'en_route'
      AND a.user_id = location_logs.user_id
      AND a.responder_status = 'en_route'
      AND (
        a.user_id IN (
          SELECT p.id
          FROM public.profiles p
          WHERE p.auth_user_id = (SELECT auth.uid())
            AND p.role = ANY (ARRAY['responder'::text, 'driver'::text])
        )
        OR (SELECT private.tacresq_actor_role()) = 'dispatcher'
      )
  )
);

ALTER POLICY location_logs_insert_owner
ON public.location_logs
WITH CHECK (
  user_id IN (
    SELECT p.id
    FROM public.profiles p
    WHERE p.auth_user_id = (SELECT auth.uid())
      AND p.role = ANY (ARRAY['responder'::text, 'driver'::text])
  )
  AND incident_id IS NOT NULL
  AND EXISTS (
    SELECT 1
    FROM public.incidents i
    JOIN public.incident_assignments a ON a.incident_id = i.id
    WHERE i.id = location_logs.incident_id
      AND i.status = 'en_route'
      AND a.user_id = location_logs.user_id
      AND a.responder_status = 'en_route'
  )
);

-- Realtime is not present in the local PostgreSQL replay harness. On
-- Supabase, preserve the exact user-scoped broadcast predicate and optimize
-- only the request-identity lookup. Do not create a missing policy here.
DO $migration$
BEGIN
  IF to_regclass('realtime.messages') IS NOT NULL
     AND EXISTS (
       SELECT 1
       FROM pg_catalog.pg_policies
       WHERE schemaname = 'realtime'
         AND tablename = 'messages'
         AND policyname = 'tacresq_authenticated_notification_broadcast_read'
     ) THEN
    EXECUTE $policy$
      ALTER POLICY tacresq_authenticated_notification_broadcast_read
      ON realtime.messages
      USING (
        extension = 'broadcast'
        AND realtime.topic() = 'user:' || (
          SELECT p.id::text
          FROM public.profiles p
          WHERE p.auth_user_id = (SELECT auth.uid())
        )
      )
    $policy$;
  END IF;
END;
$migration$;

COMMIT;
