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
migration = read("supabase/migrations/20261105235016_fcm_delivery_attempts.sql")
docs = read("docs/FCM_SETUP.md")

assert "ApplicationDefaultCredentials::getCredentials" in transport
assert "https://www.googleapis.com/auth/firebase.messaging" in transport
assert "fcm.googleapis.com/v1/projects/" in transport
assert "['message' => ['token' => $token, 'data' => $data]]" in transport
assert "error.status" in transport
assert "UNREGISTERED" in transport and "INVALID_ARGUMENT" in transport
assert "supabase_secret_key" in config
assert "fcm_project_id" in config
assert "SUPABASE_SECRET_KEY" in env
assert "GOOGLE_APPLICATION_CREDENTIALS" in env
assert "google/auth:^1.48" in dockerfile
assert "user_notifications" in dispatcher
assert "fcm_tokens" in dispatcher
assert "fcm_delivery_attempts" in dispatcher
assert "resolution=ignore-duplicates" in dispatcher
assert "deleteToken" in dispatcher
assert "tacresq:dispatch-fcm" in command
assert "--check-config" in command
assert "checkConfiguration" in dispatcher
assert "assertCredentialsAvailable" in transport
assert "notification_id" in migration and "token" in migration
assert "UNIQUE (notification_id, token)" in migration
assert "REVOKE ALL ON TABLE public.fcm_delivery_attempts" in migration
assert "SUPABASE_SECRET_KEY" in docs
assert "Do not expose this command" in docs
assert "--check-config" in docs
# The trusted sender must not expose a public route or use a client-visible key.
assert "Route::post('/internal/push" not in read("backend/src/routes/api.php")
assert "supabase_publishable_key" not in dispatcher
print("Trusted FCM server contract passed.")
