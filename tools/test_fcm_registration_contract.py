#!/usr/bin/env python3
"""Offline contract test for client-side FCM registration wiring."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/TacApi.kt").read_text(encoding="utf-8")
supabase = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text(encoding="utf-8")
repository = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text(encoding="utf-8")
coordinator = (ROOT / "app/src/main/java/com/tacresq/app/notifications/PushTokenCoordinator.kt").read_text(encoding="utf-8")
service = (ROOT / "app/src/main/java/com/tacresq/app/notifications/TacResqFirebaseMessagingService.kt").read_text(encoding="utf-8")
app_root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text(encoding="utf-8")

assert 'com.google.firebase:firebase-messaging:' in build
assert "interface TacPushApi" in api
assert "api_register_fcm_token" in supabase
assert "api_unregister_fcm_token" in supabase
assert "registerFcmToken" in repository and "unregisterFcmToken" in repository
assert "FirebaseApp.getApps" in coordinator
assert "notificationsAllowed" in coordinator
assert "onTokenRefreshed" in service
assert "com.google.firebase.MESSAGING_EVENT" in manifest
assert "POST_NOTIFICATIONS" in manifest
assert "pushTokenCoordinator.sync" in app_root
assert "pushTokenCoordinator.unregister" in app_root
assert "RequestPermission()" in app_root
assert "POST_NOTIFICATIONS" in app_root
# Alert content must remain generic; incident details are fetched after authentication.
assert '"Open TACResQ to review a new incident update."' in service
assert 'message.data["description"]' not in service
print("FCM registration contract passed.")
