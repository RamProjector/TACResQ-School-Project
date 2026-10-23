#!/usr/bin/env python3
"""Source contract for cooldown bypass limited to reviewed emergency-pin corrections."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = (ROOT / "supabase/migrations/20261023040000_requester_urgent_location_correction.sql").read_text().lower()
SCREEN = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
ALIGNMENT = (ROOT / "tools/test_migration_history_alignment.py").read_text()

checks = {
    "bypass requires a changed coordinate": "incident_row.latitude is distinct from p_latitude" in MIGRATION and "incident_row.longitude is distinct from p_longitude" in MIGRATION,
    "bypass forbids unrelated type/detail/note edits": "incident_row.type is not distinct from p_type" in MIGRATION and "incident_row.description is not distinct from nullif(trim(p_description), '')" in MIGRATION and "incident_row.location_notes is not distinct from nullif(trim(p_location_notes), '')" in MIGRATION,
    "bypass still requires review confirmation": "location_only_correction and coalesce(p_confirm_review, false)" in MIGRATION,
    "bypass is auditable": "urgent_location_correction boolean not null default false" in MIGRATION and "urgent_location_correction" in MIGRATION,
    "UI allows only location-only correction during cooldown": "urgentLocationCorrection" in SCREEN and "editCooldownSeconds == 0L || urgentLocationCorrection" in SCREEN,
    "review explains the emergency location bypass": "safety-critical change can bypass the timer" in SCREEN,
    "migration is tracked in repository history": "20261023040000_requester_urgent_location_correction.sql" in ALIGNMENT,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"{'PASS' if ok else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} urgent-location correction contract(s) failed")
print(f"Urgent location correction contract OK: {len(checks)} checks.")
