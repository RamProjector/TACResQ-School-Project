from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
requester = (ROOT / "app/src/main/java/com/tacresq/app/ui/requester/RequesterScreen.kt").read_text()
auth = (ROOT / "app/src/main/java/com/tacresq/app/ui/auth/AuthScreen.kt").read_text()
admin = (ROOT / "app/src/main/java/com/tacresq/app/ui/admin/AdminAccessScreen.kt").read_text()
supabase_api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
repository = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
admin_migration = (ROOT / "supabase/migrations/20261026161500_admin_role_audit_foundation.sql").read_text()
admin_runbook = (ROOT / "docs/ADMIN_PROVISIONING_RUNBOOK.md").read_text()
location_migration = (ROOT / "supabase/migrations/20261030100000_require_incident_location_fallback.sql").read_text().lower()
signup_migration = (ROOT / "supabase/migrations/20261030110000_require_requester_signup_contact.sql").read_text().lower()
signup_bounds_migration = (ROOT / "supabase/migrations/20261030120000_bound_requester_signup_contact_lengths.sql").read_text().lower()

# SOS category must be selected explicitly; Other requires details.
assert "mutableStateOf<IncidentType?>(null)" in requester
assert "emergencyTypeError = selectedType == null" in requester
assert "selectedType == IncidentType.OTHER && description.isBlank()" in requester
assert "Describe the emergency (required)" in requester
assert "Emergency type (required)" in requester
assert "an emergency description is required for the other type" in location_migration

# A requester must provide either a fresh location pin or a usable landmark.
assert "if (!hasFreshPin && locationNotes.isBlank())" in requester
assert 'locationRequirementError = "Add a location pin or a landmark/access detail before continuing."' in requester
assert "if (fix == null && locationNotes.isBlank())" in requester
assert "Add a location pin or a landmark/access detail before sending." in requester
assert "Landmark or access detail (required without a pin)" in requester
assert "a location pin or landmark/access detail is required" in location_migration
assert "before insert or update of latitude, longitude, location_notes, type, description on public.incidents" in location_migration

# Registration requires all requester identity/contact/credential fields.
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
assert "passwordVisible = false" in auth
assert "Staff and administrator accounts are provisioned separately" in auth
assert '"role" to "requester"' in supabase_api
assert "administrator provisioning is operator-only" in admin_migration.lower()
assert "a full name is required for requester registration" in signup_migration
assert "a valid mobile number is required for requester registration" in signup_migration
assert "requester mobile numbers must contain 7 to 15 digits" in signup_migration
assert "'requester'" in signup_migration
assert "raw_user_meta_data ->> 'role'" not in signup_migration

# Client and database enforce the same bounded, ASCII-only contact validation.
assert "name.trim().length > 120" in auth
assert "requester names must contain 120 characters or fewer" in signup_bounds_migration
assert "char_length(full_name) > 120" in signup_bounds_migration
assert "phone.length > 25" in auth
assert "char_length(phone_value) > 25" in signup_bounds_migration
assert "phone.count { it in '0'..'9' }" in auth
assert "phone.any { it !in '0'..'9' && it !in \"+- ()\" }" in auth
assert "phone_value !~ '^[0-9+() -]+$'" in signup_bounds_migration

# Authentication errors must not reveal whether an account exists.
for snippet in (
    'val authPath = e.response()?.raw()?.request?.url?.encodedPath.orEmpty()',
    'if (authPath.endsWith("/auth/v1/token"))',
    '"Email or password is incorrect."',
    'if (authPath.endsWith("/auth/v1/signup"))',
    "We couldn't complete sign-up. Check your details or try again later.",
    '"Too many sign-in attempts. Wait a moment and try again."',
    '"Too many sign-up attempts. Wait a moment and try again."',
    'catch (e: ApiException)',
    'catch (e: IllegalArgumentException)',
    'Result.failure(ApiException("Please check the information you entered and try again."))',
    'Result.failure(ApiException("Something went wrong. Please try again."))',
):
    assert snippet in repository, f"Missing authentication privacy contract: {snippet}"

# Admin provisioning remains out-of-band and traceable.
for prerequisite in (
    "approved out-of-band process",
    "existing Supabase Auth user UUID",
    "verified email-backed account",
    "operator identity and change/ticket reference",
    "linked to a verified Supabase Auth user",
):
    assert prerequisite.lower() in admin_runbook.lower(), f"Missing Admin provisioning prerequisite: {prerequisite}"

# Admin role changes require an audit reason and explicit target selection.
assert 'label = { Text("Audit reason (required)") }' in admin
assert "reason.trim().length in 10..500" in admin
assert "Required: explain why this role change is necessary." in admin
assert "This explanation is saved in the audit history." in admin
assert "repository.setAdminUserRole(account.id, targetRole, reason.trim())" in admin
assert 'var targetRole by remember { mutableStateOf("") }' in admin
assert 'targetRole = ""' in admin
assert "No role is preselected." in admin
assert 'Text("${account.email} · Account ID ${account.id}"' in admin
assert "targetRole in adminRoles" in admin
assert "Confirm ${targetRole.replaceFirstChar { it.uppercase() }} role" in admin
assert "onDismissRequest = { if (!loading) selectedUser = null }" in admin
assert "enabled = !loading, onClick = { targetRole = role; error = null }" in admin
assert "onValueChange = { if (it.length <= 500) { reason = it; error = null } }" in admin
reason_start = admin.index("value = reason")
reason_end = admin.index("modifier = Modifier.fillMaxWidth()", reason_start)
assert "enabled = !loading," in admin[reason_start:reason_end]
assert "error?.let {" in admin[admin.index("selectedUser?.let"):admin.index("confirmButton = {")]
assert "Saving role change…" in admin
assert 'enabled = !loading) { Text("Cancel") }' in admin

print("Requester, SOS location, registration, authentication privacy, and admin audit contracts passed.")
