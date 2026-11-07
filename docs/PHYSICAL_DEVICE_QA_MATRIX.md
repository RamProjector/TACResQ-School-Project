# TACResQ physical-device QA matrix

**Purpose:** target-2 release gate using synthetic accounts, synthetic incidents, and a non-production backend only.  
**Status:** Template added; no physical-device run is claimed by this PR.

## Test record fields

Record the date, tester, device model/OEM, Android/API level, app version, APK SHA-256, backend environment, synthetic account IDs/roles, network condition, expected result, observed result, and defect ID. Do not record passwords, bearer tokens, FCM tokens, real names, real phone numbers, real incidents, or precise personal locations.

## Matrix

| ID | Scenario | Expected result | Evidence status |
|---|---|---|---|
| AUTH-01 | Sign in as synthetic requester and restore after process restart | Session restores or fails with a recoverable message; no token appears in logs | Not run: device/live QA required |
| AUTH-02 | Sign out and sign in as a different synthetic role | Previous local drafts/cache are not exposed to the new owner | Automated ownership contracts; device run pending |
| SOS-01 | Capture valid synthetic GPS fix and review SOS | Review shows source, accuracy, and capture time before explicit send | Automated/source coverage; device run pending |
| SOS-02 | Airplane mode during SOS attempt | Request is not shown as sent; exact pending request remains available for explicit retry | Automated policy/cache coverage; device run pending |
| SOS-03 | Timeout after synthetic server commit, then explicit retry | Idempotency prevents a duplicate incident | Backend/source coverage; live QA pending |
| SYNC-01 | App backgrounded with network, WorkManager read refresh | Only confirmed requester reads refresh; cached status remains safe on failure | Source/CI coverage; device scheduling pending |
| SYNC-02 | Offline then reconnect | Read-only sync may refresh later; no SOS/status/location/chat mutation is auto-submitted | Source/CI coverage; device run pending |
| FCM-01 | Android 13+ notification permission denied | In-app inbox remains usable; no claim of push delivery | Source coverage; device run pending |
| FCM-02 | Synthetic notification while app foregrounded/backgrounded/closed | Generic push opens TACResQ; details are fetched through authorized inbox | Worker/live Firebase/device pending |
| GPS-01 | Approximate, precise, denied, revoked, and Location disabled | UI accurately labels permission/service state; no fabricated coordinate | Source coverage; device run pending |
| GPS-02 | Responder starts/stops sharing, backgrounds app, signs out | Foreground notification and assignment checks govern sharing; stop prevents further writes | Source coverage; device/OEM run pending |
| ROUTE-01 | Routing disabled at backend | Route endpoints return safe 503 and make no upstream call | CI/source and backend feature coverage |
| ROUTE-02 | Synthetic route with approved provider | Consent names provider; no silent provider failover; result is static estimate | CI/source coverage; live provider/device pending |
| ROLE-01 | Requester, responder, driver, dispatcher, unrelated account | Each role sees only authorized records/actions | PostgreSQL/source coverage; live QA pending |
| BUILD-01 | Signed release workflow | Keystore is required, APK is signed, checksum is uploaded | Manual workflow pending configured secrets |

## Release decision

Do not mark the matrix complete from CI alone. A release candidate is blocked until the required device/live-backend rows are executed with synthetic data, defects are resolved or accepted by the authorized operator, and the APK SHA and backend environment are recorded. TACResQ remains an emergency-coordination aid and not a guaranteed emergency-services provider.
