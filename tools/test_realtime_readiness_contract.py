from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
research = (ROOT / "docs/RESEARCH_REALTIME_RELIABILITY.md").read_text()
status = (ROOT / "docs/PHASE3_REALTIME_STATUS.md").read_text()
plan = (ROOT / "plan.md").read_text()

def check(label, passed):
    print(f"{'PASS' if passed else 'FAIL'} {label}")
    return passed

checks = [
    check("phase 3 preserves RPC fallback", "authenticated RPC reads" in research and "fallback_sync" in research),
    check("phase 3 requires authenticated scoped transport", "authenticated user" in research and "authorized incident" in research),
    check("phase 3 protects sensitive payloads", "precise location" in research and "phone number" in research),
    check("phase 3 requires reconnect reconciliation", "authoritative RPC refresh" in research),
    check("phase 3 defines non-live connection states", all(state in research for state in (
        "unavailable", "connecting", "connected", "reconnecting", "fallback_sync", "error",
    ))),
    check("phase 3 defers location streaming", "Do not enable responder-location streaming" in research),
    check("phase 3 does not claim live completion", "live Supabase and device gates remain open" in research and "does not claim live Supabase authorization" in status),
    check("phase 3 has synthetic duplicate/order/revocation coverage", "duplicate and out-of-order hints" in research and "revoked-membership" in research and "NotificationsViewModelTest" in status),
    check("project plan still requires polling fallback", "Keep the existing polling refresh as a fallback" in plan),
]
if not all(checks):
    raise SystemExit(1)
print(f"Realtime readiness contract OK: {len(checks)} checks.")
