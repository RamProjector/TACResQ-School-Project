from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
requester = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
auth = (ROOT / "app/src/main/java/com/tacresq/app/ui/auth/AuthScreen.kt").read_text()
admin = (ROOT / "app/src/main/java/com/tacresq/app/ui/admin/AdminAccessScreen.kt").read_text()
supabase_api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
admin_migration = (ROOT / "supabase/migrations/20261026161500_admin_role_audit_foundation.sql").read_text()
admin_runbook = (ROOT / "docs/ADMIN_PROVISIONING_RUNBOOK.md").read_text()
location_migration = (ROOT / "supabase/migrations/20261030100000_require_incident_location_fallback.sql").read_text().lower()
signup_migration = (ROOT / "supabase/migrations/20261030110000_require_requester_signup_contact.sql").read_text().lower()

# SOS category must be selected explicitly; Other requires details.
assert "mutableStateOf<IncidentType?>(null)" in requester
assert "emergencyTypeError = selectedType == null" in requester
assert "selectedType == IncidentType.OTHER && description.isBlank()" in requester
assert "Describe the emergency (required)" in requester
assert "Emergency type (required)" in requester
assert "an emergency description is required for the other type" in location_migration

# A requester must provide either a fresh location pin or a usable landmark.
assert "if (!hasFreshPin && locationNotes.isBlank())" in requester
assert "locationRequirementError = \"Add a location pin or a landmark/access detail before continuing.\"" in requester
assert "if (fix == null && locationNotes.isBlank())" in requester
assert "Add a location pin or a landmark/access detail before sending." in requester
assert "Landmark or access detail (required without a pin)" in requester
assert "a location pin or landmark/access detail is required" in location_migration
assert "before insert on public.incidents" in location_migration

# Registration requires all identity/contact/credential fields and explains the
# separate provisioning path for staff and administrators.
for field in ("name.isBlank()", "email.isBlank()", "phone.isBlank()", "password.isBlank()"):
    assert field in auth, f"Missing required requester registration validation: {field}"
for label in ("Full name (required)", "Mobile number (required)", "Email address (required)", "Password (required)"):
    assert label in auth, f"Missing required registration label: {label}"
assert 'var password by remember { mutableStateOf("") }' in auth
assert "var passwordVisible by remember { mutableStateOf(false) }" in auth
assert "var password by rememberSaveable" not in auth
assert "var passwordVisible by rememberSaveable" not in auth
assert 'Text(if (passwordVisible) "Hide" else "Show")' in auth
assert "if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation()" in auth
assert "passwordVisible = false" in auth, "Password must be hidden again when switching login/register mode."
assert "Staff and administrator accounts are provisioned separately" in auth
assert '"role" to "requester"' in supabase_api, "Public Supabase signup must force the requester role."
assert "administrator provisioning is operator-only" in admin_migration.lower()
assert "a full name is required for requester registration" in signup_migration
assert "a valid mobile number is required for requester registration" in signup_migration
assert "requester mobile numbers must contain 7 to 15 digits" in signup_migration
assert "'requester'" in signup_migration
assert "raw_user_meta_data ->> 'role'" not in signup_migration
for prerequisite in (
    "approved out-of-band process",
    "existing Supabase Auth user UUID",
    "verified email-backed account",
    "operator identity and change/ticket reference",
    "linked to a verified Supabase Auth user",
):
    assert prerequisite.lower() in admin_runbook.lower(), f"Missing Admin provisioning prerequisite: {prerequisite}"

# Admin role changes cannot be confirmed without a reason; the UI explains why.
assert 'label = { Text("Audit reason (required)") }' in admin
assert "reason.trim().length in 10..500" in admin
assert "Required: explain why this role change is necessary." in admin
assert "This explanation is saved in the audit history." in admin
assert "repository.setAdminUserRole(account.id, targetRole, reason.trim())" in admin
# High-impact role changes require an explicit target choice and clear identity context.
assert 'var targetRole by remember { mutableStateOf("") }' in admin
assert 'targetRole = ""' in admin
assert 'No role is preselected.' in admin
assert 'Text("${account.email} · Account ID ${account.id}"' in admin
assert 'targetRole in adminRoles' in admin
assert 'Confirm ${targetRole.replaceFirstChar { it.uppercase() }} role' in admin
assert "onDismissRequest = { if (!loading) selectedUser = null }" in admin
assert 'enabled = !loading, onClick = { targetRole = role; error = null }' in admin
assert 'onValueChange = { if (it.length <= 500) { reason = it; error = null } }' in admin
assert "enabled = !loading," in admin[admin.index("value = reason"):admin.index("modifier = Modifier.fillMaxWidth()", admin.index("value = reason"))]
assert 'error?.let {' in admin[admin.indexOf('selectedUser?.let'):admin.indexOf('confirmButton = {')]
assert "Saving role change…" in admin
assert 'enabled = !loading) { Text("Cancel") }' in admin

print("Required requester, SOS location, and admin audit feedback contracts passed.")
