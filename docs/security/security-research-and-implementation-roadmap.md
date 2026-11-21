# TACResQ Security Research and Implementation Roadmap

**Owner:** TACResQ project  
**Tracking PR:** #335 — consolidated security hardening  
**Status:** Research and staged implementation; nothing in this document authorizes production deployment or live database changes.

## Operating principles

1. Protect emergency reporting and dispatch availability first. Security controls must not prevent a legitimate requester from sending an SOS because a device is new, an optional provider is unavailable, or an integrity verdict is inconclusive.
2. Enforce trust at the server boundary. Never trust client role values, device IDs, hidden UI controls, client timestamps, or client-supplied authorization claims.
3. Use least privilege and explicit ownership/assignment checks for every sensitive operation. RLS is necessary but not sufficient; review grants, RPC execution, SECURITY DEFINER search paths, and service-role boundaries together.
4. Prefer free, built-in, and self-hostable controls. No paid WAF, malware, device-intelligence, monitoring, or backup service without explicit approval.
5. Preserve evidence without collecting unnecessary personal data. Avoid covert device fingerprinting, IMEI/serial/MAC collection, and indefinite precise-location retention.
6. Ship in independently testable stages inside this single PR. A roadmap item is not considered implemented merely because it is documented.
7. Do not apply migrations to production, alter Render configuration, rotate credentials, or deploy without explicit approval.

## Threat model

TACResQ handles emergency requests, precise location, responder/driver assignments, incident chat and attachments, notifications, privileged administration, and operational status history. The security model must account for:

- Account takeover, weak recovery, stolen or replayed sessions, and privilege escalation.
- Unauthorized reads/writes through PostgREST, RPCs, storage policies, and service credentials.
- Spoofed device identifiers, cloned installations, modified clients, and stolen devices.
- API abuse, automated sign-up/login attempts, request flooding, replay, and resource exhaustion.
- Malicious or malformed chat uploads, unsafe file types, and object-path authorization mistakes.
- Insider misuse, unauthorized assignment overrides, audit tampering, and excessive administrator access.
- Database, Render, notification-provider, network, or storage outages during an emergency.
- Dependency, build pipeline, secret, container, and release artifact compromise.
- Excessive retention or exposure of GPS, identity, message, and operational history.

## Prioritized workstreams

### P0 — Identity, authentication, and privileged access

**Research and implement**
- Inventory authentication, signup, recovery, email confirmation, session refresh, logout, and account deactivation paths.
- Ensure public signup can only create the least-privileged requester role; role changes remain server-authorized and audited.
- Add login, signup, recovery, OTP, and sensitive RPC throttling with bounded responses and abuse telemetry.
- Evaluate MFA/passkeys for admin and dispatcher accounts; use step-up verification for role changes, device revocation, assignment overrides, and other high-impact actions.
- Provide session listing and server-side revocation; ensure password/account recovery invalidates or revalidates sessions as appropriate.
- Review session lifetime, refresh-token rotation, recovery-channel security, and account enumeration behavior.
- Keep emergency requester flows usable when optional step-up providers are unavailable; do not weaken privileged-action requirements.

**Acceptance**
- No client can self-assign a privileged role.
- A revoked session cannot perform protected actions.
- High-impact administrative actions require fresh authorization and produce immutable audit evidence.
- Abuse limits are tested without locking out all users behind a shared network.

**Implemented in PR #335 (Laravel API; CI validation pending)**

- Login attempts use a per-account-and-source failure bucket (five failures, 15-minute decay) plus a separate generous source ceiling (120/minute). Successful authentication clears the account/source failure bucket. Responses avoid account-existence disclosure and token responses are marked `no-store`.
- Public registration is rate-limited by source (10 attempts per 15 minutes) before validation/database work. Email is normalized before duplicate lookup; public signup remains requester-only.
- Authenticated `POST /api/logout` revokes the current Sanctum token. Authenticated `POST /api/logout-all` revokes all personal access tokens for the account.
- Static CI contracts cover the login, registration, and session-revocation behavior.

**Implemented in PR #335 (Laravel API; CI validation pending)**

