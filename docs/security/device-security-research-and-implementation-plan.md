# TACResQ Device Security & Identification — Research and Implementation Plan

**Status:** Phase 1 private registry foundation and generic Laravel account-wide Sanctum token revocation are present in PR #335; CI validation pending; not deployed  
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

**Current implementation status:** no challenge issuance endpoint, signature verifier, device-session binding, or sensitive-operation enforcement has been implemented. The Laravel bridge and Sanctum logout endpoints are not substitutes for those controls. Keep the registry private until the Supabase-side transaction and request-path enforcement are complete.


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

### Remaining implementation blockers

1. Confirm the exact verified Supabase JWT session claim available in the deployed PostgREST request context and test it using a real authenticated request. The current Laravel getUser() bridge validates a token but does not expose verified session_id claims to the bridge controller.
2. Decide whether challenge issuance/signature verification belongs in a Supabase Edge Function or another trusted server component. The selected path must retain the original user JWT when calling the database, while keeping secret/service-role credentials server-only.
3. Verify available cryptographic libraries/extensions and deployment conventions before selecting ES256 or Ed25519 verification. The schema currently permits both; the initial implementation should select one supported algorithm rather than claim both are implemented.
4. Inventory all data surfaces that need device binding, including direct table reads, Storage operations, and any web-only paths—not just named RPCs.
5. Add server-side enforcement and isolated PostgreSQL/HTTP tests before Android or web UI integration. Existing clients must continue operating safely while the registry remains unused.

**Scope decision:** do not add device-proof parameters to the existing emergency/dispatch RPC signatures in this phase. First deliver a narrowly scoped registration and session-binding protocol, prove replay resistance and ownership, then separately propose any additional operation-level enforcement based on a concrete threat and safe fallback.
