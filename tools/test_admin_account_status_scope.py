#!/usr/bin/env python3
"""Static contract for the current Admin account-governance UI and its limits."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
console = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()
security = (ROOT / "backend/web/src/components/AdminSecurityStatus.tsx").read_text()
client = (ROOT / "backend/web/src/lib/supabase.ts").read_text()
transition = (ROOT / "supabase/migrations/20261125020926_admin_account_lifecycle_compare_and_set.sql").read_text().lower()

# User management exposes lifecycle state separately from operational duty.
for marker in (
    'user.account_status === "active" ? "Active" : "Suspended"',
    'user.account_status === "active" ? "Suspend" : "Reactivate"',
    'aria-label={(user.account_status === "active" ? "Suspend account for " : "Reactivate account for ") + user.name}',
    'async function confirmAccountStatusChange()',
    'await setAdminAccountStatus(lifecycleTarget.id, expectedStatus, nextStatus, normalizedReason)',
    'Account status is separate from responder or driver duty status.',
):
    assert marker in console, f"Missing Admin lifecycle UI contract: {marker}"

# The browser client invokes only the narrow RPC with a compare-and-set state.
assert 'getSupabase().rpc("api_admin_set_account_status"' in client
assert 'p_expected_account_status: expectedStatus' in client
assert 'grant execute on function public.api_admin_set_account_status(bigint, text, text, text) to authenticated' in transition
assert 'perform private.tacresq_require_admin_mfa()' in transition
assert "v_target.account_status is distinct from p_expected_account_status" in transition
assert "p_target_profile_id = v_actor_id" in transition
assert "if v_target.role = 'admin'" in transition
assert "account_lifecycle_audit_logs" in transition
assert "release or complete active incident assignments" in transition

# Security-page inventory must distinguish branch implementation from hosted rollout.
assert "User management page implements reason-required suspend/reactivate actions" in security
assert "do not prove the migration is deployed in the hosted project" in security
assert "not yet proven across every direct RPC, Storage, Edge Function, and legacy Laravel/Sanctum path" in security
assert "No suspend/reactivate action is exposed here." not in security
assert "EXECUTE remains revoked from client roles" not in security

# Admin lifecycle actions remain unavailable for Admin targets; Admin provisioning
# and recovery are still a trusted-operator boundary.
assert 'if (user.role === "admin" || saving || roleChangeInFlight.current) return;' in console
assert "Admin accounts cannot be created, promoted, demoted, or edited here." in console

print("PASS: Admin account lifecycle UI, narrow RPC grant, and rollout limitations are accurately represented.")
