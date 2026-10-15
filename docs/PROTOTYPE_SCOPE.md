# TACResQ UI Prototype Scope

## Objective

This version is a **free, local-only Android UI prototype**. It uses the in-memory fake backend already included in the starter project. No paid APIs, subscriptions, cloud accounts, billing, or external server are required to run the demo.

The prototype prioritizes working role-based flows over styling, branding, animations, and production deployment.

## Completed prototype flows

| Requirement from Chapters 1–3 | Prototype implementation | Verification |
|---|---|---|
| Emergency requester submits an incident | Requester screen with emergency type, description, and GPS capture | Sign in as requester and send SOS |
| Incident location is included | GPS coordinates and accuracy are stored in the fake incident | Confirm coordinates on requester/dispatcher cards |
| Responder and driver roles | Shared field-unit screen for responder and driver accounts | Sign in with both demo accounts |
| Duty status updates | Available/off-duty controls with active-incident restrictions | Set responder Available |
| Dispatcher incident queue | Functional dispatcher console with pending and historical incidents | Sign in as dispatcher |
| Unit visibility | Dispatcher sees responder/driver names, roles, phone numbers, and duty status | Check Field units section |
| Manual dispatch assignment | Dispatcher can assign a pending incident to an Available unit | Select Assign on a pending incident |
| Status progression | Assigned → En route → On scene → Transporting/Resolved | Sign in as assigned unit and advance status |
| Requester progress visibility | Requester list refreshes and shows incident status | Sign back in as requester |
| Navigation handoff | Responder can open coordinates in an installed maps app | Tap Navigate in Google Maps |
| Role-based access | Fake API rejects unauthorized role operations | Try the wrong role or inspect API rules |

## Deliberately deferred

These are not part of the free UI prototype milestone:

- Laravel/MySQL deployment
- Firebase Cloud Messaging
- Embedded map SDK or map API key
- Background live GPS service
- Offline queue and synchronization
- Production token encryption and HTTPS deployment
- Dashboard reports and analytics
- Visual branding, custom fonts, animation, and dynamic effects

The API interface and documentation include extension points for the real backend, but the prototype remains runnable without a server.

## Demo accounts

All demo accounts use password `demo123`:

- `requester@demo.com`
- `responder@demo.com`
- `driver@demo.com`
- `dispatcher@demo.com`

## Manual end-to-end test

1. Create a fresh Android Studio **Empty Activity** project with package `com.tacresq.app` and minimum SDK 26.
2. Merge the `app/`, `docs/`, and source files from this archive into the project as described in `README.md`.
3. Add the dependencies and manifest permissions listed in `README.md`.
4. Run the app with an emulator GPS location configured, or on an Android phone with location permission enabled.
5. Sign in as `responder@demo.com`, set the duty status to **Available**, and log out.
6. Sign in as `requester@demo.com`, grant location permission, and send a medical SOS.
7. Sign in as `dispatcher@demo.com`. The incident should appear in the queue. Assign it manually to the available responder, or wait for the fake dispatch demonstration.
8. Sign in as `responder@demo.com` and move the incident through the available status buttons.
9. Sign in as `requester@demo.com` and confirm the incident status updates.

## Acceptance criteria for this milestone

- The project opens in Android Studio without requiring a paid account.
- The fake backend supports the full demo flow without an internet connection.
- A dispatcher can see incidents and available units.
- A dispatcher can manually assign a pending incident.
- A field unit can update the assigned incident status.
- The requester can see the updated status.
- No screen depends on FCM, a map API key, or a cloud backend.
