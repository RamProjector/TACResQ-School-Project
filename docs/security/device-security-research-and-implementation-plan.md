# TACResQ Device Security & Identification — Research and Implementation Plan

**Status (2026-11-28):** Phase 1 private registry foundation, Phase 2 trusted verifier-to-database challenge flow, and an isolated Android Keystore P-256 key manager are implemented in PR #370. Exact PR head `a2e2b7ec4f0abe059398de39e3c77d2b529b1225` has 18 completed check runs: 16 success, one neutral aggregate CodeQL check (the Android and Web/Actions CodeQL jobs succeeded), and CodeRabbit skipped because repository eligibility requirements are not met. The manual emulator workflow remains manual-only and has not been dispatched; instrumentation execution on a physical device/emulator remains unverified. Hosted Supabase remains on migration `20261125030354`; the dedicated verifier role/routines and verifier Edge Function are absent. Hosted transaction-pooler identity/TLS, Android-to-verifier API integration, sensitive-operation enforcement, revocation/recovery UX, independent security/maintainer review, manual Admin UI acceptance, and owner-approved production rollout remain outstanding. Keep the registry unused by operational client flows until all applicable gates pass. No production settings, schema, secrets, data, or services were changed.
**Scope:** Android app, dispatcher/admin web clients, Supabase backend, and Render bridge where applicable  
**Design principle:** Device recognition is a risk signal. Authentication and server-side authorization remain authoritative.

## 1. Objective

Reduce account takeover, stolen-session reuse, cloned-installation abuse, modified-client risk, and unauthorized privileged actions without making emergency reporting dependent on a device passing an integrity check.

Device security must complement—not replace—account authentication, role authorization, RLS, RPC authorization, rate limits, audit logging, and incident response.

## 2. Threat model and controls

| Threat | Control | Important limitation |
|---|---|---|
| Stolen access/session token | Session-to-device association, server-side revocation, short-lived sessions, reauthentication for sensitive actions | Device binding is useful only when the server verifies fresh proof; a client-supplied device ID proves nothing |
| Cloned installation identifier | Per-install public/private key pair and challenge-response signature | Never trust a static installation UUID or device fingerprint |
| Replayed registration/proof | Cryptographically random one-time challenge, short expiry, server-side single-use consumption | A challenge must be consumed atomically |
| Modified or repackaged Android app | Google Play Integrity verification on the server | Integrity verdict is a risk signal, not a substitute for user authentication |
| Rooted/emulated/compromised device | Integrity signals and risk-based restrictions on sensitive operations | Avoid blanket denial; legitimate test builds and accessibility needs require explicit handling |
| Lost/stolen device | User self-service revoke, admin revoke, associated session revocation | Revocation must be checked server-side, not only in the UI |
| Suspicious device/account reuse | Correlated security events, rate limits, alerts and human review | Shared devices/networks are not proof of fraud; do not auto-ban based on a shared fingerprint |
| Privileged account compromise | Step-up authentication, registered-device policy, admin/dispatcher alerts, full audit trail | Device recognition alone must never grant admin access |

## 3. Identification approach

### Android
- Generate a unique key pair per app installation.
- Keep the private key in Android Keystore; request hardware-backed protection when available and record the actual key security level.
- Register only the public key, key algorithm, app version, platform, and user-visible device label.
- Prove possession using a fresh server challenge signed by the installation key.
- Verify Google Play Integrity tokens on the server using Google's documented verification flow; never accept a client-parsed verdict.
- Treat unsupported integrity, emulator, and development builds as explicit policy states—not silent passes.

### Web / dispatcher / admin
- Associate a browser session with a server-issued device record only after authentication and proof of possession.
- Prefer WebAuthn/passkeys for phishing-resistant step-up authentication where supported.
- Do not claim browser fingerprints are stable, unique, or unforgeable.
- Require fresh authentication for role changes, device revocation, account recovery, and other high-impact administrative actions.

### iOS future support
- If an iOS client is introduced, evaluate Apple App Attest/DeviceCheck. Do not claim support until server-side attestation verification and replay tests exist.

## 4. Data model foundation

The initial migration establishes private, backend-only tables:

- `user_devices`: profile ownership, random installation identifier, platform/model/app metadata, public key, algorithm, trust state, registration/last-seen/verification timestamps, and revocation metadata.
- `device_sessions`: association between a registered device and a Supabase auth session, including expiry and revocation.
- `device_challenges`: short-lived challenge hashes for registration, session proof, and step-up. Raw challenges are returned once and never stored.
- `device_security_events`: append-only event stream for registration, proof, integrity, revocation, and suspicious activity.
- `device_integrity_checks`: minimal server-verified integrity summaries, not raw provider tokens.

The migration intentionally gives no direct table access to `anon` or `authenticated`. The tables remain unusable by clients until narrowly scoped server verification functions are implemented and tested. Private keys, access tokens, refresh tokens, raw attestation tokens, and raw challenge values must never be stored in these tables or logs.

## 5. Role-sensitive policy

- **Requester:** permit emergency request submission from a newly recognized device after normal account authentication. Notify the account owner and apply proportionate abuse controls; do not make device integrity a prerequisite to requesting help.
- **Responder:** register installation key; require current device proof for sensitive operational actions; alert on unfamiliar devices and revoke lost devices.
- **Driver:** same as responder, with stronger step-up for dispatch/transport-sensitive actions where the workflow warrants it.
- **Dispatcher:** registered browser/device, shorter privileged session policy, and step-up authentication for assignment overrides and operational controls.
- **Admin:** phishing-resistant step-up where available, registered device policy, reauthentication for role/security changes, and immediate session/device revocation controls.

## 6. Admin and user experience

### User Profile / Settings → My Devices
- List active, restricted, and revoked devices with platform, user-provided name, last seen, and last verified.
- Allow a user to revoke their own device after reauthentication.
- Explain that a device label is descriptive and does not expose IMEI, serial number, MAC address, or invasive hardware fingerprints.
- Provide lost-device recovery that does not lock a legitimate requester out of emergency reporting.

### Admin → Security & Countermeasures → Device Security
- Search/filter devices and review verification history.
- Show account, role, platform, app version, trust state, last activity, and risk events.
- Allow authorized revoke/terminate-session actions with a required reason and audit entry.
- Do not expose private keys, session tokens, raw attestation tokens, or unnecessary precise location.
- Require step-up authentication for high-impact actions; consider dual approval for bulk or emergency-wide controls.

## 7. Implementation sequence

1. **Foundation (this PR):** private schema, constraints, indexes, no client table grants, and migration-history alignment.
2. **Server challenge service:** issue purpose-bound, short-lived random challenges; persist only challenge hashes; atomically consume once; enforce profile/session ownership and rate limits.
3. **Android key proof:** Keystore key generation, public-key registration, signed challenge verification, replay and cloned-ID tests.
4. **Session controls:** bind verified sessions to device records; revoke one device or all account sessions; enforce revocation at server boundaries.

   - **Current partial control:** Laravel exposes authenticated `POST /api/logout-all` to revoke all Sanctum personal access tokens for the account. This is account-wide recovery, not per-device revocation; Supabase Auth sessions and device registry associations are not yet bound or revoked by this endpoint.
5. **Android integrity:** verify Play Integrity server-side; map verdicts to policy and retain minimal results.
6. **Web privileged-device support:** browser registration, WebAuthn/passkeys step-up, session lifecycle and admin/dispatcher policy.
7. **Admin and user UI:** My Devices and Device Security console with auditable actions.
8. **Detection and response:** velocity/risk signals, alerting, safe throttling, investigation workflow, and recovery.
9. **Verification:** migration replay; authorization tests for every role; forged IDs; replayed challenges; wrong-account keys; revoked devices/sessions; expired challenges; modified app verdicts; rate limits; emergency-request availability from a new device.

Do not collapse these stages into a single broad SECURITY DEFINER function. Prefer narrow RPCs or Edge Functions with pinned execution context, explicit authorization, bounded inputs, and no client-supplied trust decisions.

## 8. Acceptance criteria

- A device ID or fingerprint alone cannot authenticate, authorize, or elevate a user.
- Only a fresh server-verified signature from the registered public key can prove installation-key possession.
- Challenges are random, purpose-bound, short-lived, single-use, and replay-resistant.
- Private keys and bearer tokens never leave the client/provider boundary or enter database logs.
- Revoking a device invalidates its associated sessions at server-enforced checkpoints.
- Users can see and revoke their own devices; admins can revoke devices/sessions only with authorization, reauthentication, reason, and audit.
- A new device does not prevent a legitimate requester from submitting an emergency report.
- Integrity failures trigger risk-based controls, not an automatic account ban.
- Admin/dispatcher privileged actions require stronger verification than ordinary requester actions.
- All security decisions are tested against the actual mobile, web, Supabase, and Render request paths.

## 9. Explicit non-goals

- No IMEI, serial-number, MAC-address collection, invasive browser fingerprinting, or covert tracking.
- No claim that Play Integrity, App Attest, WebAuthn, or a device label independently proves the human user's identity.
- No automatic bans based only on shared device/network identifiers.
- No paid third-party device intelligence or malware product without approval.
- No production migration, service deployment, or security-policy activation as part of this foundation PR.

