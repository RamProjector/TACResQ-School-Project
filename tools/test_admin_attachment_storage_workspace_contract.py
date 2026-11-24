from pathlib import Path

source = Path("backend/web/src/pages/AdminConsole.tsx").read_text()
styles = Path("backend/web/src/pages/AdminConsole.css").read_text()

# Attachment storage is a first-class, URL-addressable Admin section.
assert 'type AdminSection = "overview" | "users" | "security" | "analytics" | "audit" | "recovery" | "attachments" | "settings" | "help";' in source
assert '{ id: "attachments", label: "Attachment storage", icon: Images }' in source
assert '"recovery", "attachments", "settings", "help"' in source
assert '{activeSection === "attachments" && (' in source
assert 'id="admin-attachment-title"' in source

# Show the exact evidence boundary and staged rollout caveat.
for marker in (
    "Live inventory not connected",
    "The latest repository-recorded live bucket inspection is dated 22 November 2026",
    "Storage health is not currently monitored in this portal.",
    "incident-chat-attachments",
    "JPEG, PNG, and WebP",
    "5 MiB per-object maximum",
    "authenticated INSERT and SELECT only",
    "Staged enforcement",
    "migration default keeps strict reservation enforcement disabled",
    "live switch or prove rollout completion",
    "No cleanup schedule",
    "approved retention period",
    "Object bytes not verified",
    "does not download or hash object bytes",
    "read-only attachment inventory SQL",
    "Real Storage acceptance",
    "Retention decision",
    "Current bucket configuration",
    "Not checked live",
    "Object inventory",
    "Not queried",
    "Byte integrity",
    "Not tested",
    "Retention / cleanup",
    "Not configured",
):
    assert marker in source, f"Missing attachment storage boundary: {marker}"

for required_link in (
    "incident-chat-attachment-lifecycle-review.md",
    "incident-chat-upload-reservation-design.md",
    "report_incident_chat_attachment_inventory.sql",
    "storage-object-backup-procedure.md",
):
    assert required_link in source, f"Missing attachment operator reference: {required_link}"

# This is not an unrestricted media browser or cleanup control.
for forbidden in (
    "View all attachments",
    "Download attachment",
    "Delete orphaned objects",
    "Clean up storage",
    "Enable reservation enforcement",
):
    assert forbidden not in source, f"Unapproved attachment action exposed: {forbidden}"

assert "admin-attachment-grid" in styles
assert "admin-attachment-metrics" in styles
assert "@media (max-width:760px)" in styles

print("PASS: Admin attachment storage workspace is privacy-bounded, evidence-aware, read-only, and responsive.")
assert 'placeholder="Search workspaces, roles, security, reports, recovery, storage…"' in source
assert "recovery, or storage." in source
