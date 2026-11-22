#!/usr/bin/env python3
"""Static contract for clear Admin Overview account metric scope."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
console = (root / "backend/web/src/pages/AdminConsole.tsx").read_text()

assert "PROFILES MATCHING FILTERS" in console
assert "Across all directory pages" in console
assert "REQUESTERS · CURRENT PAGE" in console
assert "FIELD UNITS · CURRENT PAGE" in console
assert "Only the profiles on this page" in console
assert "Responders and drivers on this page only" in console
assert "Counts follow the current directory search and role filters." in console
assert "they are not system-wide role totals." in console

print("PASS: Admin Overview distinguishes filtered directory totals from current-page role breakdowns.")
