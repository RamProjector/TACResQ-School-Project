# TACResQ Android device-verifier transport — implementation note

**Status:** Implemented as an isolated Android transport in PR #370. Exact head 8d5b7b98b7524d5a890723bbd8920793b6a1a80a passed all six workflow groups, including Android JVM tests, source contracts, Android instrumentation-test compilation, Android Lint, demo/Supabase debug builds, release APK build, local Supabase verifier integration, clean PostgreSQL migration replay, Laravel/dashboard integration, RPC grants, and Android/Web CodeQL. Physical emulator/device execution, hosted verifier availability, and operational device registration remain unverified.

## Scope

The Android application now has a dedicated DeviceProofVerifierApi transport for the existing Supabase Edge Function:

- Fixed endpoint: functions/v1/tacresq-device-verifier
- Sends the Supabase publishable key in the apikey header.
- Sends the current caller access token as a Bearer authorization header.
- Adds a fresh W3C traceparent using the existing interceptor.
- Uses bounded request values and validates challenge and verification response shapes.
- Requires HTTPS for non-loopback origins. Plain HTTP is accepted only for localhost/127.0.0.1 test servers.
- Does not install an HTTP body/header logging interceptor.

The transport supports the two server-defined purposes only: registration and session_proof. Registration submits the public SPKI key and bounded device metadata. Session proof does not accept or send a replacement public key. DeviceProofMessage builds the exact server-defined eight-line, versioned proof message, binds the Auth session ID and device UID, and requires a canonical 32-byte Base64URL challenge.

## Isolation boundary

The class is intentionally not referenced by login, SOS, dispatcher, chat, notification, or Admin code. It is not called by SupabaseTacApi, DeviceProofKeyStore, or any ViewModel. A source contract checks that the transport remains dormant until the hosted and device-level release gates are met.

This is deliberate. A transport client is not proof that the verifier is deployed, that a hosted database connection runs as tacresq_device_verifier, or that a registered device is enforced at sensitive server operations.

## Automated test coverage added

The Android JVM test suite covers the authenticated transport and the canonical proof-message builder. It validates the server's exact newline-delimited message format, registration/session purpose binding, profile/session/device identifiers, and canonical challenge encoding.

- Authenticated POST path, publishable-key and Bearer headers.
- Registration challenge parsing and request shape.
- Registration proof payload, metadata trimming, and result parsing.
- Session-proof payload without public-key replacement.
- Rejection of malformed token inputs before network activity.
- Preservation of HTTP authorization errors.
- Rejection of non-loopback HTTP origins.

The existing Android Keystore contract test also checks the fixed endpoint, authentication headers, trace context, HTTPS restriction, absence of request logging, and that neither the transport nor the message builder has operational call sites.

## Remaining release gates

The new canonical message builder and its JVM tests were added after the previously validated transport-only commit. The exact latest PR head must pass its own Android JVM, source-contract, and full workflow checks before this expanded client-protocol slice is considered validated.

1. Execute Android Keystore instrumentation tests on supported Android emulators/devices; CI compilation alone is insufficient.
2. Verify the hosted Supabase transaction-pooler session_user identity and TLS behavior through a controlled, owner-approved check.
3. Deploy the verifier Edge Function and migration/role boundary together only after explicit production approval.
4. Add a separately reviewed registration/session-proof workflow with recovery and revocation UX.
5. Add server-side checks for selected sensitive operations only after proving that requester SOS and other emergency paths remain available from new devices.
6. Obtain independent security and maintainer review.

No production Supabase migration, role/password, Edge Function deployment, Auth setting, Render configuration, user data, or operational client flow was changed by this transport implementation.

## Exact-head CI evidence — 2026-11-28

The implementation source commit was 8d5b7b98b7524d5a890723bbd8920793b6a1a80a. Its six workflow runs all completed successfully:

- TACResQ CI: 46110222316
- TACResQ Security: 46110222322
- TACResQ QA: 46110222350
- RPC grant contract: 46110222348
- TACResQ Frontend Review: 46110222312
- Read-only profile verification: 46110222349

The TACResQ CI run uploaded the Android build artifact (artifact ID 15249622111, SHA-256 ee0602cb0668a84deade3189b0c96bde6ca7379854567d6882f4c2a40fc30054) and Android Lint report (artifact ID 15249472160). The release APK build succeeded, but signing verification was skipped because signing configuration was not supplied. The manual Android emulator workflow was not dispatched.
