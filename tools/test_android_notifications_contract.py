#!/usr/bin/env python3
"""Static contract checks for the Android in-app notification inbox."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


models = read("app/src/main/java/com/tacresq/app/data/model/Models.kt")
for required in (
    "data class UserNotification(",
    '@SerializedName("incident_id") val incidentId: Long?',
    '@SerializedName("event_type") val eventType: String',
    '@SerializedName("created_at") val createdAt: String?',
    '@SerializedName("read_at") val readAt: String?',
):
    assert required in models, f"Notification model missing field contract: {required}"

api = read("app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt")
for required in (
    "TacApi, TacNotificationApi",
    'rest/v1/rpc/api_my_notifications',
    'rest/v1/rpc/api_mark_notification_read',
    "NotificationsLimitRpc(limit)",
    "MarkNotificationReadRpc(notificationId)",
    "limit in 1..100",
):
    assert required in api, f"Supabase notification RPC contract missing: {required}"

notification_contract = read("app/src/main/java/com/tacresq/app/data/repo/NotificationRepository.kt")
for required in (
    "suspend fun myNotifications(limit: Int = 50)",
    "suspend fun markNotificationRead(notificationId: Long)",
):
    assert required in notification_contract, f"Notification interface contract missing: {required}"

repository = read("app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt")
for required in (
    "override suspend fun myNotifications(limit: Int)",
    "override suspend fun markNotificationRead(notificationId: Long)",
    "api as? TacNotificationApi",
    "Notification page size must be between 1 and 100.",
):
    assert required in repository, f"Notification repository contract missing: {required}"

fake = read("app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt")
for required in (
    "TacApi, TacNotificationApi",
    "override suspend fun myNotifications(limit: Int)",
    "it.recipientId == me.id",
    "it.value.id == notificationId && it.recipientId == me.id",
    "emitAssignmentNotifications(id, body.userIds)",
    "emitStatusNotifications(result, me.id)",
):
    assert required in fake, f"Demo notification behavior missing: {required}"

screen = read("app/src/main/java/com/tacresq/app/ui/notifications/NotificationsScreen.kt")
for required in (
    "class NotificationsViewModel",
    "collectAsStateWithLifecycle()",
    "repeatOnLifecycle(Lifecycle.State.STARTED)",
    "delay(30_000)",
    "catch (cancelled: kotlinx.coroutines.CancellationException)",
    "catch (_: Exception)",
    "Could not load notifications. Try again.",
    "LazyColumn(",
    "items(state.items, key = { it.id })",
    "Mark as read",
    "Open incident",
    "Open conversation",
    "Notifications unavailable",
    "All caught up",
    "Loading your notifications",
    "$unreadCount unread",
    "Try again",
):
    assert required in screen, f"Notification UI/lifecycle contract missing: {required}"

assert screen.count("catch (cancelled: kotlinx.coroutines.CancellationException)") >= 2, (
    "Both notification refresh and mark-read actions must preserve coroutine cancellation."
)
assert screen.count("catch (_: Exception)") >= 2, (
    "Both notification refresh and mark-read actions must handle unexpected repository exceptions."
)

root = read("app/src/main/java/com/tacresq/app/ui/AppRoot.kt")
requester = read("app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt")
responder = read("app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt")
dispatcher = read("app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt")
for required in (
    "NotificationsViewModel",
    "NotificationsAndSafetyScreen(notificationsVm, user, logout, onOpenIncident, onOpenChat)",
    "destination == AppDestination.ALERTS",
    "destination = AppDestination.CHAT",
    "focusedIncidentId = selectedIncidentId",
    "IncidentHistoryAdaptiveScaffold(",
    "LaunchedEffect(selectedIncidentId)",
    "navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedIncidentId)",
    "navigator.currentDestination?.contentKey",
    "Incident not in the current list",
    "vm.refresh(); openIncident(incidentId)",
    "onRefreshIncidents = vm::refresh",
):
    assert required in root, f"Notification screen routing missing: {required}"

shell = read("app/src/main/java/com/tacresq/app/ui/RoleShell.kt")
assert 'MAP("GPS Map")' in shell, "GPS Map must remain a top-level destination"
assert "GpsMapNavigationItem(selected, onDestinationSelected)" in shell
assert shell.count("NavigationItem(destinations[") >= 4, "The four non-map destinations must remain"
assert "GPS Map" in shell, "GPS Map must remain visible in the central navigation item"
assert 'ALERTS("Notifications")' in shell, "notifications remain available as a dedicated top-app-bar destination"
assert 'ScreenHeader(title = "Notifications")' in screen, "notifications must have a focused notification-center title"
profile = root
profile_start = profile.index("destination == AppDestination.PROFILE -> {")
profile_section = profile[profile_start:]
assert 'Text("Safety & privacy"' in profile_section, "safety guidance must be available under Profile / settings"
for safety_item in ("Emergency help", "Confirm your location", "Location sharing", "Personal information"):
    assert safety_item in profile_section, f"Profile safety guidance missing: {safety_item}"

components = read("app/src/main/java/com/tacresq/app/ui/common/Components.kt")
assert "fun ScreenHeader(title: String, subtitle: String = \"\", onOpenNotifications: (() -> Unit)? = null)" in components
assert "Text(\"Sign out\")" not in components, "sign-out must not appear in the shared screen header"
app_root = read("app/src/main/java/com/tacresq/app/ui/AppRoot.kt")
assert 'Text("Sign out")' in app_root and 'Text("Cancel")' in app_root
assert 'Text("Your emergency requests will remain active.' in app_root, "logout confirmation must explain active-request behavior"
assert "showSignOutConfirmation = true" in app_root

assert "onOpenNotifications: (() -> Unit)? = null" in components
assert 'contentDescription = "Open notifications"' in components
assert "val openNotifications: () -> Unit = {" in app_root
assert "selectedChatIncidentId = null" in app_root, "Opening Alerts must clear the selected chat route"
assert app_root.count("onOpenNotifications = { chatVm.closeConversation(); openNotifications() }") == 3, (
    "Requester, responder/driver, and dispatcher chats must close their conversation before opening Alerts"
)
assert "onOpenNotifications = openNotifications" in app_root
assert "onOpenNotifications = onOpenNotifications" in requester
assert "onOpenNotifications = onOpenNotifications" in responder
assert "onOpenNotifications = onOpenNotifications" in dispatcher
assert "onOpenNotifications = onOpenNotifications" in read("app/src/main/java/com/tacresq/app/ui/chat/IncidentChatScreen.kt")
assert root.count("onOpenNotifications = openNotifications") >= 3, "requester, responder/driver, and dispatcher incident chats must expose the notification action"


# Client code must consume the protected RPCs, not read/write the private table directly.
for path in (
    "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt",
    "app/src/main/java/com/tacresq/app/ui/notifications/NotificationsScreen.kt",
):
    source = read(path)
    assert "user_notifications" not in source, f"{path} must not directly access the private table"

dispatcher = read("app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt")
for required in (
    "focusedIncidentId: Long? = null",
    "Opened from alert",
    "current authorized dispatch queue",
):
    assert required in dispatcher, f"Dispatcher alert target handling missing: {required}"

behavior_tests = read("app/src/test/java/com/tacresq/app/ui/notifications/NotificationsViewModelTest.kt")
for required in (
    "refreshThrownExceptionUsesGenericErrorAndClearsProgress",
    "refreshResultFailurePreservesRepositoryMessageAndClearsProgress",
    "refreshCancellationPropagatesWithoutShowingErrorAndClearsProgress",
    "markReadThrownExceptionUsesGenericErrorAndClearsBusyId",
    "markReadResultFailurePreservesRepositoryMessageAndClearsBusyId",
    "markReadCancellationPropagatesWithoutShowingErrorAndClearsBusyId",
):
    assert required in behavior_tests, f"Focused notification ViewModel behavior test missing: {required}"

print("PASS: Android notifications use caller-scoped RPCs, recipient-filtered demo data, lifecycle-aware polling, explicit loading/empty/error/retry states, and preserve GPS Map navigation.")
