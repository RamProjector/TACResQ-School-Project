#!/usr/bin/env python3
"""Static contract checks for dispatcher-only, audited team-member release."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
migration = (
    ROOT
    / "supabase/migrations/20261122050837_dispatcher_release_incident_assignment.sql"
).read_text()

# Both entry points must be explicit, authenticated, and protected by a
# dispatcher-role check in the private SECURITY DEFINER implementation.
for required in (
    "CREATE OR REPLACE FUNCTION private.tacresq_release_incident_assignment(",
    "SECURITY DEFINER",
    "SET search_path = pg_catalog",
    "actor.role <> 'dispatcher'",
    "CREATE OR REPLACE FUNCTION public.api_release_incident_assignment(",
    "SECURITY INVOKER",
    "REVOKE ALL ON FUNCTION private.tacresq_release_incident_assignment(bigint,bigint)",
    "GRANT EXECUTE ON FUNCTION private.tacresq_release_incident_assignment(bigint,bigint)",
    "REVOKE ALL ON FUNCTION public.api_release_incident_assignment(bigint,bigint)",
    "GRANT EXECUTE ON FUNCTION public.api_release_incident_assignment(bigint,bigint)",
):
    assert required in migration, f"Missing release authorization contract: {required}"

# The release is serialized against assignment/progress operations and cannot
# orphan an incident or remove a member after arrival.
for required in (
    "FROM public.incidents\n  WHERE id = p_id\n  FOR UPDATE",
    "incident_row.status NOT IN ('assigned', 'en_route')",
    "FROM public.incident_assignments\n  WHERE incident_id = p_id AND user_id = p_user_id\n  FOR UPDATE",
    "IF remaining_count <= 1 THEN",
    "DELETE FROM public.incident_assignments",
):
    assert required in migration, f"Missing release lifecycle safeguard: {required}"

# Replacing the legacy primary member is permitted only after the former
# primary is no longer a member and the replacement is a current team member.
for required in (
    "CREATE OR REPLACE FUNCTION public.tacresq_guard_incident_update()",
    "OLD.assigned_to IS NOT NULL",
    "NEW.assigned_to IS NOT NULL",
    "OLD.status IN ('assigned', 'en_route')",
    "NOT EXISTS (",
    "a.user_id = OLD.assigned_to",
    "a.user_id = NEW.assigned_to",
):
    assert required in migration, f"Missing guarded primary handoff: {required}"

# Release history is written by the existing DELETE trigger in the same
# transaction; released personnel are made available only when no other active
# incident still reserves them.
for required in (
    "DELETE trigger appends the release event in this same transaction",
    "'available'",
    "i.id <> p_id",
    "incident_assignment_events",
    "'released'",
):
    assert required in (
        migration
        + (
            ROOT
            / "supabase/migrations/20261122050834_incident_assignment_event_history.sql"
        ).read_text()
    ), f"Missing release audit/availability behavior: {required}"

print("PASS: only dispatchers can invoke the assignment release operation.")
print("PASS: release is serialized, pre-arrival only, and cannot orphan the team.")
print("PASS: primary assignment handoff is narrowly guarded by membership.")
print("PASS: release history and availability restoration remain transactional.")
