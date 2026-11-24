from pathlib import Path

source = Path("backend/web/src/pages/AdminConsole.tsx").read_text()
styles = Path("backend/web/src/pages/AdminConsole.css").read_text()

# The recovery workspace is a first-class, URL-addressable Admin section.
assert 'type AdminSection = "overview" | "users" | "security" | "analytics" | "audit" | "recovery" | "settings" | "help";' in source
assert '{ id: "recovery", label: "Recovery & continuity", icon: HardDrive }' in source
assert '"recovery", "settings", "help"' in source
assert '{activeSection === "recovery" && (' in source
assert 'id="admin-recovery-title"' in source

# The page must distinguish documented procedures from live evidence.
for marker in (
    "No live recovery evidence connected",
    "Recovery capability is not currently verified.",
    "No backup schedule, off-site copy, backup freshness record, or completed restore drill",
    "PostgreSQL data &amp; schema",
    "Storage object bytes",
    "Auth users &amp; project settings",
    "Render Admin portal &amp; API",
    "FCM dispatcher continuity",
    "Isolated restore drill",
    "Backup freshness",
    "Off-site copy",
    "Restore success",
    "Achieved RPO / RTO",
    "Not measured",
    "Not verified",
    "Not tested",
    "Safe recovery sequence",
    "provisional RPO/RTO planning targets",
):
    assert marker in source, f"Missing recovery scope or evidence disclosure: {marker}"

for required_link in (
    "postgres-logical-backup-procedure.md",
    "storage-object-backup-procedure.md",
    "isolated-restore-drill-checklist.md",
    "backup-disaster-recovery-research-and-implementation-plan.md",
    "FCM_OPERATIONS_RUNBOOK.md",
):
    assert required_link in source, f"Missing operator procedure link: {required_link}"

# The Admin page is informational only until governed backup evidence exists.
for forbidden in (
    "Start backup",
    "Run restore",
    "Create recovery project",
    "Delete backup",
    "Schedule backup",
):
    assert forbidden not in source, f"Unverified recovery action exposed: {forbidden}"

assert "admin-recovery-grid" in styles
assert "admin-recovery-metrics" in styles
assert "@media (max-width:760px)" in styles

print("PASS: Admin recovery workspace is evidence-aware, read-only, responsive, and links approved operator procedures.")
# Navigation, overview shortcuts, and the permission matrix must describe the
# new section without implying it performs privileged recovery operations.
assert 'navigateToSection("recovery")' in source
assert "Recovery &amp; continuity</strong>" in source
assert "Recovery &amp; continuity</th><td>Admin portal · read-only guidance</td>" in source
assert '["recovery", "backup", "restore", "disaster recovery", "continuity", "storage backup", "rpo", "rto"]' in source
assert "grid-template-columns:repeat(auto-fit,minmax(220px,1fr))" in styles
