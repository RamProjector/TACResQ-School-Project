# TACResQ Project Analysis — Current Repository Baseline

**Reviewed:** 2026-11-16  
**Canonical product definition:** [docs/PRODUCT_SCOPE.md](docs/PRODUCT_SCOPE.md)  
**Research-backed gap roadmap:** [docs/APP_FEATURE_GAPS_AND_ROADMAP.md](docs/APP_FEATURE_GAPS_AND_ROADMAP.md)  
**Durable project memory:** [docs/project-memory/README.md](docs/project-memory/README.md)

This document replaces an outdated analysis that incorrectly described FCM, responder location reporting, and route preview as absent. It summarizes repository source and explicitly separates implementation from live/device verification.

## Repository architecture

- **Android client:** Kotlin, Jetpack Compose, Gradle; role-based requester, responder/driver, dispatcher, and limited administrator workflows.
- **Primary backend:** Supabase Auth, Postgres, RLS, and narrowly scoped RPCs. The Android app can intentionally select an in-memory fake backend for demo/testing.
- **Dispatcher host:** Laravel 13 on Render serves the TACRU web dashboard and browser-safe runtime configuration. The dashboard uses Supabase Auth/RPC for identity and operational data; the temporary Render PostgreSQL database is not the source of truth for this path.
- **Maps and routing:** OpenStreetMap-based map display and incident pins; authenticated Laravel route-preview proxy supports Geoapify and private OSRM. Provider selection is server-side; Geoapify is the backend configuration default. Route preview is disabled by default in backend and Android configuration.
- **Notifications:** persistent in-app notification records are authoritative; Android FCM registration and a trusted HTTP v1 sender/delivery ledger exist. The signed dispatch endpoint exists, but production scheduled dispatch is not active without operator setup and approval.

## Main system workflows

### Requester
A requester can submit an incident with an optional one-time GPS fix, accuracy/timestamp metadata, and supported landmark/location fallback. The app protects against duplicate SOS creation and can persist uncertain submission state for later reconciliation. It does not automatically submit an SOS in the background.

### Responder and driver
A field unit can set permitted duty status, view individually assigned incidents or claimable incidents according to server rules, update its own assignment status, and use a static route preview for an authorized active assignment. Consented foreground location reporting is implemented; continuous/background tracking is not claimed.

### Dispatcher
The dispatcher dashboard reads the incident queue and available units, coordinates multi-responder assignments, and displays supported assignment progress. The legacy `assigned_to` compatibility field is not a formal team-leader designation.

### Administrator
The repository includes a limited server-side user-management and audit foundation. Do not infer additional administration capabilities without inspecting the current RPCs and UI.

### Incident chat
Incident-scoped text and image messaging use server-side authorization. Text sends use a client message ID and server idempotency. Durable Android text retry persistence merged in PR #312 (squash commit `28d4e4f24ab1c202c066257068b23979fadca4e6`); its instrumentation tests still need device execution. Durable image attachment retry across process death remains a separate gap.

## Database and security model

The migration set covers profiles, incidents, status logs, responder assignments, location reports, in-app notifications, FCM delivery attempts, incident messages, image attachment metadata/policies, and administrative audit records. Application data access is guarded through RLS, explicit grants, and role/ownership/assignment checks in RPCs.

Do not infer that every SECURITY DEFINER function is unsafe merely because it is callable by authenticated users. Review its owner, pinned search path, explicit EXECUTE grants, identity/role checks, and row/object ownership logic. Conversely, a pinned search path does not replace authorization checks. The `api_units()` dispatcher RPC retains its server-side role check and existing grants; PR #313 adds a migration to pin its lookup path to `pg_catalog`. That migration is committed in the repository but remains unapplied to the live Supabase project at this snapshot.

## Important capability boundaries

| Capability | Repository state | Verification boundary |
|---|---|---|
| Supabase-backed Android and dashboard | Implemented | Live account and production behavior must be checked separately |
| SOS and duplicate-submission protection | Implemented | Device/network failure paths need ongoing QA |
| Per-responder assignment status and claiming | Implemented | Validate full multi-account workflow with synthetic users |
| In-app notification inbox | Implemented | Remains authoritative when push is unavailable |
| FCM token registration and sender/ledger | Implemented | Device delivery and production worker scheduling are not guaranteed |
| Responder foreground location sharing | Implemented in source | Physical-device permission, battery, lifecycle, and OEM validation required |
| GPS map and incident pins | Implemented in source | Tile loading and GPS accuracy need physical-device validation |
| Route preview | Backend/provider support implemented; disabled by default | Synthetic provider and device route validation required |
| Offline requester recovery | Encrypted drafts and limited status snapshot | Not general offline write synchronization; device restart behavior needs validation |
| Incident chat | Text/image paths implemented | Text durable retry PR #312; image outbox persistence still outstanding |
| Admin user/audit foundation | Implemented in source/migrations | Verify role restrictions and UI acceptance before operational use |

## Live environment notes

A read-only Supabase inspection on 2026-11-16 found that the live migration ledger matched repository migrations through `20261116042927_responder_incident_claiming`. The private incident-chat image bucket had a 5 MiB limit and JPEG/PNG/WebP allowlist. The queried inventory did not include `pg_cron` or `pg_net`; no production Cron job was active. These are dated observations and must be rechecked before production setup.

The audit did not apply migrations, mutate data, enable extensions, create Vault secrets or Cron jobs, alter Render settings, send notifications, or deploy infrastructure.

## Release and safety boundary

TACResQ is an educational/engineering prototype, not an operational emergency service. FCM is supplementary and not a life-critical delivery channel. Static route previews do not establish road passability or emergency access. CI builds and source contracts do not prove physical GPS, notification, background, Doze, network, or routing behavior.

For exact PR and check status, consult GitHub rather than relying on this document. For durable requirements, research, and handoffs, use [project memory](docs/project-memory/README.md).
