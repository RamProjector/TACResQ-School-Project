BEGIN;

-- The prior migration deliberately excludes fcm_tokens from the broad policy
-- replacement so its operation-specific SELECT/INSERT/UPDATE policies and
-- suspended self-unregister DELETE exception remain intact. Remove the legacy
-- all-operations policy as well; otherwise it continues to call the locking
-- helper during read-only token/profile verification.
DROP POLICY IF EXISTS tacresq_active_account_required ON public.fcm_tokens;

COMMIT;