## 10. Research references

- NIST SP 800-63B-4, *Digital Identity Guidelines: Authentication and Authenticator Management*: https://pages.nist.gov/800-63-4/sp800-63b.html
- NIST SP 800-63B-4, session and authenticator lifecycle guidance: https://pages.nist.gov/800-63-4/sp800-63b.html
- OWASP Mobile Application Security Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Mobile_Application_Security_Cheat_Sheet.html
- OWASP Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- Android Developers, Play Integrity API: https://developer.android.com/google/play/integrity
- Apple Developer, DeviceCheck and App Attest: https://developer.apple.com/documentation/devicecheck
- NIST SP 800-63B-4 Digital Identity Guidelines: https://pages.nist.gov/800-63-4/

Research must be rechecked against current platform documentation before implementing provider-specific verification. Provider verdict formats and API requirements can change.


## 11. Server challenge protocol contract (implementation prerequisite)

This contract narrows the challenge-service work before any endpoint or client UI is added. It is a design specification, not evidence that verification is implemented.

### Challenge issuance

- Require a valid Supabase access token. Verify its signature, issuer, audience, expiry, and subject on the trusted server; never accept a profile ID or auth-session ID as authoritative client input.
- Resolve the profile from the verified subject using the existing account-to-profile mapping. Read the Supabase `session_id` claim from the verified JWT and bind it to the challenge. Do not expose or grant client access to `auth.sessions`.
- Accept only the enumerated purposes: `registration`, `session_proof`, and `step_up`. Enforce purpose-specific device requirements: registration has no existing device ID; session proof and step-up require an active device owned by the verified profile.
- Generate 32 cryptographically random bytes with the platform CSPRNG. Return the raw challenge exactly once as unpadded base64url, together with its UUID, purpose, and expiry. Persist only lowercase hexadecimal SHA-256 of the raw 32 bytes.
- Use a two-minute validity window for newly issued challenges. The existing ten-minute database maximum is a hard upper bound, not the recommended operational lifetime.
- Apply per-account, per-session, and per-IP issuance limits at the trusted API boundary. Do not log the raw challenge, authorization header, JWT, or signature.

### Signed message and key handling

The client signs a fixed UTF-8/ASCII message, not reserialized JSON. The exact byte sequence is:

```text
TACRESQ-DEVICE-PROOF
v1
{challenge_id}
{purpose}
{profile_id}
{session_id}
{device_uid_or_dash}
{challenge_base64url}
```

Each value is the exact server-issued value; lines are separated by a single LF byte, with no trailing LF. For registration, `device_uid_or_dash` is the single hyphen character. The server reconstructs this message from its persisted challenge and verified request context; it must not trust client-supplied profile, session, purpose, or device ownership values.

- Use P-256 / ES256 for the first Android Keystore implementation. Store the public key as a bounded, validated SubjectPublicKeyInfo (X.509) DER encoding in base64; never accept private-key material.
- Android's `SHA256withECDSA` signature is ASN.1 DER encoded. Define the API signature field as base64url of those DER bytes and convert/validate it explicitly at the verifier boundary. Do not silently mix DER ECDSA with the 64-byte JOSE ES256 representation.
- Reject malformed keys, unsupported curves/algorithms, oversized inputs, invalid DER, and signatures that do not verify. A key's existence or a client-supplied `trust_status` is never proof.

### Atomic consumption and transaction behavior

1. Load the challenge by UUID and verify that it exists, is unconsumed, unexpired, and matches the verified profile, Supabase session, purpose, and expected device association.
2. Recompute SHA-256 over the decoded 32-byte challenge and compare it with the stored hash using a constant-time comparison.
3. Verify the signature against the registered or proposed public key and the exact signed message. For registration, validate the proposed public key before associating it with the new device record.
4. In one database transaction, atomically consume the challenge with a conditional update equivalent to:

   ```sql
   UPDATE public.device_challenges
      SET consumed_at = clock_timestamp()
    WHERE id = :challenge_id
      AND profile_id = :verified_profile_id
      AND auth_session_id = :verified_session_id
      AND purpose = :expected_purpose
      AND challenge_hash = :expected_hash
      AND consumed_at IS NULL
      AND expires_at > clock_timestamp()
   RETURNING id;
   ```

   Exactly one returned row means consumption succeeded. Zero rows means expired, replayed, mismatched, or concurrently consumed; reject the proof. Never perform a read-then-write consumption without this conditional update.
5. In that same transaction, persist the registration/session association or verification timestamp and append the corresponding security event. If any required write fails, roll back the transaction so a partially accepted proof is not recorded.
6. Return a generic proof failure to the client. Keep detailed internal rejection reasons in bounded security events, without storing challenge values, signatures, tokens, or private data.

The server may perform cryptographic verification before the atomic update to avoid consuming a challenge for an invalid signature; the conditional update is still mandatory because concurrent valid submissions must have exactly one winner. A failed signature must not create or activate a device.

### Session binding and revocation boundary

- A successful `session_proof` binds only the verified Supabase `session_id` to the verified device and profile. Do not bind by access-token text, refresh-token text, IP address, or user-agent fingerprint.
- Enforce the device-session association at server-side sensitive-operation boundaries. A client-side badge or cached device state is not enforcement.
- For sensitive requests, validate that the JWT is valid and that the referenced Supabase session still exists and is not revoked, then confirm the device-session association is active. Do not query `auth.sessions` from a client or expose it through a public RPC.
- Revoking a device must revoke its associated registry session rows and make subsequent protected operations fail. Supabase refresh-token revocation and already-issued JWT expiry are separate concerns; do not claim immediate global revocation unless the request path performs the server-side session check.
- Do not make device proof a prerequisite for requester emergency submission. Device proof can add risk context and trigger proportionate abuse controls, but must not block a legitimate request for help.

### Required negative and concurrency tests

Before enabling any endpoint, cover: expired challenge; replay after success; two concurrent submissions of the same valid proof (exactly one succeeds); wrong profile; wrong Supabase session; wrong purpose; wrong device; altered challenge bytes; malformed/oversized public key; unsupported curve; malformed DER signature; invalid signature; revoked device; revoked Supabase session; failed event insert rolling back acceptance; and rate-limit exhaustion. Also verify that no challenge, signature, bearer token, or private-key material appears in application logs or event details.

**Release gate:** keep the registry private and unused by client flows until the trusted verifier, transaction boundary, server-side session/device enforcement, and the negative/concurrency test suite are implemented and reviewed. Do not add Device Security controls to the admin portal before that gate passes.


## 12. Repository integration boundary (verified source review)

The current repository has two distinct authentication/data paths. They must not be conflated when implementing device proof:

- The Laravel routes under `backend/src/routes/api.php` protect the legacy API with `auth:sanctum` and Laravel `User` records. Its `/login`, `/register`, `/logout`, and `/logout-all` endpoints issue or revoke Laravel Sanctum personal-access tokens. Those tokens are not Supabase Auth sessions and cannot be written into `device_sessions.auth_session_id`.
- The optional Laravel Supabase bridge uses `App\\SupabaseBridge::getUser()` to call Supabase Auth `/auth/v1/user` with the caller's bearer token. This verifies the token with Supabase and returns the Auth user, but the current method does not return verified JWT claims such as `session_id`. It must not decode the bearer token without independently verifying its signature, issuer, audience, and expiry.
- The bridge's `callRpc()` forwards the caller's JWT and the publishable key to an allowlisted PostgREST RPC. It does not use a service-role key for business RPCs. The Laravel bridge is intentionally stateless and does not provide the PostgreSQL transaction connection needed to atomically consume a challenge and update the private registry.
- The dispatcher/admin web client and the normal Android Supabase transport call Supabase RPCs directly with the signed-in user's JWT. They do not automatically traverse Laravel route middleware. A Laravel-only device check would therefore not protect those direct request paths.

### Consequence for implementation

Do not implement the device verifier as a Laravel Sanctum route, do not bind a Sanctum token ID to a Supabase session, and do not assume that adding a Laravel bridge endpoint protects direct Supabase RPC calls.

