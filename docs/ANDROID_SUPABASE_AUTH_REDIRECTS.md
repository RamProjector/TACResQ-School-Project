# Android Supabase email-confirmation redirects

This runbook covers the installed Android app's email-confirmation return path. It does **not** change the Supabase project's live Auth configuration.

## Expected redirect contract

The real-backend Android app asks Supabase Auth to use:

```text
tacresq://auth/callback
```

The app's manifest accepts that exact scheme, host, and path. The Supabase-mode debug build and release build use the `tacresq` scheme. The in-memory demo build uses `tacresq-demo` so it does not intercept real-backend confirmation links.

After the email's verification action succeeds, the link should return to the installed app. The callback is treated as untrusted input: the app does not accept tokens from an arbitrary deep link as proof of login. The user signs in normally after confirmation.

## Supabase Dashboard checks

In the **intended QA Supabase project first** (not production):

1. Open **Authentication → URL Configuration**.
2. Under **Redirect URLs**, add the exact URI `tacresq://auth/callback`. Do not assume the project Site URL alone allows this custom URI.
3. Check the confirmation email template and any custom Auth hook. Do not hardcode `http://localhost:3000` as the final redirect target.
4. Create a new test account with a controlled QA mailbox and request a fresh confirmation email after saving the configuration.
5. Inspect the resulting link without sharing its tokens. After verification, confirm it returns to the installed Supabase-mode APK. Then open the app and sign in with the test account.
6. Repeat only after verifying the intended QA project and callback allowlist. Do not use real emergency reports or personal incident data for this test.

Keep the project's Site URL set to the intended canonical web origin used by its web flows. The Android custom-scheme URI belongs in the Redirect URLs allowlist; do not replace a working web Site URL with the Android URI just to make the mobile flow work.

## If the link still opens localhost

A redirect to `localhost:3000` usually means the effective redirect target is still the local web URL or the requested target was not accepted. Check the exact redirect URL in the email link, the Auth Redirect URLs allowlist, and the email template's `redirectTo`/redirect parameter. Do not paste a full confirmation URL into an issue or chat: it may contain one-time secrets.

If the URI is correct but Android does not open, install the **Supabase-mode** debug APK (built with `./gradlew --no-daemon assembleDebug -PuseSupabase=true`), verify the package is installed, and confirm the OS associates `tacresq://auth/callback` with TACResQ. The ordinary demo APK intentionally uses a different scheme.

## Limits and validation

CI can verify the source contract, manifest wiring, and parser tests. It cannot verify a live Supabase project's allowlist, the exact email template currently deployed, delivery provider behavior, or Android's handling on a physical device. Those checks must be recorded separately during QA. Never include access tokens, refresh tokens, confirmation codes, or real user email addresses in test logs.
