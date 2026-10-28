#!/usr/bin/env python3
"""Contract checks for role-aware driver presentation and safe route guidance."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()
root = (ROOT / "app/src/main/java/com/tacresq/app/ui/AppRoot.kt").read_text()
nav = (ROOT / "app/src/main/java/com/tacresq/app/ui/RoleShell.kt").read_text()

# Drivers currently use the shared assignment API/model; their screen must not
# claim vehicle telemetry or controls that the backend does not provide.
assert "Role.RESPONDER, Role.DRIVER ->" in root
assert 'title = if (user.role == com.tacresq.app.data.model.Role.DRIVER) "Driver workspace" else "Responder workspace"' in screen
assert 'subtitle = if (user.role == com.tacresq.app.data.model.Role.DRIVER) "Assigned trips, route guidance, and location sharing" else "Your assignments, duty status, and location sharing"' in screen
assert 'if (user.role == com.tacresq.app.data.model.Role.DRIVER) "Current trip" else "Active assignments"' in screen
assert 'if (user.role == com.tacresq.app.data.model.Role.DRIVER) "Trip history" else "Recent history"' in screen
assert 'Text(if (user.role == com.tacresq.app.data.model.Role.DRIVER) "Open route guidance" else "Open navigation app")' in screen
assert "Do not interact with this app while driving." in screen
assert "incident.statusFor(user.id).nextSteps()" in screen, "driver actions must still follow server-supported incident transitions"
assert "enabled = !state.busy && !state.loading && !state.refreshing && state.syncError == null" in screen
assert 'Role.RESPONDER, Role.DRIVER -> listOf(if (compact) "Call" else "Current call", "History", "Safety", "Profile")' in nav
assert "GpsMapNavigationItem(selected, onDestinationSelected)" in nav
print("PASS: driver gets role-specific trip labels and route-safety copy without invented vehicle features.")
print("PASS: assignment transitions, verified sync state, and GPS Map navigation remain shared safeguards.")
