#!/usr/bin/env python3
"""Static contract for accurate account-governance status wording."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
console = (root / "backend/web/src/pages/AdminConsole.tsx").read_text()

assert "Duty status describes a responder or driver’s operational availability" in console
assert "it is not an account-enabled, suspended, or sign-in status" in console
assert "This directory does not currently expose account suspension state." in console
assert "Admin accounts cannot be created, promoted, demoted, or edited here." in console
assert "Role changes for field units are blocked while they are assigned to an active incident." in console

print("PASS: Admin directory does not imply duty status is account activation or sign-in state.")
