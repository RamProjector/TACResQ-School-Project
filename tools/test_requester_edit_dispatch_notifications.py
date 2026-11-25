from pathlib import Path

migration = Path(
    "supabase/migrations/20261122050832_dispatch_request_edit_notifications.sql"
).read_text(encoding="utf-8").lower()

checks = {
    "notification is created from the durable requester edit log": (
        "after insert on public.incident_request_edit_logs" in migration
        and "new.incident_id" in migration
        and "new.id" in migration
    ),
    "only dispatcher profiles receive the correction alert": (
        "where dispatcher.role = 'dispatcher'" in migration
        and "dispatcher.id is distinct from new.requester_id" in migration
    ),
    "the notification event is included in the constrained event type set": (
        "'incident_request_updated'" in migration
        and "user_notifications_event_type_check" in migration
    ),
    "each dispatcher receives at most one alert for a given edit event": (
        "user_notifications_request_edit_event_unique_idx" in migration
        and "on conflict (recipient_user_id, source_event_id)" in migration
        and "do nothing" in migration
    ),
    "notification creation is performed by a locked-down trigger function": (
        "security definer" in migration
        and "set search_path = pg_catalog" in migration
        and "revoke all on function private.tacresq_notify_requester_edit()" in migration
    ),
    "push and inbox copy do not embed sensitive incident details": (
        "'a requester corrected an emergency report. open the dispatch queue to review the latest details.'"
        in migration
        and "new.description" not in migration
        and "new.after_values" not in migration
        and "new.latitude" not in migration
        and "new.longitude" not in migration
    ),
}

for description, passed in checks.items():
    if not passed:
        raise AssertionError(description)

print(f"Requester edit dispatch notification contract passed ({len(checks)} checks).")


cancellation_migration = Path(
    "supabase/migrations/20261122050851_notify_dispatcher_of_requester_cancellation.sql"
).read_text(encoding="utf-8").lower()

cancellation_checks = {
    "cancellation is a constrained notification event": (
        "'incident_cancelled'" in cancellation_migration
        and "user_notifications_event_type_check" in cancellation_migration
    ),
    "only dispatcher accounts receive the cancellation alert": (
        "dispatcher.role = 'dispatcher'" in cancellation_migration
        and "dispatcher.id is distinct from new.changed_by" in cancellation_migration
    ),
    "one durable alert is allowed per dispatcher and cancelled incident": (
        "user_notifications_one_cancellation_alert_idx" in cancellation_migration
        and "on conflict (recipient_user_id, incident_id)" in cancellation_migration
    ),
    "cancellation alert is generic and contains no incident coordinates/details": (
        "'a requester cancelled a pending emergency. open the dispatch queue to review the updated queue.'" in cancellation_migration
        and "new.latitude" not in cancellation_migration
        and "new.longitude" not in cancellation_migration
        and "new.description" not in cancellation_migration
    ),
    "existing status notifications remain available for other transitions": (
        "incident_status_changed" in cancellation_migration
        and "notification_body := 'the incident status is now '" in cancellation_migration
    ),
}

for description, passed in cancellation_checks.items():
    if not passed:
        raise AssertionError(description)

print(f"Requester cancellation notification contract passed ({len(cancellation_checks)} checks).")