The preferred first implementation boundary is a narrowly scoped Supabase-side verifier path that preserves the authenticated Supabase caller context and performs all registry mutations in one PostgreSQL transaction. The verifier must obtain the caller's verified subject and session claim from Supabase's validated request context (for example, the database's verified `auth.uid()` and `auth.jwt()` context for a user-JWT RPC), not from caller-supplied IDs. If an Edge Function is used for rate limiting or cryptographic processing, it must forward the original user JWT into the narrowly scoped database operation so the database can independently enforce the caller context; never replace it with service-role identity and then trust arbitrary profile/session parameters.

Before choosing the exact implementation, inspect the current Supabase project configuration, installed Edge Function conventions, database extension availability, and function privilege patterns. The database function must pin `search_path`, validate the expected role/subject/session/purpose/device relationship, use conditional single-use challenge consumption, and keep table grants private. Do not add a broad SECURITY DEFINER RPC or expose the registry tables to `authenticated`.

### Required integration inventory before coding

1. Enumerate every direct Supabase RPC path used by Android and the admin/dispatcher web client, then identify which operations actually need device proof. Do not make emergency request creation dependent on proof.
2. **Profile mapping confirmed from the migration source:** `public.profiles.auth_user_id` is the UUID foreign key to `auth.users(id)`, while `public.profiles.id` is the bigint returned by `api_me()` and referenced by the device registry's `profile_id`. Resolve the registry profile by matching the verified `auth.uid()` to `profiles.auth_user_id`, then use that row's bigint `id`. Never use the Supabase Auth UUID or Laravel Eloquent user ID as `profile_id`.
3. Inspect the existing Supabase migration conventions for function ownership, grants, fixed search paths, and tests. Keep verifier functions narrowly callable and deny direct table access.
4. Decide how the verifier obtains a trusted session ID and how revoked sessions are checked at sensitive-operation boundaries. Supabase Auth `/user` validation alone does not expose the `session_id` claim in the current bridge implementation.
5. Add tests for both the direct-Supabase path and optional bridge path, including a proof that a caller cannot use a different profile/session/device ID and that direct calls cannot bypass any intended enforcement.

**Schema detail confirmed:** `device_sessions.auth_session_id` is `NOT NULL` and unique; `device_challenges.auth_session_id` is nullable in the foundation migration. A registration challenge may have no device ID, but issuance and proof still need to bind it to a verified Supabase session. Do not interpret the nullable challenge column as permission to accept an unbound session.

**Current implementation status:** the PR now contains an authenticated Edge Function challenge endpoint, P-256 signature verification, a dedicated non-login-by-default PostgreSQL verifier role, narrowly granted private transaction functions, live Auth-session checks, atomic device/session binding, and append-only event writes. The disposable local integration suite passes replay, cross-user/session, wrong-key, altered-challenge, concurrency, expiry, rate-limit, stale-session, bypass, and transaction-rollback cases. Sensitive-operation enforcement is still absent: a verified device association is not yet consulted by incident/dispatch/chat/other protected operations. The Laravel bridge and Sanctum logout endpoints remain separate identity paths. Keep the registry private and do not connect it to operational client flows until enforcement and independent review are complete.


## 13. Client RPC inventory and device-proof scope (verified branch source review)

### Current direct Supabase callers

**Android (app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt)**

The default Retrofit transport calls Supabase PostgREST directly with the current user's bearer token. The opt-in Render bridge covers only a subset; several sensitive operations intentionally bypass it. The current direct RPC surface includes:

- Identity/profile and field operations: api_me, api_set_duty_status, api_my_incidents, api_all_incidents, api_units, api_active_responder_locations.
- Requester lifecycle: api_create_incident_v4, api_cancel_my_incident, api_update_my_incident.
- Dispatch and field response: api_update_incident_status, api_assign_incident, api_assign_incident_team, api_release_incident_assignment, api_claim_incident, api_claimable_incidents, api_set_incident_claimable, api_record_location_v2.
- Notifications and device messaging: api_my_notifications, api_my_notification, api_mark_notification_read, api_register_fcm_token, api_unregister_fcm_token.
- Incident chat: api_my_incident_messages, api_send_incident_message, api_send_incident_image_message.

Android also reads RLS-protected assignment history and uses Supabase Storage directly for incident image uploads/downloads. Those paths are not protected by a Laravel-only gate either.

**Web admin portal (backend/web/src/lib/supabase.ts)**

The current admin/dashboard RPC callers include api_me, api_all_incidents, api_units, api_active_responder_locations, api_assign_incident, api_admin_list_users_page, api_admin_set_user_role, api_admin_analytics_summary, and api_admin_list_audit_logs. Dependency health is a direct PostgREST fetch. This inventory is the observed current source surface, not a guarantee that every legacy function remains used.

### Existing Google Authenticator control must remain authoritative for Admin

The branch contains migration 20261122123000_require_totp_for_admin_rpcs.sql and AdminMfaGate.tsx contract coverage. The migration requires a Supabase AAL2 JWT with a TOTP entry in amr for the four Admin RPC wrappers (user pagination, role change, audit logs, analytics) and adds a restrictive AAL2/TOTP policy to direct audit-log reads. The previous internal implementations are renamed and their client grants revoked; the legacy unbounded user-list RPC remains unavailable.

Consequences:
- Keep Google Authenticator TOTP as the Admin step-up factor. Do not add a second device-signature prompt to those same four Admin operations merely to duplicate MFA.
- A registered device key is not equivalent to AAL2, a TOTP factor, or a verified user identity. Never allow device proof to satisfy the SQL Admin MFA gate.
- Re-check that the TOTP migration is applied through the approved migration process before treating live Admin RPCs as protected; source presence alone is not deployment evidence.

### Recommended device-proof boundaries

Device proof should initially establish and maintain a cryptographic association between one app installation, one TACResQ profile, and one verified Supabase Auth session. It should not become a blanket prerequisite for ordinary emergency workflows.

| Operation group | Initial device-proof policy | Reason |
|---|---|---|
| Device registration, key rotation, device revocation, session-to-device binding | **Require fresh proof** from the relevant key or a separately authenticated recovery path | These operations change the device trust relationship itself. |
| Admin user-role changes, Admin audit/analytics/user-list RPCs | **Keep TOTP AAL2 mandatory; device proof is supplementary only** | Existing database TOTP enforcement is the authoritative Admin step-up control. |
| Requester SOS creation and emergency location submission | **Never require device proof** | A missing, unsupported, lost, or newly installed device must not block a request for help. Preserve existing authenticated requester/role validation and idempotency controls. |
| Responder claim, dispatcher assignment/release, incident status progression, responder location update | **Do not require an interactive step-up in the first rollout** | These are time-sensitive operational actions. Continue enforcing current server-side role, assignment, duty, lifecycle, and incident-lock checks. Device trust may inform monitoring/risk review, but must not replace those checks. |
| Ordinary incident reads, notifications, and authorized chat | **No new device-proof gate initially** | Avoid breaking dispatch visibility, incident coordination, and notification navigation. Existing RLS and RPC authorization remain mandatory. |
| Password recovery, account recovery, or a future change to authentication factors | **Require the provider's verified reauthentication/MFA or recovery flow; evaluate device proof as an additional signal** | A device key alone cannot recover an account or override Supabase Auth. |

This is a deliberately conservative first rollout. If later threat modeling identifies a specific high-risk operation that needs device step-up, add it as a narrow server-enforced policy with a fallback/recovery route and emergency-safe failure behavior. Do not implement UI-only checks.

### Remaining implementation and release gates

1. Validate the dedicated verifier role through the actual hosted Supabase shared transaction pooler, including whether `session_user` remains `tacresq_device_verifier` for the private-function gate. Local direct-PostgreSQL success does not prove pooler identity behavior.
2. Provision the role password and Edge Function connection secret only through an approved, controlled deployment procedure; never store them in Git or client configuration.
3. Execute the added Android Keystore P-256 instrumentation tests on supported Android 8.0+ hardware/emulators, confirm SPKI/DER compatibility with the server verifier, and complete key invalidation/rotation recovery. The isolated key manager now exists; do not reuse the app's AES-GCM session-encryption key.
4. Add server-enforced session/device checks only to individually justified sensitive operations, with explicit recovery paths. Preserve requester SOS, emergency location submission, chat, responder claim, dispatcher assignment, and Admin TOTP/AAL2 behavior unless a separately reviewed policy establishes a safe exception.
5. Implement device revocation and session-revocation lifecycle with audit records, then user-facing My Devices and protected Admin Device Security screens.
6. Add a trusted per-IP rate limit if the deployed Edge platform exposes a verified client address; never trust arbitrary forwarding headers.
7. Obtain independent security review and controlled hosted validation before deploying migrations, enabling the Edge Function, provisioning the role, or wiring clients.

**Scope decision:** keep this verifier as an isolated, unused registration/session-proof service until the above gates pass. Do not add device-proof parameters to emergency/dispatch RPCs or turn device recognition into an SOS prerequisite.


## 14. Cryptographic runtime feasibility (repository-only review)

The current migration baseline enables pgcrypto. The repository now includes `supabase/functions/tacresq-device-verifier/index.ts`, shared WebCrypto P-256 primitives, a local Edge Function integration script, and a dedicated advisory CI job. There is still no `supabase/config.toml`; no per-function JWT-verification override is committed, so retain the platform's default JWT verification behavior and keep the handler's own `getClaims` verification. The older `tacresq-notify` function remains a separate service-role integration and is not part of this verifier's trust boundary.

pgcrypto is useful for secure random bytes and SHA-256 challenge hashing, but its presence does not establish a supported P-256/ES256 signature-verification primitive. Do not implement a home-grown ECDSA verifier in SQL or mistake digest/hash support for public-key signature verification.

**Recommended technical split for the first implementation:**
- A trusted runtime with a maintained cryptographic library performs public-key signature verification. A Supabase Edge Function using the Web Crypto API is a viable candidate, but it would be a new deployment surface and must be reviewed for project config, JWT verification settings, local test setup, secret handling, rate limiting, and operational observability before adoption.
- The original user's Supabase JWT must remain the caller identity when the trusted runtime invokes the narrowly scoped database operation. The database independently resolves auth.uid(), profile id, and session_id from the validated request context; it must not trust profile/session identifiers supplied by the client or by a service-role-only request.
- PostgreSQL performs the atomic challenge consume, key/device/session ownership checks, device/session state changes, and security event insertion in one transaction. The raw challenge and signature must not be persisted or logged.
- Start with one key algorithm and one platform path. The current schema's allowance for both Ed25519 and ES256 is not evidence that either verifier is implemented. ES256 must explicitly handle the Android Keystore ECDSA signature encoding expected by the chosen Web Crypto/library interface; test the DER-to-raw conversion boundary rather than assuming the formats match.
- Keep all service credentials out of Android and browser bundles. Do not introduce a paid third-party verification service or a new hosted platform without approval.

**Decision gate before implementation:** confirm the Supabase project's actual function deployment configuration and local CLI availability, select the supported verifier runtime, and prove with an authenticated integration test that the original user JWT reaches Postgres with the expected subject and session claim. If that cannot be demonstrated, keep the registry private and do not ship a partially trusted bridge.



## 15. Repository verification: test harness and Android key capability

A focused source review on this branch confirms the following implementation facts:

- `tools/test_supabase_migration_replay.sh` creates minimal `auth.uid()` and `auth.jwt()` compatibility functions. The latter reads `request.jwt.claims`, but the replay fixtures do not currently set or assert a `session_id` claim, exercise missing/malformed session claims, or test device-session ownership. The script is a migration replay harness against a supplied PostgreSQL database; it is not a running Supabase Auth/PostgREST integration environment.
- No `supabase/config.toml` exists on this branch; this is intentional at present because no function-specific JWT override is required. The device-verifier function and local Supabase function workflow are now checked in and exercised by the advisory CI lane. The function is not deployed.
- Android `app/build.gradle.kts` sets `minSdk = 26`. `app/src/main/java/com/tacresq/app/data/local/SessionStore.kt` creates one Android Keystore AES key (`tacresq_session_aes_gcm_v1`) for AES/GCM/NoPadding encryption. That key is symmetric and is used for local data encryption; it is not a signing key and must not be reused as the device identity key.
- The reviewed Android session store does not implement a P-256 key pair, public-key export, `SHA256withECDSA` signing, signature encoding conversion, key invalidation handling, or device-key rotation. Existing Keystore use proves only the local AES-GCM storage path.
- The migration replay harness's `auth.jwt()` stub is suitable for deterministic SQL claim tests by setting `request.jwt.claims` within a transaction, but that alone cannot prove that a real signed Supabase JWT's session claim survives an Edge Function/proxy/PostgREST hop.

### Required next implementation gates

1. Extend isolated SQL contract coverage to set a synthetic `sub` and `session_id` in `request.jwt.claims`, then assert correct profile/session binding and rejection of absent, malformed, wrong-profile, and wrong-session claims. These are SQL-context tests, not substitutes for a real Supabase integration test.
2. Establish a local Supabase project configuration and function test/deploy workflow only after confirming the actual project's configuration and approved deployment target. Do not add an assumed Edge Function and describe it as deployable without this setup.
3. Add a distinct Android P-256 signing key alias and explicit signing/public-key serialization contract. Keep the existing AES-GCM key and encrypted session storage separate. Validate the exact Android Keystore signature bytes against the selected verifier's expected representation, including malformed DER and invalidated-key cases.
4. Add a real authenticated integration test proving that the original user's verified JWT reaches the database with the expected `auth.uid()` and `session_id`. Do not infer this from the SQL replay stub or from decoding an unverified token in Laravel.
5. Keep all device registry tables backend-only and unused by app/web flows until these gates, atomic challenge consumption, authorization tests, and operational deployment review are complete.

**Current status:** these findings are repository inspection only. No asymmetric device key, verifier endpoint, Supabase configuration, migration application, or production deployment was added by this review.


## 16. Supabase Auth session-claim documentation check

The current Supabase Auth documentation identifies `session_id` as a required access-token claim: it is a UUID identifying the user's Auth session and correlates with the primary key in `auth.sessions`. Supabase also documents that a session row is removed when the session is terminated by sign-out, and that sensitive endpoints can check whether the JWT's `session_id` still has a corresponding session row.

Implementation implications:
- The protocol's use of the verified `session_id` claim is aligned with the documented Supabase Auth token contract; it is not an application-defined custom claim.
- The database must read the claim from the validated request context (for example, `auth.jwt() ->> 'session_id'`) and reject absent or invalid UUID values. Never accept a caller-supplied session ID.
- A future sensitive-operation gate may check the session row's existence to detect a terminated session, but access to `auth.sessions` must remain server-side and narrowly scoped. Do not expose that table or its contents to client roles.
- Documentation confirms the claim's intended meaning; it does not prove that this project's deployed Auth configuration, PostgREST path, Laravel bridge, or any future Edge Function forwards the original user JWT unchanged. An authenticated integration test is still required before device binding is enabled.

Official references checked 22 November 2026:
- Supabase, User sessions: https://supabase.com/docs/guides/auth/sessions
- Supabase, JWT Claims Reference: https://supabase.com/docs/guides/auth/jwt-fields
- Supabase, Row Level Security and `auth.jwt()`: https://supabase.com/docs/guides/database/postgres/row-level-security


## 17. Live Supabase project inspection: existing Edge Function

A read-only inspection of the connected Supabase project `TACResQ-School-Project` (project ref `kttguamhznoflzagfutr`, region `ap-southeast-1`) on 22 November 2026 corrected the earlier repository-only assessment:

- The live project is `ACTIVE_HEALTHY` and has one active Edge Function: `tacresq-notify`, version 2, with `verify_jwt = true`.
- The earlier source-recovery snapshot predates the checked-in `tacresq-device-verifier` Edge Function and its local integration workflow. Current implementation and validation details are recorded in Section 20.
- The deployed notification function uses `admin.auth.getUser(userToken)` to validate the caller, then uses a service-role Supabase client for profile/incident/FCM queries. This is a notification-specific privileged workflow. It does not implement device challenge issuance, P-256 signature verification, challenge replay protection, or session-to-device binding.
- Important for the future verifier: validating a bearer token with `auth.getUser()` through an admin client does not by itself establish that a subsequent service-role database query runs with the caller's `auth.uid()` and `auth.jwt()` context. A verifier must deliberately preserve the original user JWT on the database operation or use a narrowly scoped, independently verified server-side transaction design. Do not assume the notification function's service-role query pattern is sufficient for session-bound proof.
- The live migration history includes `device_security_registry_foundation`, `bound_device_registry_metadata`, and `require_totp_for_admin_rpcs`. This confirms those migrations are recorded in the connected project; it does not prove that device-verifier behavior exists. The device registry remains backend-only and currently has no client-facing verifier flow.

**Correction:** earlier notes saying there was no existing Edge Function convention were too broad. There is one deployed notification function, but no checked-in function source or device-verification implementation. Future work must first bring the deployed function source under repository version control through a reviewed, non-destructive reconciliation; do not overwrite or redeploy it as part of device-proof work.


### Source reconciliation follow-up

The currently deployed `tacresq-notify` version 2 entrypoint has now been copied without behavioral edits into `supabase/functions/tacresq-notify/index.ts` on this branch. The deployed source digest observed during retrieval was `3a0a08f59ebb34d05034be0deabaa8ddffd276aec76f3c1623bb1c74f2cbb631`; the checked-in file was fetched again after commit and confirmed to contain the same 7,167 UTF-8 characters and the expected `Deno.serve` entrypoint.

This is source recovery/version control only. It was **not** deployed, and no function configuration or secrets were changed. The existing function still does not implement device proof. Before any future deployment, compare the complete source and runtime settings against the live project, pin the Supabase JS dependency deliberately, and add isolated function tests. Do not deploy this mirror merely because it is now in Git.

## 13. Deployed notification Edge Function reconciliation (2026-11-22)

The live Supabase project exposes one active Edge Function, `tacresq-notify`, with JWT verification enabled. Its deployed source was recovered read-only and mirrored at `supabase/functions/tacresq-notify/index.ts`; the mirror is source recovery, not a deployment or endorsement of the function as the current delivery path.

### Verified behavior of the recovered function

- It accepts POST requests, validates the bearer token with Supabase Auth `getUser`, resolves the caller's `profiles` row, loads an incident using the service-role client, and manually checks role/ownership before selecting recipient FCM tokens.
- It creates a Google OAuth service-account assertion in the Edge runtime and sends FCM HTTP v1 messages directly.
- It does not claim rows from the repository's `fcm_delivery_attempts` queue, create delivery-ledger attempts, or use the queue's lease/retry/backoff workflow.
- It has no explicit request-body byte limit, per-user/per-incident send rate limit, or idempotency key. A valid requester can repeat the `incident_created` call for their own incident; a dispatcher can repeat `incident_assigned` for an assigned incident. Those checks establish role/relationship but do not make delivery idempotent.
- The `incident_assigned` branch checks that the caller is a dispatcher and that the incident is assigned, but does not bind the caller to a specific assignment event or assignment actor. This is not evidence of an incident-assignment privilege bypass—the database/RPC path authorizes the assignment—but it means the function itself is not an event-authenticated delivery trigger.
- It deletes registrations after an FCM 404 or a response body containing `UNREGISTERED`; this is a separate token-cleanup implementation from the canonical Laravel sender.

### Current repository delivery path

The repository's Laravel `FcmNotificationDispatcher` claims bounded delivery work through `api_claim_fcm_delivery_batch`, sends using the shared `FcmHttpV1Client`, and records attempt outcomes, retry timing, invalid-token cleanup, and a ten-minute claim lease. Persistent inbox notifications are the durable source of truth; FCM is supplemental. This is materially more complete than the direct-send Edge Function path.

### Decision and safety boundary

- Treat the recovered Edge Function as a legacy/parallel implementation pending an explicit caller and deployment audit. Do not add new callers or route new notification events through it.
- Keep the Laravel queue/attempt-ledger pipeline as the repository's canonical FCM delivery path unless a later architecture decision replaces it.
- Before disabling or deleting the live function, trace all deployed callers and verify that no external workflow depends on it. No function was disabled, deleted, redeployed, or reconfigured during this review.
- Before any future reuse, add bounded body parsing, per-account/per-incident rate limits, event provenance or server-generated queue claims, idempotency, bounded recipient/token batches, and tests for repeated calls, wrong roles, wrong incident ownership, revoked sessions, and invalid-token cleanup.
- The function uses a service-role client after validating the user token. Its authorization therefore depends on the manual checks in this function, not on caller-scoped RLS. Preserve this distinction in every review; do not assume `auth.uid()` or `auth.jwt()` reflects the caller inside service-role PostgREST operations.
- No live Supabase configuration, secrets, function deployment, notification delivery, or production data was changed.

**Release gate:** the active Edge Function and the Laravel queue worker must not both deliver the same event family. Establish the actual production caller/scheduler topology before enabling, disabling, or changing either path.



## 18. Verified Supabase JWT context: local integration proof (required before device writes)

Current Supabase Edge Function guidance explicitly supports forwarding the caller's Authorization header into a publishable-key Supabase client so PostgREST evaluates the request under the original user's JWT and RLS context. The current official guide also documents the newer @supabase/server withSupabase({ auth: 'user' }) pattern, which provides a caller-scoped ctx.supabase and verified ctx.jwtClaims. The current TACResQ function is a legacy supabase-js@2 implementation, so use the compatible, documented caller-scoped client pattern for the first isolated proof rather than silently changing runtime dependencies.

References (rechecked 2026-11-27):
- Supabase, [Integrating With Supabase Auth](https://supabase.com/docs/guides/functions/auth-legacy-jwt) — forward the request Authorization header into a publishable/anon-key client; queries then execute in the caller's RLS context.
- Supabase, [Securing Edge Functions](https://supabase.com/docs/guides/functions/auth) — authenticated user mode, verify_jwt = true, caller-scoped ctx.supabase, and verified ctx.jwtClaims.
- Supabase, [JWT Claims Reference](https://supabase.com/docs/guides/auth/jwt-fields) — the signed Auth JWT includes sub and session_id claims.

### Isolated test topology

Do not run this proof against the hosted TACResQ project and do not create a production test RPC. Use a disposable Supabase local stack with synthetic users and a temporary probe RPC that is created only inside the disposable database and is not included in production migrations.

1. **Completed in PR #370:** pin the Supabase CLI, start an isolated local Auth/PostgREST/Edge Runtime stack, prove signed JWT subject/session propagation, provision an ephemeral dedicated verifier login, and exercise the actual Edge Function against disposable PostgreSQL. This is local evidence only; it does not prove hosted pooler identity behavior or deployment readiness.
2. Start local Supabase in an isolated CI job; assert the resolved API URL is loopback/local before creating users or issuing any request. Fail closed if the URL contains the hosted TACResQ project ref or is not a local address.
3. Create two disposable Auth users through the local Auth admin API using only the local service-role secret in the CI process. Sign each user in through the local Auth API using the publishable key and obtain real access JWTs. Keep all credentials in process memory; never print or upload them.
4. Create a temporary SQL probe function in the local database only. It must return auth.uid(), auth.jwt() ->> 'session_id', and auth.jwt() ->> 'role'; grant execute only to authenticated. The probe must not exist in the migration directory or any deployed schema.
5. Run a temporary test Edge Function with JWT verification enabled. In its request handler, construct a user-scoped client using the local publishable key and the incoming Authorization header, then call the probe RPC. Do not instantiate or use a service-role client for this call.
6. Invoke the function with User A's real access JWT and assert the SQL-returned subject equals User A's Auth UUID, the SQL-returned session ID equals the session_id claim from the actual signed JWT, and the SQL role is authenticated.
7. Repeat for User B and assert there is no cross-user/session contamination when requests execute concurrently.
8. Negative cases: no Authorization header, malformed token, expired token, anonymous/publishable key without a user JWT, and a body containing forged profile_id/session_id values must never produce an authenticated probe result. The database-returned claims must remain unchanged when the body/header contains spoofed IDs.
9. Verify the Edge Function's configured JWT gateway rejects invalid JWTs; do not disable JWT verification for this function. Ensure all test users, local containers, temporary probe functions, and volumes are destroyed even after failure.

### Device challenge verifier acceptance tests after context proof

- Verify the user JWT is forwarded to PostgREST exactly as received; only the publishable key is supplied as apikey.
- The database function derives the owner from auth.uid() and the session from auth.jwt()->>'session_id'; no caller-provided profile/session field can override either.
- Check that the session ID exists in auth.sessions and is not revoked/expired according to the selected session-validity policy before binding it.
- Use one short-lived purpose-bound challenge. Challenge consumption, signature-verification result recording, device/session binding, and append-only event insertion must be atomic. Two concurrent submissions of the same challenge must result in exactly one success.
- Negative tests cover wrong user, wrong session, wrong device key, wrong purpose, expired challenge, malformed SPKI, unsupported curve/algorithm, malformed DER signature, invalid signature, replay, revoked device, and revoked Auth session.
- Test that a failure rolls back all state changes and creates only the explicitly defined rejection event (if policy calls for one); it must never leave a partially bound device session.
- Keep Admin TOTP/AAL2 requirements independent of device proof. Device recognition never grants a role and never blocks requester SOS submission.

### Explicit implementation decision and runtime evidence

The original disposable local Auth-to-PostgREST identity proof **passed** on commit `c545506d8810d38b04c7a76afc293b580e823406` in PR #370. It used real Auth-issued JWTs for two synthetic local users and verified PostgreSQL `auth.uid()`, signed `session_id` propagation, cross-user isolation under concurrent requests, rejection of missing/malformed/expired/anonymous credentials, and resistance to body-supplied identity spoofing. The proof ran only against a disposable loopback Supabase stack; it did not use hosted TACResQ data or credentials.

The extended local revocation and trace-context proof **passed on commit `4145dbece6ec0099e366cad4c04abb9bb585c2d4`** in PR #370. It confirms that the matching `auth.sessions` row is deleted on local sign-out, the old unexpired access JWT remains accepted by ordinary stateless PostgREST JWT verification, the database session predicates reject it, and the revoked refresh token is rejected. It also sends real `traceparent` headers through the local gateway/PostgREST path, confirms the database parser returns the expected trace ID, and repeats the assertion across 24 concurrent requests with isolated identities and trace IDs. The Admin investigation SQL contract separately passed trace stamping, exact trace-ID search, and session attribution. CI path detection includes `tools/test_supabase_jwt_context.py`, so changes to this integration proof trigger the local job.

Do not conflate the original JWT-context proof with immediate access-token invalidation. A stale access JWT can remain cryptographically valid until expiry; sensitive operations that require immediate sign-out enforcement must independently check the signed `session_id` against `auth.sessions`. Do not treat documentation, a mocked Laravel HTTP test, a decoded JWT payload, or PostgreSQL-only CI as a substitute for the disposable Auth/PostgREST runtime proof.


### Supabase-supported test conventions

Supabase's current Edge Function testing guide recommends Deno's built-in test runner under a functions test directory, and its development guide uses the Supabase CLI local stack plus `supabase functions serve`. This is a suitable convention for the disposable JWT-context probe. Keep the local test-only SQL fixture outside `supabase/migrations`; the committed function tests must not introduce a public production RPC just to inspect claims.

References:
- [Testing your Edge Functions](https://supabase.com/docs/guides/functions/unit-test)
- [Edge Function development environment](https://supabase.com/docs/guides/functions/development-environment)
- [Local development workflow](https://supabase.com/docs/guides/local-development/cli-workflows)

The current CI's RPC grant contract uses a standalone PostgreSQL 17 service and the repository's custom migration replay script. That is useful database coverage but it does not start Supabase Auth, PostgREST, or the Edge Runtime. Add the full local-stack job as a separate advisory lane first; do not replace or weaken the existing fast source and database gates. Promote it to required only after repeatable startup, runtime, and cleanup are demonstrated.


## 19. First cryptographic implementation slice — P-256 proof primitives

**Implemented in repository on 2026-11-27; not an endpoint and not enabled in any client flow.**

The first Android device-key algorithm is now explicitly P-256 / ES256. The reusable runtime-neutral module at supabase/functions/_shared/device_proof_crypto.mjs provides:

- CSPRNG generation of exactly 32 challenge bytes and a lowercase SHA-256 challenge hash. The caller receives the base64url challenge once; only the hash is intended for database persistence.
- Construction of the exact newline-delimited TACRESQ-DEVICE-PROOF v1 message from server-issued challenge, purpose, profile, Auth session, and device identifiers.
- Strict ASN.1 DER ECDSA signature parsing and conversion to the 64-byte P1363 representation expected by WebCrypto. It rejects negative/non-minimal integers, malformed lengths, trailing bytes, zero/out-of-range scalars, and oversized signatures.
- SPKI P-256 public-key import and SHA-256 ECDSA signature verification using the runtime WebCrypto API.
- Canonical base64/base64url validation; no private-key import or export API exists in this module.

tools/test_device_proof_crypto.mjs uses ephemeral WebCrypto-generated P-256 key pairs and covers valid proof verification, altered-message rejection, wrong-key rejection, DER parser negative cases, exact signed-message construction, purpose/device mismatch, random challenge uniqueness, SHA-256 hash agreement, and canonical base64url handling. The test is wired into the Node 22 source-check job and runs without a third-party dependency or external service.

### Security boundary retained

Historical checkpoint: this primitive-only slice did not itself issue challenges or register devices. It was subsequently extended by the trusted verifier implementation in Section 20. The registry remains private and unused by operational clients; no hosted function, production migration, Vault secret, database role password, client key, Android API integration, or Admin control was deployed.

### Remaining gates before a verifier endpoint

1. Implement and test the database transaction contract for issuance, proof acceptance, conditional single-use consumption, device/session association, and event insertion. The verifier must not expose a direct authenticated RPC that can be called without cryptographic verification.
2. Establish a trusted verifier-to-database attestation boundary. A caller-scoped JWT preserves auth.uid() and auth.jwt(), but an ordinary authenticated RPC cannot distinguish a request forwarded by the Edge verifier from a direct client call. A service-role request loses caller JWT context. Do not bridge this gap by trusting a boolean or caller-supplied profile/session ID.
3. Use an explicit, server-held verifier proof (for example, a narrowly scoped HMAC attestation checked by a private SQL function with a separately provisioned secret) or a transaction design that independently verifies the original JWT and session inside the trusted boundary. Secret provisioning must be documented and tested locally; never put the secret in migrations or client bundles.
4. Add persistent per-account/session issuance throttles and a trusted per-IP rate-limit strategy. Do not use in-memory Edge instance counters as a security control.
5. Add a disposable local Supabase Edge Function integration test proving original JWT identity/session preservation, direct-RPC bypass resistance, concurrent one-winner consumption, rollback on event failure, wrong-user/session/purpose/device rejection, expiry, replay, and revoked-session rejection.
6. Implement a separate Android P-256 Keystore key alias and test real SHA256withECDSA DER output, SPKI serialization, key invalidation, and key rotation on supported Android versions. Do not reuse the existing AES-GCM session-encryption key.
7. Keep requester SOS, location reporting, chat, responder claim, dispatch assignment, and Admin TOTP/AAL2 behavior unchanged until a separate operation-specific enforcement review is approved.

**Decision:** the cryptographic primitive is ready for isolated reuse and CI regression coverage; the server trust boundary is not ready. Do not deploy or expose device registration/proof until all remaining gates pass.


## 20. Trusted verifier-to-database boundary — implementation slice

**Implemented in the repository on 2026-11-27; local disposable integration validation is now wired into the advisory Supabase CI lane. It has not been deployed or provisioned in the hosted project.**

### Selected trust boundary

The verifier uses a dedicated PostgreSQL login named `tacresq_device_verifier`, not the Supabase `service_role` and not a client-callable PostgREST RPC.

- The migration creates the role as `NOLOGIN`, `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `NOINHERIT`, `NOREPLICATION`, and `NOBYPASSRLS`. A password is deliberately not stored in migration source.
- The Edge Function verifies the incoming bearer token with Supabase Auth `getClaims`, reads the verified `sub` and `session_id`, and uses a dedicated server-only database connection string. The new migration revokes legacy direct `service_role` table grants from all five device-registry tables; it preserves the existing `service_role` USAGE/SELECT-only sequence grants required by the repository's RPC contract. The dedicated verifier receives no direct table or sequence privileges; only the SECURITY DEFINER routines can mutate registry state.
- Three functions in the non-exposed `private` schema check `session_user = 'tacresq_device_verifier'`. They are `SECURITY DEFINER` with a fixed `pg_catalog` search path, owned by the migration owner, and executable only by that dedicated login. The login has no direct table privileges.
- The database functions independently map the verified Auth UUID through `profiles.auth_user_id`, resolve the bigint profile ID, and verify that the supplied Auth session row exists and belongs to that Auth user. A stale but cryptographically valid access JWT after sign-out therefore cannot issue or complete a device proof.
- The Edge Function verifies P-256 signatures before calling the atomic completion function. The dedicated database credential is the attestation that the caller is the trusted verifier; ordinary authenticated users, anon, and `service_role` cannot call the private functions or bypass signature verification through PostgREST.
- The completion function consumes the challenge, creates/updates the device, binds the Auth session, and appends the security event in one transaction. A failed write rolls the entire transaction back.

This replaces the earlier HMAC-attestation proposal. It avoids keeping a matching HMAC key in both Edge secrets and Vault, and avoids returning a database-held signing secret to the Edge runtime. The security property now depends on protecting one dedicated database credential and limiting that role to the three private function grants.

### Implemented endpoint contract

`supabase/functions/tacresq-device-verifier/index.ts` currently supports only Android P-256:

1. `POST action=issue` with `registration` or `session_proof`. The database generates 32 CSPRNG bytes, stores only SHA-256, returns the raw base64url challenge once, and applies persistent limits of five issuances per Auth session and twenty per profile/account in 15 minutes, serialized with a profile-scoped transaction advisory lock. Challenges expire after two minutes.
2. `POST action=verify` with challenge ID, raw challenge, DER-encoded ECDSA signature (base64url), and a proposed SPKI public key for registration. For session proof, the server loads the already registered public key; clients cannot replace it through this route.
3. The Edge Function reconstructs the exact v1 signed message using database-returned challenge context and the verified Auth claims. It validates the P-256 SPKI and DER signature using the shared crypto module.
4. Only after successful verification does it call the database completion function. Registration creates the device and binds the current Auth session; session proof refreshes an existing same-device binding. A live Auth session cannot be rebound to a different device.

Malformed signatures, wrong keys, altered challenge bytes, cross-profile/session requests, expired/replayed challenges, and rate-limit exhaustion are rejected with bounded responses. Tokens, raw challenges, signatures, and keys are not written to application logs or event details.

### Local integration coverage added

- `tools/prepare_device_verifier_local_env.py` provisions a random password only for the disposable local database role and writes a mode-0600 Edge environment file. It refuses non-loopback Supabase and PostgreSQL URLs.
- `tools/test_device_verifier_local.mjs` exercises the real Edge Function against local Supabase Auth and PostgreSQL: valid registration, same-user cross-session denial, cross-user/cross-device denial, malformed and wrong-key signatures, altered challenge bytes, successful replay denial, two concurrent submissions with exactly one winner, expired challenge rejection, per-session and multi-session account rate limits, expired Auth-session rejection, stale JWT after sign-out, direct table/RPC bypass denial, and transaction rollback when the event insert is forced to fail.
- `tools/test_device_security_privileges.sql` now asserts the dedicated role has only the required private function execution grants, no direct registry-table privileges, and no client/service-role execution grants. The clean PostgreSQL migration replay runs this contract.
- The advisory local Supabase CI job now provisions the disposable login, serves the Edge Function, runs the HTTP integration suite, and removes temporary credentials and containers.

### Operational and release gates that remain

- The hosted role remains `NOLOGIN`; no password or Edge secret was provisioned in the connected project. No hosted deployment, migration, or database setting was changed.
- Before a controlled deployment, provision a strong role password out-of-band and configure `TACRESQ_DEVICE_VERIFIER_DATABASE_URL` as an Edge Function secret using the project-provided shared transaction-pooler connection string. Supabase recommends transaction pooling for short-lived Edge Function database connections; confirm that `session_user` is preserved through the selected pooler before enabling the endpoint. Never put the password in Git, migration SQL, logs, or the Android/web bundle.
- The local test currently uses a loopback direct database URL, not the hosted pooler. Pooler identity behavior and TLS must be validated in a separate controlled environment before production deployment.
- Persistent per-session and per-profile/account issuance throttling is implemented. A trusted per-IP rate limit is not yet implemented; do not infer a trusted client IP from an arbitrary request header.
- Android Keystore key generation, key invalidation/rotation, device UI, user self-service revocation, Admin device console, Play Integrity, WebAuthn, and operation-level device enforcement are not implemented.
- `step_up` is intentionally not exposed yet. Admin TOTP/AAL2 remains independent and authoritative. Requester SOS, emergency location submission, dispatch, responder claims, chat, and existing operations are unchanged.
- No production migrations, Edge Function deployment, Vault changes, database role password, service-role key, or Render settings were modified.

### Current decision

The trust boundary is now concrete in source: a dedicated login, private function-only grants, verified JWT claims, live Auth-session checks, and atomic database completion. It is eligible for isolated CI validation only. Do not expose it to clients or deploy it until the disposable Edge Function integration suite passes consistently, the pooler preserves the dedicated login identity, and the implementation receives independent security review.

Research references rechecked 2026-11-27:
- Supabase, [Integrating with Postgres from Edge Functions](https://supabase.com/docs/guides/functions/connect-to-postgres): Edge Functions can use a Postgres client directly; short-lived serverless connections should use the shared transaction pooler.
- Supabase, [Securing Edge Functions](https://supabase.com/docs/guides/functions/auth): user JWT verification and caller authentication modes; keep JWT verification enabled.
- Supabase, [JavaScript auth.getClaims](https://supabase.com/docs/reference/javascript/auth-getclaims): verifies JWT claims using the project's signing keys.
- Supabase, [Auth sessions](https://supabase.com/docs/guides/auth/sessions): the signed access JWT includes a `session_id` corresponding to `auth.sessions`.
- Supabase, [Database connection methods](https://supabase.com/docs/guides/database/connecting-to-postgres): shared transaction pooling is intended for short-lived serverless/Edge connections.


## 21. Android Keystore P-256 key manager — isolated implementation

**Added to the Android source on 2026-11-27. It is intentionally not connected to login, SOS, responder, dispatch, chat, FCM, or Admin flows.**

- `app/src/main/java/com/tacresq/app/data/local/DeviceProofKeyStore.kt` creates and loads a non-exportable EC P-256 private key using Android Keystore and `SHA256withECDSA`.
- The versioned alias is `tacresq_device_proof_p256_v1`, separate from the existing `tacresq_session_aes_gcm_v1` AES-GCM session encryption key.
- The class exposes only the SPKI public key (standard Base64) and DER signature bytes. It has no private-key export method.
- The message length is bounded to 2,048 bytes; the DER signature is checked for the expected ASN.1 SEQUENCE framing and P-256-sized range.
- Key deletion is an explicit recovery hook only. It is not automatically called after a signing error because deleting a registered key requires server-side device revocation/re-registration. There is no automatic key rotation.
- `app/src/androidTest/java/com/tacresq/app/data/local/DeviceProofKeyStoreTest.kt` covers stable public-key retrieval, valid SHA256withECDSA verification, altered-message rejection, explicit delete/recreate behavior, and separation from the AES session alias.

The existing Android build compiles instrumentation tests, but this workflow does not run AndroidKeyStore tests on a real device/emulator. Therefore the new instrumentation tests are present but **not yet executed**. No emulator was activated, no endpoint was wired into app behavior, and no device registration was enabled.

### Android integration gates

1. Execute the instrumentation suite on supported Android 8.0+ hardware/emulators and confirm Android Keystore returns P-256 SPKI and DER signatures accepted by the server's local verifier suite.
2. Validate behavior on devices where Android Keystore cannot generate EC P-256 keys; fail closed with a clear unsupported-device result rather than silently falling back to software keys.
3. Decide and implement Android Key Attestation verification before making hardware-backed claims. This implementation proves possession of a Keystore-held key only; it does not prove StrongBox, hardware backing, Play Integrity, or device integrity.
4. Implement an authenticated device revocation/key-rotation transaction before exposing `deleteForRecovery()` in user-facing recovery UI. A registered Auth session cannot be rebound to a different device.
5. Add the Android API client and UX only after the verifier endpoint is deployed with a provisioned dedicated DB login and pooler `session_user` behavior is validated.

**Decision:** the server-side verifier and Android key-storage primitives now exist with local integration and compile-time regression coverage. The Android key manager remains dormant until device-level tests and the production trust-boundary setup are approved.


## 2026-11-28 — Exact-head CI verification and device-security gate review

- **Exact source head inspected:** `6ae1b2aea0481276a13951ae7ae9f29a455aac8d` on `docs/admin-ips-ui-master-plan`, PR #370.
- **CI completed successfully on this head:** TACResQ CI run [46103650222](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/46103650222) — source checks, Android debug/release build job, local Supabase migration replay and verifier integration, Laravel/dashboard integration, clean PostgreSQL migration replay, and advisory prototype checks all succeeded. Android JVM unit tests, Android Lint, demo/Supabase debug APK builds, release APK build, and APK artifact upload succeeded. Release signature verification was skipped because signing credentials are not configured.
- **Other latest-head workflow groups:** TACResQ Security [46103650209](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/46103650209) passed CodeQL Web/Actions, CodeQL Android, and repository guardrails; RPC grant contract [46103650173](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/46103650173) passed; QA [46103650193](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/46103650193) passed browser capture and local-stack stress smoke test; Frontend Review [46103650181](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/46103650181) passed; read-only profile verification [46103650220](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/46103650220) passed.
- **Device proof source review:** Rechecked `supabase/functions/tacresq-device-verifier/index.ts`, `supabase/migrations/20261127090000_device_verifier_trusted_db_boundary.sql`, `supabase/functions/_shared/device_proof_crypto.mjs`, `DeviceProofKeyStore.kt`, and its Android instrumentation tests. The Edge Function verifies JWT claims and Auth session ID, bounds the request body, verifies P-256 proof, and delegates atomic challenge consumption/session binding/event insertion to narrowly granted private database routines. Local disposable integration already tests cross-user/session rejection, forged and altered proofs, replay, concurrent consumption, rollback, rate limits, expired/stale Auth sessions, and direct PostgREST denial.
- **Decision:** Keep the device registry and Android key manager dormant. Do not integrate proof into login, SOS, dispatch, chat, notifications, or privileged operational flows until the Android instrumentation suite has executed on a supported physical device/emulator, the hosted Supabase transaction-pooler `session_user` identity and TLS behavior are validated, and the integration/enforcement design receives independent security review. Requester SOS must remain available from new/unverified devices.
- **Other release gates:** manual Admin UI acceptance, independent maintainer/security review, project-owner confirmation of hosted Auth audit persistence settings, and explicit deployment approval remain outstanding. No hosted migration, Auth setting, database write, Edge Function deployment, Render setting, user data, or production workflow was changed.
- **PR status at inspection:** #370 remained open and mergeable, not merged; no human reviews or review threads were recorded. The latest code validation above applies to the exact inspected source head. The Android instrumentation suite was compiled, not executed on a device/emulator.
- **Next:** obtain the external device/hosted-environment validations and reviews before activating the dormant device-security foundation. Do not bypass these gates or infer hosted readiness from local CI success.


## 2026-11-28 — Latest PR validation and manual emulator evidence capture

- **Validated source head:** `bde453193e2351f3462a3f2105a091b9ac46c665` on the existing PR #370 branch. All six fresh PR workflow groups completed successfully: TACResQ CI, TACResQ Security, RPC grant contract, QA, Frontend Review, and read-only profile verification. Android JVM tests, builds, Lint, local verifier integration, clean migration replay, browser suite, Laravel/dashboard integration, CodeQL, repository guardrails, and stress smoke tests passed.
- **Manual device-test boundary preserved:** `.github/workflows/android-emulator-manual.yml` is intentionally `workflow_dispatch` only; `tools/test_android_emulator_kvm.py` explicitly guards against running emulator UI tests on every PR. Do not remove that boundary without a deliberate cost/CI-policy decision.
- **Implementation commits:** `cd29120c7fac0a3a8bc7a90527f686863a5388ff` adds failure-only emulator logcat/device-property capture and an always-run upload of connected Android test XML/HTML reports as `tacresq-android-instrumentation-results` (7-day retention). `f31aa21b1619800466157933763c1bbfa26318ca` extends the static KVM/manual-workflow contract to require those diagnostics and artifacts.
- **Important limitation:** this change improves observability of the manual emulator run; it does not dispatch the workflow or prove the Keystore tests passed on an emulator. The available GitHub integration in this session can inspect runs/jobs and rerun failed jobs, but does not expose a workflow-dispatch action. Do not report a device run until an actual workflow-dispatch result exists.
- **Remaining gates:** dispatch the manual emulator workflow on the PR branch and inspect its results/artifact; validate hosted Supabase transaction-pooler `session_user` identity and TLS; obtain independent security/maintainer review; complete the separately reviewed Android-to-verifier integration, revocation/recovery, and sensitive-operation enforcement without blocking requester SOS; perform manual Admin UI acceptance; obtain owner approval before any production changes.
- No production Supabase/Auth/Edge Function/Render changes, deployments, user-data writes, or merge occurred.


## 2026-11-28 — Read-only hosted Supabase baseline and drift assessment

- **Project inspected:** TACResQ-School-Project (kttguamhznoflzagfutr), region ap-southeast-1, status ACTIVE_HEALTHY, PostgreSQL 17.11.0.003. All checks in this section were read-only; no migration, Auth setting, function, or data was changed.
- **Hosted migration baseline:** latest recorded migration is 20261125030354_make_actor_role_lookup_read_only_safe. PR #370's later migrations (including 20261126100000 onward and the trusted verifier boundary migration 20261127090000) are not present in the hosted migration history. Do not infer they are deployed from repository CI.
- **Device verifier availability:** hosted public.user_devices and public.device_challenges exist from the older registry foundation. The tacresq_device_verifier role and private device_verifier_issue_challenge, device_verifier_get_challenge, and device_verifier_complete_challenge routines do not exist. Hosted Edge Function inventory currently contains only active tacresq-notify v2; tacresq-device-verifier is not deployed.
- **Hosted registry grants:** read-only information_schema.role_table_grants inspection showed service_role retains INSERT/SELECT/UPDATE grants on the five device registry tables (and postgres retains owner privileges). No anon or authenticated table grants were returned for those tables. This is the legacy pre-dedicated-verifier boundary; the PR migration that revokes service-role table grants is not deployed. Keep verifier rollout blocked until the dedicated credential, transaction-pooler identity, TLS, and grant transition are validated in a controlled release.
- **Connection identity caveat:** the read-only Supabase SQL tool reported current_user = session_user = postgres. This is the management/MCP SQL connection, not a transaction-pooler test. It proves nothing about the future dedicated login's session_user identity or TLS behavior.
- **Security Advisor snapshot (observed 2026-11-28 04:02:57 UTC):** 16 informational rls_enabled_no_policy findings, including private/server-owned audit, device, and operational tables; 24 authenticated_security_definer_function_executable warnings; and one warning that leaked-password protection is disabled. The no-policy findings should be interpreted with actual grants and schema exposure, not blindly fixed by adding client policies. The SECURITY DEFINER warnings require per-function authorization/AAL2 and grant-contract review; do not revoke broad RPCs without preserving legitimate app/admin workflows. Leaked-password protection is a hosted Auth configuration change and needs owner approval plus login-flow testing.
- **Release conclusion:** hosted project is healthy but intentionally behind the current PR security migrations. No production migration, role change, Auth setting, Edge Function deployment, or user-data mutation was performed. Before rollout, prepare a coordinated migration + dedicated connection-secret provisioning + Edge Function deployment sequence with rollback and outage checks; never deploy only one half of the trust boundary.


## 2026-11-28 — Exact-head CI and hosted security-advisor follow-up

- **Current PR state:** PR #370 remains open and mergeable, not merged, on branch `docs/admin-ips-ui-master-plan`. Exact head: `a9d6c67d3f145ebe45920a9450704e34b6ee7d57`. The branch is synchronized with `main` (0 commits behind; 750 commits ahead at inspection). No human reviews or review threads are recorded.
- **Exact-head validation:** all six workflow groups completed successfully: TACResQ CI (Android debug/release builds and JVM tests, instrumentation compilation, Lint, local migration/verifier integration, PostgreSQL migration replay, Laravel/dashboard integration), TACResQ Security (repository guardrails and CodeQL), RPC grant contract, QA, Frontend Review, and read-only profile verification. The manual emulator workflow is a separate `workflow_dispatch` workflow; its static contract passed in CI, but the emulator workflow itself was not run.
- **Manual emulator diagnostics implementation:** commit `cd29120c7fac0a3a8bc7a90527f686863a5388ff` adds failure-only logcat/device-property diagnostics and always-run instrumentation XML/HTML artifact collection; commits `f31aa21b1619800466157933763c1bbfa26318ca` and `a9d6c67d3f145ebe45920a9450704e34b6ee7d57` strengthen the manual workflow contract and pin the emulator runner and artifact actions to full commit SHAs. These changes improve diagnosability; they do not prove device execution.
- **Hosted advisor follow-up (read-only):** the Performance Advisor snapshot returned 10 unindexed foreign-key findings, one RLS initialization-plan warning (`auth_rls_initplan` on `incident_assignment_events_dispatch_read`), and 34 unused-index notices. Do not automatically drop unused indexes; assess actual query plans and workload before proposing index changes. Evaluate FK indexes and RLS predicate optimization in separately tested migrations.
- **Existing Security Advisor interpretation remains:** 16 informational RLS-enabled/no-policy findings, 24 SECURITY DEFINER executable warnings, and leaked-password protection disabled. Interpret each against schema exposure, effective grants, and function authorization; do not blanket-add policies or revoke functions. Any hosted Auth setting change requires owner approval and login-flow validation.
- **Decision:** no additional production change is justified by advisor labels alone. Preserve existing AAL2/TOTP guards, private schema boundaries, role separation, and emergency workflows. The next safe work is evidence gathering and separately reviewed remediation proposals—not automatic privilege/index changes.
- **Deployment boundary:** the hosted project still lacks the PR verifier role/routines and deployed Edge Function; the management SQL connection's `session_user=postgres` is not evidence about transaction-pooler identity. Do not provision the dedicated credential or deploy only one side of the verifier boundary. No production migration, role/password, Auth setting, Edge Function, Render configuration, or user data was changed.
- **Release gates:** manual emulator/device execution; hosted pooler identity and TLS verification; device API integration and server-side enforcement design; revocation/recovery UX; manual Admin UI acceptance; independent security and maintainer review; explicit owner approval for coordinated deployment. Do not merge or activate the dormant device registry based only on green CI.


## 2026-11-28 — Final validation of documentation head

- **Exact head:** `a2e2b7ec4f0abe059398de39e3c77d2b529b1225`.
- **Check-run inventory:** 18 check runs completed. Sixteen concluded success. The aggregate `CodeQL` check concluded neutral; its specific Android (Java/Kotlin) and Web/GitHub Actions analysis jobs both concluded success, as did repository security guardrails. `CodeRabbit` concluded skipped because this repository does not meet the integration's activation threshold; it is not a review approval. All other named checks concluded success, including Android debug/release builds, source checks, clean PostgreSQL migration replay, local Supabase migration replay, Laravel/dashboard integration, RPC grant contract, QA browser/prototype capture and local stack stress smoke test, Frontend Review, read-only profile verification, static security/correctness review, and Gradle dependency submission.
- **What this proves:** repository source/build/integration contracts pass at this exact head. It does not prove hosted deployment, transaction-pooler identity/TLS, actual Android emulator/device execution, manual Admin UI acceptance, or human security review.
- **Branch state:** PR #370 remains open, unmerged, mergeable, and 0 commits behind `main`. No human review submissions or inline review threads were present at inspection.
- **Decision:** do not add speculative database/index/RLS changes based on advisor labels; do not activate device registry or deploy the verifier until the documented hosted and owner-approval gates are met. No production state was changed.


## 2026-11-28 — Android verifier DER input validation hardening

### Source finding

The isolated Android transport previously checked only the encoded signature's character set and length. The trusted Edge Function already performed strict ASN.1 DER parsing, canonical Base64URL decoding, positive/minimal INTEGER validation, and P-256 scalar range checks before WebCrypto verification. Consequently, malformed DER or a raw 64-byte P1363 signature could pass the Android transport's local shape check and incur an unnecessary verifier request, even though the server would reject it.

### Implemented correction

- Android now decodes the signature as canonical, unpadded Base64URL and requires a byte-for-byte canonical re-encoding.
- It validates a complete DER SEQUENCE, exact lengths, no trailing data, exactly two minimally encoded positive INTEGER scalars, 1–32-byte magnitudes, and scalar values strictly inside the P-256 group order.
- The transport rejects P1363 raw signatures, zero/negative scalars, non-minimal encodings, malformed/truncated DER, and padded/non-canonical Base64URL before network activity.
- The server-side verifier remains authoritative and retains its independent strict parser.
- The Android verifier API remains intentionally dormant: no login, SOS, dispatch, chat, notification, or Admin call sites were added.

### Regression coverage and acceptance

The Android verifier API JVM test now uses a valid minimal DER signature fixture and asserts malformed P1363, zero-scalar, negative-scalar, and padded Base64URL inputs fail before any MockWebServer request. The existing registration/session-proof transport tests continue to exercise the valid DER wire representation.

**Validation status:** implementation and regression tests are committed together; exact-head Android JVM, Lint, source-contract, full CI, PostgreSQL replay, RPC contract, security and QA results are pending. No passing result is claimed until the new exact commit completes CI.

**Safety:** no hosted Supabase migration, verifier role/credential, Edge Function, Auth setting, Render configuration, production data, or operational Android flow was changed.
