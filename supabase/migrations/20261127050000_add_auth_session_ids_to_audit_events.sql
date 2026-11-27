-- Preserve the authenticated Supabase session identity on durable app/API audit events.
-- No foreign key is used: auth.sessions rows are deleted on sign-out, while audit
-- history must retain the session identifier that performed the operation.

ALTER TABLE public.api_operation_logs
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;

CREATE INDEX IF NOT EXISTS api_operation_logs_auth_session_created_idx
  ON public.api_operation_logs (auth_session_id, created_at DESC, id DESC)
  WHERE auth_session_id IS NOT NULL;

COMMENT ON COLUMN public.api_operation_logs.auth_session_id IS
  'Signed Supabase Auth session_id observed after the Auth user endpoint validated the caller JWT; retained after session deletion.';

ALTER TABLE public.app_action_audit_logs
  ADD COLUMN IF NOT EXISTS auth_session_id uuid;

CREATE INDEX IF NOT EXISTS app_action_audit_logs_auth_session_created_idx
  ON public.app_action_audit_logs (auth_session_id, created_at DESC, id DESC)
  WHERE auth_session_id IS NOT NULL;

COMMENT ON COLUMN public.app_action_audit_logs.auth_session_id IS
  'session_id claim from the verified PostgREST JWT context; retained after auth.sessions deletion and NULL for system actions.';

-- Generate one server-owned correlation ID per database transaction. Trigger
-- writers reuse it so multiple audit rows created by one operation are grouped.
CREATE OR REPLACE FUNCTION private.tacresq_current_audit_correlation_id()
RETURNS uuid
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $correlation$
DECLARE
  v_correlation_id text;
BEGIN
  v_correlation_id := current_setting('tacresq.audit_correlation_id', true);
  IF v_correlation_id ~*
     '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
    RETURN v_correlation_id::uuid;
  END IF;

  v_correlation_id := gen_random_uuid()::text;
  PERFORM set_config('tacresq.audit_correlation_id', v_correlation_id, true);
  RETURN v_correlation_id::uuid;
END;
$correlation$;

REVOKE ALL ON FUNCTION private.tacresq_current_audit_correlation_id()
  FROM PUBLIC, anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION private.tacresq_record_app_action_audit()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_actor_profile_id bigint;
  v_actor_role text;
  v_action text;
  v_target_type text;
  v_target_id text;
  v_target_user_id bigint;
  v_incident_id bigint;
  v_metadata jsonb := '{}'::jsonb;
  v_session_id_text text;
  v_auth_session_id uuid;
  v_correlation_id uuid;
BEGIN
  -- PostgREST verifies the bearer JWT before trigger execution. Read only its
  -- signed session_id claim; never accept a session ID from the row/body.
  v_session_id_text := (SELECT auth.jwt()) ->> 'session_id';
  IF v_session_id_text ~*
     '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
    v_auth_session_id := v_session_id_text::uuid;
  END IF;

  IF TG_TABLE_NAME = 'incident_messages' AND TG_OP = 'INSERT' THEN
    v_actor_profile_id := NEW.sender_id;
    v_action := 'incident_message_sent';
    v_target_type := 'incident_message';
    v_target_id := NEW.id::text;
    v_incident_id := NEW.incident_id;
    v_metadata := jsonb_build_object(
      'message_kind',
      CASE WHEN NEW.attachment_path IS NULL THEN 'text' ELSE 'image' END
    );
  ELSIF TG_TABLE_NAME = 'user_notifications' AND TG_OP = 'UPDATE' THEN
    IF OLD.read_at IS NOT NULL OR NEW.read_at IS NULL THEN
      RETURN NEW;
    END IF;
    SELECT p.id, p.role::text
      INTO v_actor_profile_id, v_actor_role
      FROM public.profiles p
     WHERE p.auth_user_id = auth.uid();
    IF v_actor_profile_id IS NULL THEN
      RETURN NEW;
    END IF;
    v_action := 'notification_marked_read';
    v_target_type := 'notification';
    v_target_id := NEW.id::text;
    v_target_user_id := NEW.recipient_user_id;
    v_incident_id := NEW.incident_id;
    v_metadata := jsonb_build_object('notification_type', NEW.event_type);
  ELSIF TG_TABLE_NAME = 'profiles' AND TG_OP = 'UPDATE' THEN
    IF NEW.duty_status IS NOT DISTINCT FROM OLD.duty_status
       OR NEW.role NOT IN ('responder', 'driver') THEN
      RETURN NEW;
    END IF;
    SELECT p.id, p.role::text
      INTO v_actor_profile_id, v_actor_role
      FROM public.profiles p
     WHERE p.auth_user_id = auth.uid();
    v_action := 'duty_status_changed';
    v_target_type := 'profile';
    v_target_id := NEW.id::text;
    v_target_user_id := NEW.id;
    v_metadata := jsonb_build_object(
      'previous_status', OLD.duty_status,
      'new_status', NEW.duty_status
    );
  ELSE
    RETURN NEW;
  END IF;

  IF v_actor_role IS NULL AND v_actor_profile_id IS NOT NULL THEN
    SELECT p.role::text INTO v_actor_role
      FROM public.profiles p WHERE p.id = v_actor_profile_id;
  END IF;

  v_correlation_id := private.tacresq_current_audit_correlation_id();

  INSERT INTO public.app_action_audit_logs (
    actor_profile_id, actor_role, action, target_type, target_id,
    target_user_id, incident_id, metadata, auth_session_id, correlation_id
  ) VALUES (
    v_actor_profile_id, v_actor_role, v_action, v_target_type, v_target_id,
    v_target_user_id, v_incident_id, v_metadata, v_auth_session_id, v_correlation_id
  );

  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_record_app_action_audit()
  FROM PUBLIC, anon, authenticated, service_role;

 THEN
    RETURN v_correlation_id::uuid;
  END IF;

  v_correlation_id := gen_random_uuid()::text;
  PERFORM set_config('tacresq.audit_correlation_id', v_correlation_id, true);
  RETURN v_correlation_id::uuid;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_current_audit_correlation_id()
  FROM PUBLIC, anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION private.tacresq_record_app_action_audit()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  v_actor_profile_id bigint;
  v_actor_role text;
  v_action text;
  v_target_type text;
  v_target_id text;
  v_target_user_id bigint;
  v_incident_id bigint;
  v_metadata jsonb := '{}'::jsonb;
  v_session_id_text text;
  v_auth_session_id uuid;
