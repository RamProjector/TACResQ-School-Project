from pathlib import Path

gate = Path("backend/web/src/components/AdminMfaGate.tsx").read_text()

assert "async function handleSignOut()" in gate
assert 'if (state === "enroll" && factorId)' in gate
assert "await cancelEnrollment();" in gate
assert 'if (state === "checking" && preparation.current)' in gate
assert "const prepared = await preparation.current;" in gate
assert "getSupabase().auth.mfa.unenroll({" in gate
assert "factorId: prepared.factorId" in gate
assert "Cleanup is best-effort" in gate
assert "onSignOut();" in gate[gate.index("async function handleSignOut()"):gate.index("async function cancelEnrollment()")]
assert 'onClick={() => { void handleSignOut(); }} disabled={busy}' in gate

print("Admin MFA gate pending-factor sign-out cleanup contract: passed")
