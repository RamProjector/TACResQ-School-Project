from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

def check(label: str, passed: bool) -> bool:
    print(f"{'PASS' if passed else 'FAIL'} {label}")
    return passed

transport = read("app/src/main/java/com/tacresq/app/data/realtime/SupabaseNotificationRealtime.kt")
contract = read("app/src/main/java/com/tacresq/app/data/realtime/NotificationRealtime.kt")
noop = read("app/src/main/java/com/tacresq/app/data/realtime/NoopNotificationRealtime.kt")
migration = read("supabase/migrations/20261107234752_notification_realtime_broadcast.sql")
container = read("app/src/main/java/com/tacresq/app/AppContainer.kt")
factory = read("app/src/main/java/com/tacresq/app/ui/VmFactory.kt")
screen = read("app/src/main/java/com/tacresq/app/ui/notifications/NotificationsScreen.kt")

checks = [
    check("transport uses authenticated Supabase WebSocket", all(token in transport for token in (
        "realtime/v1/websocket", "SUPABASE_PUBLISHABLE_KEY", "access_token", "phx_join",
    ))),
    check("transport joins a private profile topic", "addProperty(\"private\", true)" in transport and "realtime:$topic" in transport),
    check("transport has explicit lifecycle and reconnect states", all(token in contract for token in (
        "UNAVAILABLE", "CONNECTING", "CONNECTED", "RECONNECTING", "FALLBACK_SYNC", "ERROR",
    )) and all(token in transport for token in ("fun start()", "fun stop()", "scheduleReconnect"))),
    check("synchronous socket setup failures fall back without escaping screen entry", all(token in transport for token in (
        "connectSafely(isReconnect = false)",
        "private fun connectSafely(isReconnect: Boolean)",
        "catch (_: IllegalArgumentException)",
        "RealtimeConnectionState.FALLBACK_SYNC",
        "connectSafely(isReconnect = true)",
        "reconnectJob = null",
    ))),
    check("OkHttp WebSocket URL retains a supported HTTP(S) scheme", (
        "AppConfig.SUPABASE_URL.toHttpUrl().newBuilder()" in transport
        and '.scheme("wss")' not in transport
        and "client.newWebSocket(" in transport
    )),
    check("transport emits only allowlisted notification hints", all(token in transport for token in (
        "notification_id", "incident_assigned", "incident_status_changed", "notification_created",
    )) and "user_notifications" not in transport),
    check("database broadcast payload is minimized", all(token in migration for token in (
        "realtime.send", "notification_id", "event_type", "true",
    )) and "'title', NEW" not in migration and "'body', NEW" not in migration),
    check("database topic authorization is profile scoped", all(token in migration for token in (
        "realtime.topic()", "TO authenticated", "p.auth_user_id = auth.uid()",
    ))),
    check("migration does not require local realtime schema", "to_regclass('realtime.messages')" in migration and "to_regprocedure('realtime.send" in migration),
    check("app wires live and demo-safe transports", all(token in container for token in (
        "SupabaseNotificationRealtime", "NoopNotificationRealtime", "notificationRealtime",
    )) and "container.notificationRealtime" in factory),
    check("notification screen reconciles hints through the existing refresh", all(token in screen for token in (
        "notificationHints.collectLatest", "vm.refresh()", "delay(30_000)", "vm.startRealtime()", "vm.stopRealtime()",
    ))),
]
if not all(checks):
    raise SystemExit(1)
print(f"Notification realtime contract OK: {len(checks)} checks.")
