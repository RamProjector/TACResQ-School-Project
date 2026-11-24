#!/usr/bin/env python3
"""Offline contract checks for the trusted, server-only FCM sender."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


transport = read("backend/src/app/FcmHttpV1Client.php")
error_classifier = read("backend/src/app/FcmHttpV1ErrorClassifier.php")
dispatcher = read("backend/src/app/FcmNotificationDispatcher.php")
command = read("backend/src/app/Console/Commands/DispatchFcmNotifications.php")
config = read("backend/src/config/tacru.php")
dockerfile = read("backend/Dockerfile")
env = read("backend/.env.example")
base_migration = read("supabase/migrations/20261105235016_fcm_delivery_attempts.sql")
queue_migration = read("supabase/migrations/20261114153546_fcm_delivery_queue_hardening.sql")
queue_test = read("tools/test_fcm_delivery_queue.sql")
docs = read("docs/FCM_SETUP.md")

assert "ApplicationDefaultCredentials::getCredentials" in transport
assert "->timeout(10)" in transport
assert "->timeout(10)" in dispatcher
assert "$baseSeconds = $httpStatus === 429 ? 60 : 10;" in dispatcher
assert "https://www.googleapis.com/auth/firebase.messaging" in transport
assert "fcm.googleapis.com/v1/projects/" in transport
assert "['message' => ['token' => $token, 'data' => $data]]" in transport
assert "'status'" in error_classifier
assert "FcmHttpV1ErrorClassifier::classify" in transport
assert "type.googleapis.com/google.firebase.fcm.v1.FcmError" in error_classifier
assert "type.googleapis.com/google.rpc.BadRequest" in error_classifier
assert "$fcmErrorCode === 'UNREGISTERED'" in error_classifier
assert "$fcmErrorCode === 'INVALID_ARGUMENT'" in error_classifier
assert "$hasBadRequestDetails" in error_classifier
assert "'retryable' => $retryable" in error_classifier
assert "Retry-After" in transport and "retryAfterSeconds" in transport
assert "INVALID_ARGUMENT" in error_classifier
assert "supabase_secret_key" in config
assert "fcm_project_id" in config
assert "SUPABASE_SECRET_KEY" in env
assert "GOOGLE_APPLICATION_CREDENTIALS" in env
assert "google/auth:^1.48" in dockerfile
assert "api_claim_fcm_delivery_batch" in dispatcher
assert "p_lease_seconds" in dispatcher
assert "$configuredLimit = (int) config('tacru.fcm_batch_limit', 50);" in dispatcher
assert "$limit = $limit ?? max(1, min($configuredLimit, 50));" in dispatcher
assert "fcm_delivery_attempts" in dispatcher
assert "next_attempt_at" in dispatcher
assert "attempt_count" in dispatcher
assert "read_at" not in dispatcher
assert "claimAttempt" not in dispatcher
assert "resolution=ignore-duplicates" not in dispatcher
assert "http_build_query" in dispatcher
assert "PHP_QUERY_RFC3986" in dispatcher
assert "$this->rpcUrl('api_unregister_fcm_token')" in dispatcher
assert "'p_token' => $token" in dispatcher
assert "$this->client()->delete($this->tableUrl('fcm_tokens'), [" not in dispatcher
assert "permanent_failure" in dispatcher
assert "persistAttemptSafely" in dispatcher
assert "FCM delivery ledger update failed for attempt." in dispatcher
assert "continue;" in dispatcher
assert "catch (\\Throwable $exception)" in dispatcher
assert "Log::warning(" in dispatcher
assert "$exception->getMessage()" not in dispatcher
assert "persistAttemptSafely(" in dispatcher
assert "use InvalidArgumentException;" in dispatcher
assert "FCM dispatch limit must be between 1 and 50." in dispatcher
assert "if ($limit !== null && ($limit < 1 || $limit > 50))" in dispatcher
assert "catch (\\Throwable)" in dispatcher
assert "Logging must not turn a recoverable ledger failure into a batch abort." in dispatcher
assert "permanent_failure" in dispatcher
assert "TRANSPORT_FAILURE" in dispatcher
assert "'TOKEN_CLEANUP_FAILURE'" in dispatcher
assert "logCleanupFailureSafely" in dispatcher
assert "FCM invalid-token cleanup failed for attempt." in dispatcher
assert "Do not misclassify a PostgREST cleanup failure as an FCM transport failure." in dispatcher
assert "catch (\\Throwable $cleanupException)" in dispatcher
assert "catch (\\Throwable)" in dispatcher
assert "tacresq:dispatch-fcm" in command
assert "--check-config" in command
assert "FILTER_VALIDATE_INT" in command
assert "$parsedLimit < 1 || $parsedLimit > 50" in command
assert "The --limit option must be an integer between 1 and 50." in command
assert "return self::INVALID;" in command
assert "checkConfiguration" in dispatcher
assert dispatcher.index("$this->checkConfiguration();") < dispatcher.index("api_claim_fcm_delivery_batch")
assert "assertCredentialsAvailable" in transport
assert "notification_id" in base_migration and "token" in base_migration
assert "UNIQUE (notification_id, token)" in base_migration
assert "REVOKE ALL ON TABLE public.fcm_delivery_attempts" in base_migration
assert "ADD COLUMN IF NOT EXISTS attempt_count" in queue_migration
assert "ADD COLUMN IF NOT EXISTS next_attempt_at" in queue_migration
assert "ON CONFLICT ON CONSTRAINT fcm_delivery_attempts_notification_id_token_key DO UPDATE" in queue_migration
assert "p_limit > 50" in queue_migration
assert "existing.attempt_count < 8" in queue_migration
assert "existing.next_attempt_at <= statement_timestamp()" in queue_migration
assert "WHERE n.read_at IS NULL" in queue_migration
assert "GRANT EXECUTE ON FUNCTION public.api_claim_fcm_delivery_batch(integer, integer)" in queue_migration
assert "TO service_role" in queue_migration
assert "FROM PUBLIC, anon, authenticated" in queue_migration
assert "permanent_failure" in queue_migration
assert "service_role" in queue_test and "authenticated" in queue_test
assert "A second worker claim must not reclaim a live lease" in queue_test
assert "A failed attempt must respect next_attempt_at backoff" in queue_test
assert "Attempts at the retry ceiling must not be claimed again" in queue_test
assert "Only an expired lease below the retry ceiling must be reclaimed" in queue_test
assert "A reclaimed lease must be fresh and the exhausted lease must stay excluded" in queue_test
assert "read_at" in queue_test and "Already read notification" in queue_test
assert "typed `google.firebase.fcm.v1.FcmError` detail" in docs
assert "typed `google.rpc.BadRequest` field-violation detail" in docs
assert "SUPABASE_SECRET_KEY" in docs
assert "Do not expose this command" in docs
assert "--check-config" in docs
assert "at-least-once, not exactly-once" in docs
assert "stable notification identifier" in docs

dispatcher_tests = read("backend/src/tests/Feature/FcmNotificationDispatcherTest.php")
assert "array_is_list($claimedAttempts)" in dispatcher
assert "foreach ($claimedAttempts as $attempt)" in dispatcher
assert "if (!is_array($attempt))" in dispatcher
assert "'tokens' => count($claimedAttempts)" in dispatcher
assert "test_object_claim_payload_is_rejected_as_not_a_delivery_list" in dispatcher_tests
assert "test_non_array_claim_row_is_counted_as_skipped_without_blocking_valid_rows" in dispatcher_tests
assert "test_invalid_explicit_limit_is_rejected_before_queue_claim" in dispatcher_tests
assert "test_successful_send_is_written_to_delivery_ledger" in dispatcher_tests
assert "test_ledger_failure_for_one_attempt_does_not_stop_later_recipients" in dispatcher_tests
assert "test_invalid_token_cleanup_failure_is_recorded_as_retryable_cleanup_error" in dispatcher_tests
assert "test_malformed_claimed_attempt_is_skipped_without_sending" in dispatcher_tests
assert "test_invalid_token_is_marked_only_after_successful_cleanup" in dispatcher_tests
assert "test_retryable_provider_result_is_persisted_as_failed_with_backoff" in dispatcher_tests
assert "test_missing_server_configuration_fails_before_queue_claim" in dispatcher_tests
assert "test_claim_service_failure_aborts_before_any_send" in dispatcher_tests
assert "test_configured_batch_limit_is_clamped_to_database_cap" in dispatcher_tests
assert "test_permanent_provider_failure_is_not_treated_as_invalid_token" in dispatcher_tests
assert "test_send_exception_is_recorded_and_later_recipient_is_processed" in dispatcher_tests
assert "test_invalid_token_cleanup_failure_does_not_stop_next_recipient" in dispatcher_tests
assert "test_missing_fcm_project_id_fails_before_queue_claim" in dispatcher_tests
assert "test_non_array_claim_response_is_rejected_before_send" in dispatcher_tests
assert "test_empty_queue_returns_zero_counts_without_sending" in dispatcher_tests
assert "test_cleanup_failure_log_contains_only_attempt_and_exception_class" in dispatcher_tests
# The trusted sender must not expose a public route or use a client-visible key.
assert "Route::post('/internal/push" not in read("backend/src/routes/api.php")
assert "supabase_publishable_key" not in dispatcher

# Each push carries its intended profile ID so Android can discard stale pushes
# that arrive after this installation has switched accounts.
assert "$profileId = (int) ($attempt['profile_id'] ?? 0);" in dispatcher
assert "$profileId < 1" in dispatcher
assert "'recipient_profile_id' => (string) $profileId" in dispatcher

# The recovered tacresq-notify Edge Function is a legacy direct-send path.
# Keep app/web clients on the durable inbox + canonical delivery ledger so
# the same event cannot bypass deduplication, leases, and retry accounting.
for source_root in (ROOT / "app/src", ROOT / "backend/web/src", ROOT / "backend/src"):
    for source_file in source_root.rglob("*"):
        if not source_file.is_file() or source_file.suffix.lower() not in {
            ".kt", ".kts", ".java", ".ts", ".tsx", ".js", ".jsx", ".php",
        }:
            continue
        source_text = source_file.read_text(encoding="utf-8", errors="ignore").lower()
        assert "tacresq-notify" not in source_text, (
            f"Legacy direct-send Edge Function must not be called by app/web/backend source: {source_file}"
        )

print("Trusted FCM server and legacy Edge Function boundary contract passed.")
