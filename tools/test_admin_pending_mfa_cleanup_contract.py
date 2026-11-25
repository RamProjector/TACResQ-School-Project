from pathlib import Path

source = Path("backend/web/src/components/AdminSecurityStatus.tsx").read_text()

cleanup_start = source.index("  useEffect(() => () => {")
cleanup_end = source.index("  }, []);", cleanup_start) + len("  }, []);")
cleanup = source[cleanup_start:cleanup_end]

assert "const factorId = pendingEnrollmentId.current;" in cleanup
assert "if (!factorId) return;" in cleanup
assert "getSupabase().auth.mfa.unenroll({ factorId }).catch(" in cleanup
assert "catch {" in cleanup, "Synchronous client initialization failures must also be contained"
assert "unhandled rejection" in cleanup
assert "pendingEnrollmentId.current = null;" in source, "Successful verification/cancellation must clear pending cleanup ownership"

print("Admin pending MFA teardown contract: passed")

gate = Path("backend/web/src/components/AdminMfaGate.tsx").read_text()
assert "async function handleSignOut()" in gate
assert 'if (state === "enroll" && factorId)' in gate
assert "await cancelEnrollment();" in gate
assert 'if (state === "checking" && preparation.current)' in gate
assert "const prepared = await preparation.current;" in gate
assert "factorId: prepared.factorId" in gate
assert "Cleanup is best-effort" in gate
assert "Sign-out must remain possible" in gate
assert "onSignOut();" in gate[gate.index("async function handleSignOut()"):gate.index("async function cancelEnrollment()")]
cancel_start = gate.index("async function cancelEnrollment()")
cancel_end = gate.index("async function verifyCode", cancel_start)
cancel = gate[cancel_start:cancel_end]
assert "finally {" in cancel and "onSignOut();" in cancel[cancel.index("finally {"):], "Sign-out must complete even when pending-factor cleanup fails"
assert 'onClick={() => { void handleSignOut(); }} disabled={busy}' in gate
