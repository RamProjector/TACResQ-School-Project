#!/usr/bin/env python3
"""Guard concise, role-specific copy that explains the current Android workflows."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
responder = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text(encoding="utf-8")
notifications = (ROOT / "app/src/main/java/com/tacresq/app/ui/notifications/NotificationsScreen.kt").read_text(encoding="utf-8")

assert 'subtitle = "Review assignments, update your status, and manage location sharing."' in responder
assert 'subtitle = "Push alerts bring you here; this inbox is the source of truth."' in notifications
print("Operational UI copy contract passed.")
