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
for required in (
    "NotificationsViewModel",
    "NotificationsAndSafetyScreen(notificationsVm, user, logout, onOpenIncident, onOpenChat)",
    "destination == AppDestination.ALERTS",
    "destination = if (role == Role.DISPATCHER) AppDestination.PRIMARY else AppDestination.SECONDARY",
    "focusedIncidentId = selectedIncidentId",
    "incidents.firstOrNull { it.id == targetId }",
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
assert 'ALERTS("Alerts")' in shell, "notifications must be discoverable from the Alerts destination"
assert 'ScreenHeader(title = "Alerts & safety"' in screen, "the destination must preserve its safety guidance context"

components = read("app/src/main/java/com/tacresq/app/ui/common/Components.kt")
assert "fun ScreenHeader(title: String, subtitle: String = \"\")" in components
assert "Text(\"Sign out\")" not in components, "sign-out must not appear in the shared screen header"
app_root = read("app/src/main/java/com/tacresq/app/ui/AppRoot.kt")
assert 'Text("Sign out")' in app_root and 'Text("Cancel")' in app_root
assert 'Text("Your emergency requests will remain active.' in app_root, "logout confirmation must explain active-request behavior"
assert "showSignOutConfirmation = true" in app_root


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
