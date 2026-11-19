#!/usr/bin/env python3
"""Static safety contract for the dependency-health monitoring plan."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/security/dependency-health-monitoring-research-and-implementation-plan.md"
text = DOC.read_text(encoding="utf-8")
lower = text.lower()

required = (
    "separate liveness from dependency readiness",
    "never create or mutate emergency data",
    "read-only, bounded probes",
    "do not expose topology or secrets publicly",
    "supabase_auth",
    "supabase_rpc",
    "healthy",
    "degraded",
    "unavailable",
    "disabled",
    "not_configured",
    "/auth/v1/health",
    "dedicated, narrowly granted read-only health rpc",
    "without sending a message",
    "fixed, non-sensitive synthetic coordinates",
    "no render environment variables, secrets, supabase settings, cron schedules, or production services are changed",
    "must not block requester sos submission",
)
for phrase in required:
    assert phrase in lower, f"Missing dependency-monitoring safety requirement: {phrase}"

# Keep the plan honest: these are proposed phases, not an active production monitor.
for phrase in (
    "no independent dependency monitor",
    "scheduled probe",
    "alert destination",
    "service-level objective",
):
    assert phrase in lower, f"Missing explicit not-yet-implemented boundary: {phrase}"

print("PASS: dependency monitoring plan preserves read-only probes, privacy, bounded behavior, and deployment boundaries.")
