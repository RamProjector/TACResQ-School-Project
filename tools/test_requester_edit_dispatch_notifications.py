from pathlib import Path

migration = Path(
    "supabase/migrations/20261118003000_dispatch_request_edit_notifications.sql"
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