- Login attempts use a per-account-and-source failure bucket (five failures, 15-minute decay) plus a separate generous source ceiling (120/minute). Successful authentication clears the account/source failure bucket. Responses avoid account-existence disclosure and token responses are marked `no-store`.
- Public registration is rate-limited by source (10 attempts per 15 minutes) before validation/database work. Email is normalized before duplicate lookup; public signup remains requester-only.
- Authenticated `POST /api/logout` revokes the current Sanctum token. Authenticated `POST /api/logout-all` revokes all personal access tokens for the account.
- Authenticated `POST /api/change-password` requires the current password and a confirmed replacement of at least 12 characters, rate-limits failed current-password checks, revokes prior Sanctum tokens, and returns one fresh token.
- Static CI contracts cover login, registration, logout/session revocation, and password-change/session rotation.

**Not yet implemented:** password reset/recovery flow, email/OTP verification, expiry/rotation for Supabase Auth sessions, per-device session listing/revocation, device challenge verification, MFA/passkeys, persistent security-event telemetry for authentication abuse, and step-up authentication. The current session controls apply to Laravel Sanctum personal access tokens only; they do not revoke Supabase Auth sessions.

### P0 — Device security and identification

See [device-security-research-and-implementation-plan.md](device-security-research-and-implementation-plan.md).

**Implementation stages**
1. Private backend-only device registry foundation.
2. Purpose-bound, short-lived, one-time challenge issuance and atomic verification.
3. Android per-install keypair using Android Keystore; public-key registration and signature proof.
4. Server-side session/device binding and revocation enforcement.
5. Server-side Google Play Integrity verification with risk-based policy.
6. Web privileged-device support and WebAuthn/passkey step-up.
7. User My Devices and Admin Device Security controls.
8. Alerting, investigation, and recovery workflows.

A device ID, browser fingerprint, platform label, or integrity verdict is never authentication by itself. New devices must not block emergency SOS submission after ordinary account authentication.

### P0 — API, database, and authorization protection

**Research and implement**
- Maintain a complete inventory of exposed tables, views, RPCs, Edge Functions, storage buckets, and service-role paths.
- Review grants and RLS together, including TRUNCATE (which RLS does not protect), sequence privileges, function EXECUTE grants, and default privileges.
- Make sensitive data tables private by default; expose narrow, caller-scoped RPCs instead of broad direct table mutation.
- Verify actor identity from the authenticated server context, not request parameters. Recheck role, ownership, incident state, and current assignment inside the same transaction as the mutation.
- Pin SECURITY DEFINER search_path, qualify object names, minimize owner privileges, and revoke default PUBLIC/anon execution.
- Apply strict input bounds, payload-size limits, pagination limits, safe error messages, CORS policy, and security headers at the appropriate web/API boundary.
- Add idempotency/replay protections to emergency submissions and other retryable mutations.
- Rate-limit expensive reads, writes, login attempts, chat, location updates, and notification actions. Prefer per-account and per-operation limits, with IP/network signals as supplemental evidence.
- Review Storage object ownership, private bucket policies, signed URL lifetimes, path traversal, metadata verification, and upload/download authorization.

**Acceptance**
- Role/ownership/assignment checks are enforced by the backend even when requests are forged outside the UI.
- Anonymous and authenticated clients cannot directly mutate protected operational or audit tables.
- Replaying a mutation does not create duplicate emergency requests or duplicate privileged actions.
- Security tests cover every exposed RPC and table boundary.

### P0 — Threat monitoring, audit, and incident response

**Research and implement**
- Normalize security events for failed authentication, repeated throttling, denied RPCs, role changes, assignment overrides/releases, device proof failures, session revocations, suspicious upload activity, and provider failures.
- Correlate events by account, session, device record, incident, operation, and time without collecting invasive fingerprints.
- Preserve append-only audit trails for privileged actions and sensitive security changes; ordinary admins must not be able to rewrite or erase audit evidence.
- Build an admin investigation view with filters, event timelines, severity, actor/target, reason, and linked operational record.
- Define alert thresholds and response runbooks: contain, revoke sessions/devices, preserve evidence, restore service, and notify affected users where appropriate.
- Keep administrator access scoped: an admin role does not automatically authorize unrestricted access to private messages or precise location history.

**Acceptance**
- Every privileged mutation has actor, target, timestamp, reason, and outcome.
- Audit records are not client-writable or client-deletable.
- Detection produces actionable alerts without automatically banning users solely due to shared devices or networks.

