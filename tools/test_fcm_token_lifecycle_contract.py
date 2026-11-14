#!/usr/bin/env python3
"""Offline contract checks for safe FCM registration lifecycle and account switching."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
coordinator = (ROOT / "app/src/main/java/com/tacresq/app/notifications/PushTokenCoordinator.kt").read_text(encoding="utf-8")
app_root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text(encoding="utf-8")
migration = (ROOT / "supabase/migrations/20261114170000_fcm_token_account_switch.sql").read_text(encoding="utf-8")
registration_contract = (ROOT / "tools/test_fcm_registration_contract.py").read_text(encoding="utf-8")

assert "private val operations = Mutex()" in coordinator
assert "operations.withLock" in coordinator
assert "registrationGeneration" in coordinator
assert "expectedGeneration != registrationGeneration" in coordinator
assert "registrationEnabled = false" in coordinator
assert "linkedSetOf(" in coordinator
assert "KEY_REGISTERED_TOKEN" in coordinator and "KEY_PENDING_TOKEN" in coordinator
assert "cleanupFailed" in coordinator
# TacRepository wraps both token operations in Result<Unit>. Verify cleanup checks
# the Result value rather than relying on exceptions that the repository does not throw.
assert "repository.unregisterFcmToken(previouslyRegistered).isSuccess" in coordinator
assert "repository.unregisterFcmToken(token).isFailure" in coordinator
assert "if (previousTokenRemoved)" in coordinator
assert "repository.registerFcmToken(token)" in coordinator
assert "ON CONFLICT (token) DO UPDATE" in migration
assert "SET search_path = pg_catalog" in migration
assert "SECURITY DEFINER" in migration
assert "auth.uid()" in migration
assert "REVOKE ALL ON FUNCTION public.api_register_fcm_token(text)" in migration
assert "GRANT EXECUTE ON FUNCTION public.api_register_fcm_token(text)" in migration
assert "registrationEnabled" in coordinator
assert "pushTokenCoordinator.unregister" in app_root
assert "pushTokenCoordinator.sync" in app_root
assert "NotificationManagerCompat.from(context).cancelAll()" in app_root
assert app_root.index("container.repository.logout()") < app_root.index("NotificationManagerCompat.from(context).cancelAll()")
print("FCM token lifecycle contract passed.")
