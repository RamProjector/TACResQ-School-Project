BEGIN;

-- Bound untrusted/client-originated metadata before it reaches the registry.
-- These constraints limit storage abuse; they do not authenticate a device.
ALTER TABLE public.user_devices
  ADD CONSTRAINT user_devices_device_model_length_check
    CHECK (device_model IS NULL OR char_length(device_model) BETWEEN 1 AND 120),
  ADD CONSTRAINT user_devices_app_version_length_check
    CHECK (app_version IS NULL OR char_length(app_version) BETWEEN 1 AND 80),
  ADD CONSTRAINT user_devices_public_key_length_check
    CHECK (char_length(public_key) BETWEEN 32 AND 4096),
  ADD CONSTRAINT user_devices_revoked_reason_length_check
    CHECK (revoked_reason IS NULL OR char_length(revoked_reason) BETWEEN 1 AND 500);

ALTER TABLE public.device_sessions
  ADD CONSTRAINT device_sessions_expiry_check
    CHECK (expires_at IS NULL OR expires_at > created_at),
  ADD CONSTRAINT device_sessions_revocation_consistency_check
    CHECK (
      (revoked_at IS NULL AND revoke_reason IS NULL)
      OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL
          AND char_length(revoke_reason) BETWEEN 1 AND 500)
    );

ALTER TABLE public.device_challenges
  ADD CONSTRAINT device_challenges_max_lifetime_check
    CHECK (expires_at <= created_at + interval '10 minutes');

ALTER TABLE public.device_security_events
  ADD CONSTRAINT device_security_events_details_size_check
    CHECK (octet_length(details::text) <= 8192);

ALTER TABLE public.device_integrity_checks
  ADD CONSTRAINT device_integrity_checks_summary_size_check
    CHECK (octet_length(result_summary::text) <= 4096);

COMMENT ON CONSTRAINT device_challenges_max_lifetime_check
  ON public.device_challenges IS
  'Challenge records cannot remain valid for more than ten minutes after issuance.';
COMMENT ON CONSTRAINT device_security_events_details_size_check
  ON public.device_security_events IS
  'Bounds backend-generated event metadata; never place secrets or raw tokens in details.';
COMMENT ON CONSTRAINT device_integrity_checks_summary_size_check
  ON public.device_integrity_checks IS
  'Bounds stored verifier summaries; raw provider tokens and signatures are not permitted.';

COMMIT;
