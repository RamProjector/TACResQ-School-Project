from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
home = (ROOT / "backend/web/src/pages/Home.tsx").read_text()
dockerfile = (ROOT / "backend/Dockerfile").read_text()
web_routes = (ROOT / "backend/src/routes/web.php").read_text()
ci = (ROOT / ".github/workflows/ci.yml").read_text()
web_readme = (ROOT / "backend/web/README.md").read_text()

# The browser frontend must not offer an incident queue or dispatch mutations.
for forbidden in ("DispatcherBoard", "assignIncident(", "getAllIncidents(", "getFieldUnits("):
    assert forbidden not in home, f"Web frontend still contains dispatcher operation: {forbidden}"

# Keep administrator governance and a clear dispatcher-to-Android handoff.
assert 'profile.role === "admin"' in home
assert "<AdminConsole" in home
assert 'profile.role === "dispatcher"' in home
assert "TACResQ Android app" in home

# The deployed static app is the admin portal; no legacy /dispatch dashboard path remains.
assert "VITE_BASE_PATH=/admin/" in dockerfile
assert "./public/admin/" in dockerfile
assert "public_path('admin/index.html')" not in web_routes
assert "redirect('/admin/')" in web_routes
assert "Route::get('/dispatch/config.json'" not in web_routes
assert "VITE_BASE_PATH: /admin/" in ci
assert "http://127.0.0.1:8000/admin/" in ci
assert "/admin/assets/" in ci
assert "administrator-only" in web_readme.lower()
assert "incident queue" in web_readme.lower()

# Keep the portal focused on implemented administrative capabilities.
admin_console = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text()
for page in ('"overview"', '"users"', '"security"', '"analytics"', '"audit"'):
    assert page in admin_console
assert '"settings"' not in admin_console
assert '"backups"' not in admin_console
assert "BACKEND-VERIFIED CAPABILITIES" not in admin_console

print("Web admin-only contract passed.")
