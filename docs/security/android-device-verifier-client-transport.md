# TACResQ Android device-verifier transport — implementation note

**Status:** Implemented as an isolated Android transport in PR #370. Automated JVM and repository-contract validation is pending on the new PR head. This is not evidence of hosted verifier availability or operational device registration.

## Scope

The Android application now has a dedicated DeviceProofVerifierApi transport for the existing Supabase Edge Function:

- Fixed endpoint: functions/v1/tacresq-device-verifier
- Sends the Supabase publishable key in the apikey header.
- Sends the current caller access token as a Bearer authorization header.
- Adds a fresh W3C traceparent using the existing interceptor.
- Uses bounded request values and validates challenge and verification response shapes.
- Requires HTTPS for non-loopback origins. Plain HTTP is accepted only for localhost/127.0.0.1 test servers.
- Does not install an HTTP body/header logging interceptor.

The transport supports the two server-defined purposes only: registration and session_proof. Registration submits the public SPKI key and bounded device metadata. Session proof does not accept or send a replacement public key.

## Isolation boundary

The class is intentionally not referenced by login, SOS, dispatcher, chat, notification, or Admin code. It is not called by SupabaseTacApi, DeviceProofKeyStore, or any ViewModel. A source contract checks that the transport remains dormant until the hosted and device-level release gates are met.

This is deliberate. A transport client is not proof that the verifier is deployed, that a hosted database connection runs as tacresq_device_verifier, or that a registered device is enforced at sensitive server operations.

## Automated test coverage added

The Android JVM test suite now covers:

- Authenticated POST path, publishable-key and Bearer headers.
- Registration challenge parsing and request shape.
- Registration proof payload, metadata trimming, and result parsing.
- Session-proof payload without public-key replacement.
- Rejection of malformed token inputs before network activity.
- Preservation of HTTP authorization errors.
- Rejection of non-loopback HTTP origins.

The existing Android Keystore contract test additionally checks the fixed endpoint, authentication headers, trace context, HTTPS restriction, absence of request logging, and lack of operational call sites.

## Remaining release gates

1. Run the new JVM tests and source contracts on the exact PR head.
2. Execute Android Keystore instrumentation tests on supported Android emulators/devices; CI compilation alone is insufficient.
3. Verify the hosted Supabase transaction-pooler session_user identity and TLS behavior through a controlled, owner-approved check.
4. Deploy the verifier Edge Function and migration/role boundary together only after explicit production approval.
5. Add a separately reviewed registration/session-proof workflow with recovery and revocation UX.
6. Add server-side checks for selected sensitive operations only after proving that requester SOS and other emergency paths remain available from new devices.
7. Obtain independent security and maintainer review.

No production Supabase migration, role/password, Edge Function deployment, Auth setting, Render configuration, user data, or operational client flow was changed by this transport implementation.
