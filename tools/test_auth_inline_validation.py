from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/auth/AuthScreen.kt").read_text()
required = (
    "val nameError: String? = null",
    "val emailError: String? = null",
    "val phoneError: String? = null",
    "val passwordError: String? = null",
    "Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()",
    "repo.login(email.trim(), password)",
    "repo.register(name.trim(), email.trim(), phone.trim(), password)",
    "phoneDigits !in 7..15",
    'phone.any { !it.isDigit() && it !in "+- ()" }',
    'password.isBlank() -> "Enter a password."',
    "catch (cancelled: CancellationException)",
    "catch (failure: Exception)",
    '"Something went wrong. Please try again."',
    "isError = state.nameError != null",
    "isError = state.emailError != null",
    "isError = state.phoneError != null",
    "isError = state.passwordError != null",
    "supportingText = { state.emailError?.let { Text(it) } }",
    "supportingText = { state.passwordError?.let { Text(it) } }",
    "fun clearFieldError(field: String)",
    'vm.clearFieldError("email")',
    'vm.clearFieldError("password")',
)
missing = [snippet for snippet in required if snippet not in source]
if missing:
    raise SystemExit("Inline authentication validation contract failed: " + ", ".join(missing))
print(f"Inline authentication validation contract passed: {len(required)} checks.")
