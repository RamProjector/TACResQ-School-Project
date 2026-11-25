#!/usr/bin/env python3
"""Static contract for retryable, race-safe Admin dependency diagnostics."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
page = (root / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")
styles = (root / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")

assert "async function retryDependencyHealth()" in page
assert "getAdminDependencyHealth()" in page
assert "dependencyHealthRequestVersion.current" in page
assert 'aria-label="Refresh dependency diagnostics"' in page
assert 'onClick={() => void retryDependencyHealth()}' in page
assert "disabled={dependencyHealthLoading}" in page
assert "setDependencyHealthError(null)" in page
assert "Dependency diagnostics are unavailable." in page
assert ".admin-health-actions" in styles
assert ".admin-health-actions .admin-button:disabled" in styles

print("PASS: dependency diagnostics can be retried with loading, error, and stale-response protection.")
