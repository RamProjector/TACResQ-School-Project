#!/usr/bin/env python3
"""Offline contract checks for the trusted, server-only FCM sender."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


transport = read("backend/src/app/FcmHttpV1Client.php")
dispatcher = read("backend/src/app/FcmNotificationDispatcher.php")
command = read("backend/src/app/Console/Commands/DispatchFcmNotifications.php")
config = read("backend/src/config/tacru.php")
dockerfile = read("backend/Dockerfile")
env = read("backend/.env.example")
base_migration = read("supabase/migrations/20261105235016_fcm_delivery_attempts.sql")
queue_migration = read("supabase/migrations/20261114135000_fcm_delivery_queue_hardening.sql")
queue_test = read("tools/test_fcm_delivery_queue.sql")
docs = read("docs/FCM_SETUP.md")

assert "ApplicationDefaultCredentials::getCredentials" in transport
assert "https://www.googleapis.com/auth/firebase.messaging" in transport
assert "fcm.googleapis.com/v1/projects/" in transport
assert "['message' => ['token' => $token, 'data' => $data]]" in transport
assert "error.status" in transport
assert "'invalid_token' => $errorCode === 'UNREGISTERED'" in transport
assert "'retryable' => $retryable" in transport
assert "Retry-After" in transport and "retryAfterSeconds" in transport
assert "INVALID_ARGUMENT" in transport
assert "supabase_secret_key" in config
assert "fcm_project_id" in config
assert "SUPABASE_SECRET_KEY" in env
assert "GOOGLE_APPLICATION_CREDENTIALS" in env
assert "google/auth:^1.48" in dockerfile
assert "api_claim_fcm_delivery_batch" in dispatcher
assert "p_lease_seconds" in dispatcher
assert "min($limit ?? (int) config('tacru.fcm_batch_limit', 50), 50)" in dispatcher
assert "fcm_delivery_attempts" in dispatcher
assert "next_attempt_at" in dispatcher
assert "attempt_count" in dispatcher
assert "read_at" not in dispatcher
assert "claimAttempt" not in dispatcher
assert "resolution=ignore-duplicates" not in dispatcher
assert "http_build_query" in dispatcher
assert "PHP_QUERY_RFC3986" in dispatcher
assert "$this->tableUrl('fcm_tokens').'?'.$query" in dispatcher
assert "$this->client()->delete($this->tableUrl('fcm_tokens'), [" not in dispatcher
assert "permanent_failure" in dispatcher
assert "tacresq:dispatch-fcm" in command
assert "--check-config" in command
assert "checkConfiguration" in dispatcher
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
assert "INVALID_ARGUMENT` is treated as a payload/request error" in docs
assert "SUPABASE_SECRET_KEY" in docs
assert "Do not expose this command" in docs
assert "--check-config" in docs
# The trusted sender must not expose a public route or use a client-visible key.
assert "Route::post('/internal/push" not in read("backend/src/routes/api.php")
assert "supabase_publishable_key" not in dispatcher
print("Trusted FCM server contract passed.")