### P0 — Backup and disaster recovery

Detailed provider-specific runbook and staged implementation gates: [backup-disaster-recovery-research-and-implementation-plan.md](backup-disaster-recovery-research-and-implementation-plan.md).

**Research and implement**
- Establish a repeatable database export/backup procedure compatible with the current Supabase plan and available free tooling.
- Back up Storage objects separately; database backups alone do not preserve chat attachments.
- Include migration history, required configuration names, deployment instructions, and recovery documentation while never exporting secrets into source control.
- Define recovery point objective (RPO) and recovery time objective (RTO) appropriate to emergency operations.
- Verify restore into an isolated environment; test schema, RLS, RPC, Storage object references, and application compatibility.
- Keep backups encrypted, access-restricted, integrity-checked, and subject to retention/deletion policy.
- Account for Render Free instance spin-down and ephemeral filesystem: do not treat local service files as durable backup storage.

**Acceptance**
- A documented restore test succeeds from a known backup.
- Database and attachment recovery are both covered.
- Backup freshness and restore evidence are visible to maintainers; no claim of automatic backup is made unless verified.

### P1 — Availability and resilience

**Research and implement**
- Add health checks and independent monitoring for web/API, database connectivity, SOS submission, dispatch assignment, push notification delivery, and Storage.
- Measure end-to-end emergency workflow outcomes, not only whether a web process responds.
- Use bounded retries, exponential backoff with jitter, idempotency keys, timeouts, and dead-letter/retry visibility for background work.
- Define degraded modes when push notifications, map/geocoding providers, or optional integrity services fail. Provide in-app refresh/polling or alternate operational visibility where feasible.
- Prevent one noisy account or failing provider from exhausting shared resources.
- Test startup delay and cold starts on the current Render Free service; never imply always-on availability.
- Establish an operational incident runbook and manual fallback for dispatch.

**Acceptance**
- Critical workflows have explicit timeout/failure states and retry rules.
- Optional provider failure does not silently lose an emergency request.
- Monitoring verifies actual SOS-to-dispatch flow and records gaps.

### P1 — Data protection and lifecycle

**Research and implement**
- Classify profile data, precise GPS, incident records, chat messages, attachments, device metadata, notifications, and audit evidence.
- Add lifecycle rules by data class, not one blanket deletion rule.
- Use soft deletion/deactivation for accounts where operational history must remain attributable; keep actor, timestamp, and reason in server-managed fields.
- Separate cancellation, account deactivation, content redaction, and legally/operationally required erasure; they are different actions.
- Preserve immutable incident/status/assignment/edit/audit history subject to approved retention policy.
- Purge expired idempotency keys, obsolete delivery attempts, revoked push tokens, and temporary challenges on a bounded schedule.
- Minimize precise GPS retention and restrict access to responders/dispatchers only when operationally necessary.
- Delete Storage objects and metadata coherently; coordinate Auth-user lifecycle with profile retention and foreign-key dependencies.
- Audit sensitive administrator reads as well as writes where practical.

**Acceptance**
- Each table/object class has an owner, purpose, access rule, retention period, and deletion/redaction procedure.
- No client can set its own deletion flags or bypass audit.
- Restore and retention procedures do not break required incident history.

### P1 — Software supply-chain and release security

**Research and implement**
- Keep dependency update automation and review security advisories for Android, web, GitHub Actions, Docker/base images, and build tooling.
- Run secret scanning, static analysis, dependency/SCA checks, and supported container/image scanning in CI using free repository-native tools where possible.
- Pin GitHub Actions to immutable commit SHAs where maintainable; grant workflows least permissions and avoid untrusted pull-request code receiving secrets.
- Separate build/test credentials from deployment credentials; use environment-scoped secrets and never print them.
- Generate checksums and provenance for release artifacts; evaluate Android signing and key custody before release distribution.
- Protect main branch with required checks and review; do not use CI bypasses to force a merge.
- Keep emulator/manual UI testing distinct from reliable code regression tests.

**Acceptance**
- No known critical/high exploitable dependency issue is silently ignored; exceptions are documented.
- Secrets do not appear in repository history, logs, artifacts, or client bundles.
- Release artifacts can be tied to a reviewed commit and reproducible build inputs where feasible.

