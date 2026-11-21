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