BEGIN
  -- PostgREST verifies the bearer JWT before trigger execution. Read only its
  -- signed session_id claim; never accept a session ID from the row/body.
  v_session_id_text := (SELECT auth.jwt()) ->> 'session_id';
  IF v_session_id_text ~*
     '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
    v_auth_session_id := v_session_id_text::uuid;
  END IF;

  IF TG_TABLE_NAME = 'incident_messages' AND TG_OP = 'INSERT' THEN
    v_actor_profile_id := NEW.sender_id;
    v_action := 'incident_message_sent';
    v_target_type := 'incident_message';
    v_target_id := NEW.id::text;
    v_incident_id := NEW.incident_id;
    v_metadata := jsonb_build_object(
      'message_kind',
      CASE WHEN NEW.attachment_path IS NULL THEN 'text' ELSE 'image' END
    );
  ELSIF TG_TABLE_NAME = 'user_notifications' AND TG_OP = 'UPDATE' THEN
    IF OLD.read_at IS NOT NULL OR NEW.read_at IS NULL THEN
      RETURN NEW;
    END IF;
    SELECT p.id, p.role::text
      INTO v_actor_profile_id, v_actor_role
      FROM public.profiles p
     WHERE p.auth_user_id = auth.uid();
    IF v_actor_profile_id IS NULL THEN
      RETURN NEW;
    END IF;
    v_action := 'notification_marked_read';
    v_target_type := 'notification';
    v_target_id := NEW.id::text;
    v_target_user_id := NEW.recipient_user_id;
    v_incident_id := NEW.incident_id;
    v_metadata := jsonb_build_object('notification_type', NEW.event_type);
  ELSIF TG_TABLE_NAME = 'profiles' AND TG_OP = 'UPDATE' THEN
    IF NEW.duty_status IS NOT DISTINCT FROM OLD.duty_status
       OR NEW.role NOT IN ('responder', 'driver') THEN
      RETURN NEW;
    END IF;
    SELECT p.id, p.role::text
      INTO v_actor_profile_id, v_actor_role
      FROM public.profiles p
     WHERE p.auth_user_id = auth.uid();
    v_action := 'duty_status_changed';
    v_target_type := 'profile';
    v_target_id := NEW.id::text;
    v_target_user_id := NEW.id;
    v_metadata := jsonb_build_object(
      'previous_status', OLD.duty_status,
      'new_status', NEW.duty_status
    );
  ELSE
    RETURN NEW;
  END IF;

  IF v_actor_role IS NULL AND v_actor_profile_id IS NOT NULL THEN
    SELECT p.role::text INTO v_actor_role
      FROM public.profiles p WHERE p.id = v_actor_profile_id;
  END IF;

  v_correlation_id := private.tacresq_current_audit_correlation_id();

  INSERT INTO public.app_action_audit_logs (
    actor_profile_id, actor_role, action, target_type, target_id,
    target_user_id, incident_id, metadata, auth_session_id, correlation_id
  ) VALUES (
    v_actor_profile_id, v_actor_role, v_action, v_target_type, v_target_id,
    v_target_user_id, v_incident_id, v_metadata, v_auth_session_id, v_correlation_id
  );

  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION private.tacresq_record_app_action_audit()
  FROM PUBLIC, anon, authenticated, service_role;

