#!/usr/bin/env python3
"""Offline source-contract checks for dispatcher queue wait-time prioritization."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
screen = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()

checks = {
    "pending incidents are sorted oldest-first by submitted timestamp": ".sortedWith(compareBy<Incident>" in screen and "incident.createdAt" in screen and "Instant.MIN" in screen,
    "missing or malformed submission timestamps are explicitly surfaced": "Waiting time unavailable — verify submission time." in screen,
    "future submission timestamps are not shown as negative wait": "Submission time is in the future — verify timestamp." in screen and "seconds < 0L" in screen,
    "wait timer refreshes without requiring a network request": "LaunchedEffect(Unit)" in screen and "delay(30_000)" in screen and "queueClock = Instant.now()" in screen,
    "long-wait incidents are visually flagged after 15 minutes": "waitSeconds >= 15 * 60" in screen and "MaterialTheme.colorScheme.error" in screen,
    "wait age is displayed on pending incident cards": "waitingAgeLabel(incident.createdAt, queueClock)" in screen,
    "elapsed wait labels support seconds, minutes, hours, and days": all(token in screen for token in ("Waiting ${seconds}s", "Waiting ${seconds / 60L}m", "Waiting ${seconds / 3_600L}h", "Waiting ${seconds / 86_400L}d")),
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} dispatcher queue contract check(s) failed")
print(f"Dispatcher queue source contract OK: {len(checks)} checks.")
