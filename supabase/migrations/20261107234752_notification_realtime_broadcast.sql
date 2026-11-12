BEGIN;

-- The local PostgreSQL replay harness does not provide Supabase Realtime. The
-- guarded block is a no-op there; on Supabase it creates a private, user-scoped
-- broadcast topic without opening direct access to user_notifications.
DO $migration$
BEGIN
  IF to_regclass('realtime.messages') IS NULL
     OR to_regprocedure('realtime.send(jsonb,text,text,boolean)') IS NULL THEN
    RAISE NOTICE 'Supabase Realtime is unavailable; notification broadcast setup skipped.';
    RETURN;
  END IF;

  EXECUTE $policy$
    CREATE POLICY tacresq_authenticated_notification_broadcast_read
      ON realtime.messages
      FOR SELECT
      TO authenticated
      USING (
        extension = 'broadcast'
        AND realtime.topic() = 'user:' || (
          SELECT p.id::text
            FROM public.profiles p
           WHERE p.auth_user_id = auth.uid()
        )
      )
  $policy$;

  EXECUTE $function$
    CREATE OR REPLACE FUNCTION private.tacresq_broadcast_notification_insert()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
    AS $body$
    BEGIN
      PERFORM realtime.send(
        jsonb_build_object(
          'notification_id', NEW.id,
          'event_type', NEW.event_type
        ),
        'notification_created',
        'user:' || NEW.recipient_user_id::text,
        true
      );
      RETURN NEW;
    END;
    $body$
  $function$;

  EXECUTE 'REVOKE ALL ON FUNCTION private.tacresq_broadcast_notification_insert() FROM PUBLIC, anon, authenticated';
  EXECUTE 'DROP TRIGGER IF EXISTS tacresq_broadcast_notification_insert ON public.user_notifications';
  EXECUTE $trigger$
    CREATE TRIGGER tacresq_broadcast_notification_insert
    AFTER INSERT ON public.user_notifications
    FOR EACH ROW
    EXECUTE FUNCTION private.tacresq_broadcast_notification_insert()
  $trigger$;
END;
$migration$;

COMMIT;
