#!/usr/bin/env python3
"""Offline contract test for client-side FCM registration wiring."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


build = read("app/build.gradle.kts")
manifest = read("app/src/main/AndroidManifest.xml")
api = read("app/src/main/java/com/tacresq/app/data/remote/TacApi.kt")
supabase = read("app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt")
repository = read("app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt")
coordinator = read("app/src/main/java/com/tacresq/app/notifications/PushTokenCoordinator.kt")
notifications_screen = read("app/src/main/java/com/tacresq/app/ui/notifications/NotificationsScreen.kt")
service = read("app/src/main/java/com/tacresq/app/notifications/TacResqFirebaseMessagingService.kt")
recipient_policy = read("app/src/main/java/com/tacresq/app/notifications/PushNotificationRecipientPolicy.kt")
app_root = read("app/src/main/java/com/tacresq/app/ui/AppRoot.kt")

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
logout_start = app_root.index("val logout: () -> Unit")
logout_end = app_root.index("LaunchedEffect(sessionState)", logout_start)
logout_flow = app_root[logout_start:logout_end]
assert logout_flow.index("pushTokenCoordinator.unregister") < logout_flow.index("container.repository.logout()")
admin_gate = app_root.index("if (loggedInState.session.user.role == Role.ADMIN)")
admin_gate_end = app_root.index("container.pushTokenCoordinator.sync", admin_gate)
admin_gate_body = app_root[admin_gate:admin_gate_end]
assert "logout()" in admin_gate_body
assert "return@LaunchedEffect" in admin_gate_body
assert "RequestPermission()" in app_root
assert "POST_NOTIFICATIONS" in app_root
assert "AlertDialog(" in app_root
assert "Stay informed about incident updates" in app_root
assert "Your in-app inbox remains available if you skip." in app_root
assert "notificationPermissionPreferences.getBoolean(NOTIFICATION_PERMISSION_PROMPT_SHOWN, false)" in app_root
assert "putBoolean(NOTIFICATION_PERMISSION_PROMPT_SHOWN, true)" in app_root
permission_effect_start = app_root.index("LaunchedEffect(sessionState)")
permission_effect_end = app_root.index("Surface(modifier = Modifier.fillMaxSize()", permission_effect_start)
permission_effect = app_root[permission_effect_start:permission_effect_end]
assert "notificationPermissionLauncher.launch" not in permission_effect, "Explain the optional permission before opening Android's prompt."
assert "pushTokenCoordinator.sync(container.repository)" in permission_effect
assert permission_effect.index("pushTokenCoordinator.sync(container.repository)") < permission_effect.index("showNotificationPermissionRationale = true")
assert "Settings.ACTION_APP_NOTIFICATION_SETTINGS" in notifications_screen
assert "Manage Android notification settings" in notifications_screen

# Push delivery is account-scoped. The service waits for session restoration and
# fails closed when the intended profile does not match the active session.
assert 'message.data["recipient_profile_id"]' in service
assert "sessionState.first { it !is SessionState.Loading }" in service
assert "withTimeoutOrNull(SESSION_RESTORE_TIMEOUT_MILLIS)" in service
assert "PushNotificationRecipientPolicy.shouldDisplay(recipientProfileId, sessionState)" in service
assert "currentProfileId == expectedProfileId" in recipient_policy
assert "SessionState.LoggedOut" not in recipient_policy  # non-LoggedIn states are rejected by the safe cast

# Alert content must remain generic; incident details are fetched after authentication.
assert '"Open TACResQ to review a new incident update."' in service
assert 'message.data["description"]' not in service
print("FCM registration contract passed.")
