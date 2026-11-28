# TACResQ Android device-verifier transport — implementation note

**Status:** Implemented as an isolated Android client-protocol slice in PR #370. The latest code head 226b9df60c2cbbd6dcbe8057287bedd0468f420a passed all six workflow groups, including canonical-message and transport JVM tests, repository source contracts, Android instrumentation-test compilation, Android Lint, demo/Supabase debug builds, release APK build, local Supabase verifier integration, clean PostgreSQL migration replay, Laravel/dashboard integration, RPC grants, and Android/Web CodeQL. Physical emulator/device execution, hosted verifier availability, and operational device registration remain unverified.

## Scope

The Android application now has a dedicated DeviceProofVerifierApi transport for the existing Supabase Edge Function:

- Fixed endpoint: functions/v1/tacresq-device-verifier
- Sends the Supabase publishable key in the apikey header.
- Sends the current caller access token as a Bearer authorization header.
- Adds a fresh W3C traceparent using the existing interceptor.
- Uses bounded request values and validates challenge and verification response shapes. Before network transmission, it accepts only canonical unpadded Base64URL containing a strict DER ECDSA sequence with positive, minimally encoded P-256 scalars in range; raw 64-byte P1363 signatures are rejected.
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
- Strict DER signature parsing, canonical Base64URL enforcement, P-256 scalar range checks, and rejection of raw P1363, zero/negative scalars, and padded encodings before network activity.
- Rejection of malformed token inputs before network activity.
- Preservation of HTTP authorization errors.
- Rejection of non-loopback HTTP origins.

The existing Android Keystore contract test also checks the fixed endpoint, authentication headers, trace context, HTTPS restriction, absence of request logging, and that neither the transport nor the message builder has operational call sites.

## Remaining release gates

The canonical message builder and its JVM tests are included in code head 226b9df60c2cbbd6dcbe8057287bedd0468f420a. That exact head passed the Android JVM suite, the extended dormant-boundary source contract, Android instrumentation-test compilation, Android Lint, both debug APK builds, the release APK build, clean migration replay, local Supabase verifier integration, Laravel/dashboard tests, RPC grant contract, QA, Frontend Review, read-only profile contract, and both CodeQL analyses.

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

## Canonical message-builder validation — exact code head 226b9df60c2cbbd6dcbe8057287bedd0468f420a

All six workflow groups passed on the exact code head above:

- TACResQ CI: 46111579198
- TACResQ Security: 46111579213
- TACResQ QA: 46111579162
- RPC grant contract: 46111579168
- TACResQ Frontend Review: 46111579178
- Read-only profile verification: 46111579220

The Android JVM suite passed both DeviceProofVerifierApiTest and DeviceProofMessageTest. The Android source contract passed after correcting its comment-only false positive; it confirms the key manager, verifier transport, and canonical message builder remain unreferenced by operational app flows. The release APK build and artifact upload passed. Release-signature verification was skipped because signing credentials were not configured.

Artifacts from TACResQ CI run 46111579198:
- Android APK archive: artifact 15250755097, SHA-256 5a6627e7d51b0359fb6e618c9c7202babdc4c25b835aa7c2adfbfa29a186c76d
- Android Lint report: artifact 15250125419, SHA-256 353177204f251ffdf03239334473386eeaed1c9ef6e7b0c82335b51363a4c5ec

No physical emulator/device execution, hosted pooler identity/TLS verification, hosted verifier deployment, or independent human security review was performed.


## Client-side DER boundary hardening — 2026-11-28

The Android transport now mirrors the server verifier's strict DER contract before sending a signature:

- Decode only canonical, unpadded Base64URL and require a byte-for-byte canonical re-encoding.
- Require a DER SEQUENCE of 8–72 bytes with exact sequence length and no trailing bytes.
- Require exactly two minimally encoded, positive DER INTEGER scalars.
- Enforce the P-256 scalar width and group-order range.
- Reject raw 64-byte IEEE P1363 signatures, zero/negative scalars, non-minimal encodings, and padded/non-canonical Base64URL before any HTTP request.

The server remains authoritative and repeats strict DER validation. This client-side check is defense in depth and does not activate the verifier transport or device registry in operational flows. Exact-head CI for this change is pending; no test result is claimed here.
