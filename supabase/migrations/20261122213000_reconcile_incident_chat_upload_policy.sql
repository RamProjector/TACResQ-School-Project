BEGIN;

-- Reconcile test projects where the original reservation migration was
-- applied from an older SQL payload that omitted the staged enforcement flag.
-- Preserve any existing true value; a newly added column defaults to false.
ALTER TABLE private.incident_chat_upload_policy
  ADD COLUMN IF NOT EXISTS reservation_enforcement_enabled boolean NOT NULL DEFAULT false;

UPDATE private.incident_chat_upload_policy
   SET reservation_enforcement_enabled = false
 WHERE singleton = true
   AND reservation_enforcement_enabled IS NULL;

ALTER TABLE private.incident_chat_upload_policy
  ALTER COLUMN reservation_enforcement_enabled SET DEFAULT false,
  ALTER COLUMN reservation_enforcement_enabled SET NOT NULL;

COMMENT ON COLUMN private.incident_chat_upload_policy.reservation_enforcement_enabled IS
  'False preserves legacy upload/finalization during Android rollout; enable only after all supported clients reserve before Storage upload.';

-- Restore the staged reservation gate. In compatibility mode, the Storage
-- policy still checks caller path, MIME, size, and incident membership.
CREATE OR REPLACE FUNCTION private.tacresq_upload_reservation_allows_insert(
  p_object_path text,
  p_mime_type text,
  p_size_bytes bigint
)
RETURNS boolean
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
  actor_profile_id bigint;
  enforcement_enabled boolean;
BEGIN
  actor_profile_id := private.tacresq_auth_profile_id();
  IF actor_profile_id IS NULL THEN
    RETURN false;
  END IF;

  SELECT policy.reservation_enforcement_enabled
    INTO enforcement_enabled
    FROM private.incident_chat_upload_policy AS policy
   WHERE policy.singleton = true;

  IF enforcement_enabled IS NULL THEN
    RETURN false;
  END IF;
  IF NOT enforcement_enabled THEN
    RETURN true;
  END IF;

  RETURN EXISTS (
    SELECT 1
      FROM private.incident_chat_upload_reservations r
     WHERE r.profile_id = actor_profile_id
       AND r.object_path = p_object_path
       AND r.mime_type = p_mime_type
       AND r.size_bytes = p_size_bytes
       AND r.consumed_at IS NULL
       AND r.expires_at > clock_timestamp()
       AND private.tacresq_can_access_incident_chat(r.incident_id, actor_profile_id)
  );
END;
$function$;

COMMIT;
