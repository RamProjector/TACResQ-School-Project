from pathlib import Path

source = Path("backend/web/src/pages/AdminConsole.tsx").read_text()

panel_start = source.index('<section className="admin-panel admin-analytics-panel"')
panel_end = source.index('</section>', panel_start)
panel = source[panel_start:panel_end]

assert 'aria-labelledby="admin-analytics-title"' in panel
assert "aria-busy={analyticsLoading}" in panel, "Analytics panel must expose request/refresh busy state"
assert 'role="status" aria-live="polite"' in panel, "Loading state must be announced politely"
assert 'role="alert"' in panel, "Analytics failures must remain announced"

print("Admin analytics busy-state contract: passed")