### P2 — Edge abuse, malware, and advanced detection

**Research before implementation**
- Evaluate available provider-native edge protections and free-tier limits before selecting a WAF, DDoS, bot, or rate-limiting product. Do not assume Render/Supabase plans include a specific control.
- Evaluate upload malware scanning using a free/self-hosted scanner such as ClamAV only if deployment resources, file limits, and operational maintenance are realistic.
- Enforce MIME allowlists, byte-size limits, content-signature checks, randomized object keys, private storage, and safe download behavior regardless of malware scanner availability.
- Consider geospatial anomaly signals only for operational abuse detection; GPS can be inaccurate, spoofed, or shared and must not independently trigger account bans.
- Evaluate encryption/key management and rotation for stored sensitive data and backups; distinguish platform-managed encryption at rest from application-level key custody.

**Acceptance**
- No security feature is adopted solely because it is marketed as “anti-raid”, “anti-malware”, or “anti-bot”; every control maps to a documented threat, measurable outcome, and tested failure mode.
- Paid services require explicit approval.

## Admin Security & Countermeasures information architecture

The admin console should expose operationally useful, least-privilege views:

1. **Security Overview** — service health, recent high-severity events, unresolved investigations, backup freshness, and key control status.
2. **Identity & Access** — account state, role changes, MFA/passkey readiness, session revocation, and recovery events.
3. **Device Security** — registered devices, proof/integrity history, risk state, device/session revocation.
4. **Threat Monitoring** — searchable security event timeline, severity filters, investigation notes, and response actions.
5. **API Protection** — rate-limit events, rejected requests, RPC authorization failures, and endpoint health.
6. **Data Protection** — retention policies, deletion/redaction requests, sensitive-access audit, and storage lifecycle.
7. **Backup & Recovery** — last verified backup, attachment backup status, restore-test history, and recovery instructions.
8. **System Availability** — end-to-end SOS/dispatch/notification health and degraded provider status.
9. **Software Security** — dependency alerts, CI scan results, release provenance, and unresolved exceptions.
10. **Audit & Activity Records** — append-only, filterable actor/target/action/reason history.

An admin role must not imply unrestricted access to private user data. Security dashboards should show summaries and links to authorized operational records, not expose secrets or raw tokens.

## Implementation sequence inside PR #335

The single PR is organized as reviewable commits/sections, not separate PRs:

1. Complete and repair current privilege hardening tests; verify registration/profile creation and existing grants before changing them.
2. Include device registry foundation and static migration contracts.
3. Add security roadmap, threat model, control ownership, and acceptance criteria.
4. Implement only the next backend-safe P0 controls whose existing app flows and authorization boundaries have been inspected.
5. Add runtime tests for each new migration/RPC, including positive and negative role cases.
6. Add CI security checks that are reliable, free, and compatible with current repository workflows.
7. Review all migrations as a single ordered sequence against the live migration ledger before requesting production approval.
8. Run full CI, security scans, migration replay, and manual UI acceptance checks.
9. Keep the PR draft until implementation is genuinely complete and all blocking checks pass.

## External standards and research references

Use these as complementary sources rather than treating any single framework as a product or complete security solution:

- OWASP Application Security Verification Standard (ASVS): https://owasp.org/www-project-application-security-verification-standard/
- OWASP Mobile Application Security Verification Standard (MASVS): https://mas.owasp.org/MASVS/
- OWASP API Security Top 10: https://owasp.org/www-project-api-security/
- OWASP Cheat Sheet Series: https://cheatsheetseries.owasp.org/
- NIST Cybersecurity Framework 2.0: https://www.nist.gov/cyberframework
- NIST Digital Identity Guidelines: https://pages.nist.gov/800-63-4/
- CIS Critical Security Controls: https://www.cisecurity.org/controls
- MITRE ATT&CK: https://attack.mitre.org/
- CISA Secure by Design: https://www.cisa.gov/securebydesign
- Android Play Integrity: https://developer.android.com/google/play/integrity
- Supabase Security documentation: https://supabase.com/docs/guides/security
- Render health checks and service behavior: https://render.com/docs/health-checks and https://render.com/docs/free

Re-check vendor documentation before implementation because product capabilities, free-tier limits, API behavior, and recommended configurations can change.
