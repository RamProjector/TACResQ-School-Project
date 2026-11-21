# TACResQ Supabase Auth Recovery — Research and Implementation Plan

**Status:** Research and gap analysis only; password recovery is not implemented. No Supabase Auth settings, email templates, SMTP settings, or production redirect allowlists were changed.
**Scope:** Android Supabase Auth account recovery. This is separate from Laravel Sanctum password change and token revocation.

## 1. Current repository findings

- Android authentication calls Supabase Auth through the app's Retrofit-based `SupabaseAuthWire` interface.
- The app currently supports password login, signup, and local-scope logout.
- The existing `Forgot password?` dialog explicitly says recovery is not connected and does not claim an email was sent.
- `AppConfig.AUTH_REDIRECT_URL` is `tacresq://auth/callback`.
- `AuthCallbackMessage` recognizes the exact callback URI but deliberately does not consume access tokens or token material from query parameters or fragments. Its test includes an untrusted access-token fragment and verifies only an informational message is returned.
- The Android app persists sessions encrypted with Android Keystore. Any recovery session must pass through the same persistence path; do not introduce a second plaintext token store.
- The Laravel `/change-password` route applies only to legacy Laravel Sanctum personal access tokens. It is not a Supabase Auth password reset endpoint and must not be presented as one.

## 2. Provider behavior and constraints

Supabase documents password recovery as two distinct operations:

1. Request a recovery email with the user's email address and a configured redirect URL.
2. After the recovery link is validated and a recovery session is established, let the user enter a new password and submit an authenticated user update.

Supabase's reset request is designed not to disclose whether an email belongs to an account. The app must show the same neutral success message for existing and unknown accounts. Do not add client-side account-existence checks or distinguish provider responses in the UI.

Supabase documents both implicit and PKCE recovery flows. PKCE requires a one-time authorization-code exchange; recovery templates can use a token-hash confirmation route. TACResQ currently uses a small Retrofit Auth wrapper rather than the Supabase Kotlin SDK, so the exact callback and token-exchange behavior must be selected and tested before adding the recovery UI.

The default Supabase email sender is best-effort and rate-limited. A production-ready recovery feature depends on a verified email template, configured redirect allowlist, and working SMTP delivery. Do not promise delivery based solely on an HTTP success response.

## 3. Required secure flow

### Phase A — Recovery request

- Validate and normalize the email locally; do not query the profile table to determine whether it exists.
- Send the request directly to Supabase Auth using the configured `tacresq://auth/callback` redirect only after the redirect is confirmed in the Supabase Auth URL allowlist.
- Use a neutral response such as: "If an account matches that address, a recovery link will be sent."
- Apply a local submission cooldown to prevent repeated taps. Treat Supabase's own rate limiting as an additional control, not the only control.
- Never log the email, recovery token, authorization header, or full recovery URL.
- Do not add recovery tokens or passwords to DataStore, analytics, crash reports, or navigation arguments.

### Phase B — Validate the email link

- Accept only the exact TACResQ scheme, host, and callback path.
- Validate the expected recovery flow type and one-time proof with Supabase Auth. A deep link by itself is never proof of authentication.
- Prefer a documented one-time PKCE/code or token-hash verification flow. Do not accept an arbitrary `access_token` query parameter or fragment as authenticated session material.
- Reject expired, malformed, replayed, wrong-purpose, or already-consumed proofs.
- Do not display the password-change screen until Supabase confirms a recovery session.
- Keep signup confirmation and password recovery as distinct callback states; the existing generic "sign in with your email and password" callback must not be mistaken for a completed password reset.

### Phase C — Set the new password

- Require a new password and confirmation; enforce the project's password policy and breached-password check where applicable.
- Submit the update only with the verified recovery session.
- On success, clear the recovery session and any temporary proof from memory and local storage, then require a normal sign-in unless Supabase's documented session behavior and tests establish a safe alternative.
- Revoke other sessions according to the chosen Supabase Auth policy; verify the actual provider semantics rather than assuming Laravel Sanctum's `logout-all` behavior applies.
- Return generic, safe error messages. Never expose raw Supabase error bodies.

## 4. Implementation gates

Do not implement or enable the production recovery flow until all are verified:

1. Supabase Auth redirect allowlist contains only the intended TACResQ callback URI(s), not broad wildcards.
2. The deployed recovery email template produces the selected proof format and correct redirect.
3. The app manifest deep-link intent filter accepts only the expected scheme/host/path.
4. A real device can receive the email, open the app, validate a valid recovery proof, and reject expired/replayed/wrong-purpose proofs.
5. Password update requires a validated recovery session and does not accept an arbitrary user-supplied access token.
6. Recovery requests do not reveal whether an account exists.
7. Session storage remains encrypted with Android Keystore and logout/revocation behavior is covered by tests.
8. The configured email provider can deliver messages reliably enough for the intended operational use.

## 5. Test matrix

- Existing and unknown email addresses receive the same UI response.
- Rapid repeated taps are blocked locally; provider throttling is handled without exposing account existence.
- Invalid email input causes no network request.
- Wrong callback scheme, host, path, port, user-info, or malformed URI is rejected.
- Arbitrary query/fragment access tokens are ignored and never establish a session.
- Expired, replayed, wrong-purpose, and malformed recovery proofs fail closed.
- Signup confirmation does not open the password-reset form.
- A recovery callback without successful server verification does not unlock password update.
- New password mismatch/weak password fails locally.
- A failed password update does not report success or erase the user's only recovery path prematurely.
- Successful update clears temporary recovery state and follows the verified session-revocation policy.
- No token, password, or recovery URL appears in logs or crash diagnostics.

## 6. References

- Supabase password-based Auth and password recovery: https://supabase.com/docs/guides/auth/passwords
- Supabase Kotlin reset-password request: https://supabase.com/docs/reference/kotlin/auth-resetpasswordforemail
- Supabase Kotlin Auth API reference: https://supabase.com/docs/reference/kotlin/v2
- Supabase native mobile deep linking: https://supabase.com/docs/guides/auth/native-mobile-deep-linking
- Supabase PKCE flow: https://supabase.com/docs/guides/auth/sessions/pkce-flow

**Decision:** Keep the current honest "not connected" dialog until the callback proof validation, provider email template, and recovery-session handling are implemented end to end. Do not ship a UI-only recovery button that cannot safely complete the reset.
