from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
requester = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
auth = (ROOT / "app/src/main/java/com/tacresq/app/ui/auth/AuthScreen.kt").read_text()
admin = (ROOT / "app/src/main/java/com/tacresq/app/ui/admin/AdminAccessScreen.kt").read_text()
supabase_api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
admin_migration = (ROOT / "supabase/migrations/20261026161500_admin_role_audit_foundation.sql").read_text()

# A requester must provide either a fresh location pin or a usable landmark.
assert "if (!hasFreshPin && locationNotes.isBlank())" in requester
assert "locationRequirementError = \"Add a location pin or a landmark/access detail before continuing.\"" in requester
assert "if (fix == null && locationNotes.isBlank())" in requester
assert "Add a location pin or a landmark/access detail before sending." in requester
assert "Landmark or access detail (required without a pin)" in requester

# Registration requires all identity/contact/credential fields and explains the
# separate provisioning path for staff and administrators.
for field in ("name.isBlank()", "email.isBlank()", "phone.isBlank()", "password.isBlank()"):
    assert field in auth, f"Missing required requester registration validation: {field}"
for label in ("Full name (required)", "Mobile number (required)", "Email address (required)", "Password (required)"):
    assert label in auth, f"Missing required registration label: {label}"
assert "Staff and administrator accounts are provisioned separately" in auth
assert '"role" to "requester"' in supabase_api, "Public Supabase signup must force the requester role."
assert "administrator provisioning is operator-only" in admin_migration.lower()

# Admin role changes cannot be confirmed without a reason; the UI explains why.
assert 'label = { Text("Audit reason (required)") }' in admin
assert "reason.trim().length in 10..500" in admin
assert "Required: explain why this role change is necessary." in admin
assert "This explanation is saved in the audit history." in admin
assert "repository.setAdminUserRole(account.id, targetRole, reason.trim())" in admin

print("Required requester, SOS location, and admin audit feedback contracts passed.")
