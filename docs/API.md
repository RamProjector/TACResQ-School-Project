# TACResQ REST API contract (proposal v0.1)

This contract is used only when `USE_FAKE_API = false` and `USE_SUPABASE_API = false` (the Laravel/Render adapter). The Supabase adapter uses the RPC contract in [`SUPABASE_BACKEND.md`](SUPABASE_BACKEND.md).

The Android app expects these routes. They live in one file, `TacApi.kt`, with the JSON shapes in `Models.kt`.
If your Laravel API already exists with different routes or field names, change those two files instead of the backend.

## Conventions

- Base URL: `{APP_URL}/api` (Laravel `routes/api.php`).
- Auth: Laravel Sanctum personal access tokens, sent as `Authorization: Bearer <token>`.
- The app sends `Accept: application/json` on every request.
- Responses are **not** wrapped in `data`. In `AppServiceProvider::boot()` call `JsonResource::withoutWrapping();`.
- Errors use Laravel's default shape: `{ "message": "...", "errors": { "field": ["..."] } }`.
  Status codes: 401 not signed in, 403 wrong role, 404 not found, 422 validation or broken rule.
- Timestamps are ISO 8601 UTC, for example `2026-10-14T07:05:00.000000Z`.
- Enum values are lowercase snake_case strings.

## Enums

| Name | Values |
|---|---|
| role | `requester`, `responder`, `driver`, `dispatcher` |
| duty_status | `off_duty`, `available`, `en_route`, `on_scene`, `transporting` |
| incident type | `medical`, `fire`, `vehicular`, `disaster`, `other` |
| incident status | `pending`, `assigned`, `en_route`, `on_scene`, `transporting`, `resolved`, `cancelled` |

## Objects

User

```json
{ "id": 2, "name": "Rey Responder", "email": "responder@demo.com", "phone": "0918 123 4567",
  "role": "responder", "duty_status": "available" }
```

`duty_status` is `null` for requesters and dispatchers.

Incident

```json
{ "id": 12, "type": "medical", "description": "Chest pain", "latitude": 11.2433, "longitude": 125.0036,
  "accuracy_m": 12.0, "location_notes": null, "location_recorded_at": "2026-10-14T07:04:53.000000Z",
  "status": "assigned", "requester_name": "Rica Requester", "requester_phone": "0917 123 4567",
  "assigned_to": 2, "created_at": "2026-10-14T07:05:00.000000Z" }
```

`latitude` and `longitude` are either both present or both `null`. When GPS is unavailable or permission is declined, TACResQ may send the SOS without a pin; optional `location_notes` can provide a landmark. The server never substitutes a guessed or stale coordinate. `location_recorded_at` is the device fix time, not a live-tracking timestamp; it is `null` when no GPS fix was attached.

## Endpoints used by the app today

### POST /login (public)

Request: `{ "email": "...", "password": "..." }`
Response 200: `{ "token": "...", "user": { User } }`
Wrong credentials: 422 with a `message`.

### POST /register (public)

Request: `{ "name": "...", "email": "...", "phone": "...", "password": "..." }` (no password confirmation field; password must be at least 8 characters)
Response 200: `{ "token": "...", "user": { User } }`
Always creates a **requester**. Responders, drivers and dispatchers are created by an admin (seeder or the dispatcher dashboard).

### POST /logout (auth)

Deletes the current token. Response 204, empty body.

### GET /me (auth)

Response: `{ User }`. The app calls this to refresh the responder's duty status.

### PATCH /me/duty-status (responder or driver)

Request: `{ "duty_status": "available" }` or `"off_duty"`. Response: `{ User }`.
Rejected with 422 while the user has an active incident. The other duty statuses are set by the server, see below.

### GET /incidents/mine (auth)

Response: `[ { Incident }, ... ]`, newest first.
Requester: incidents they created. Responder or driver: incidents assigned to them, including finished ones.

### POST /incidents (requester)

Request with GPS: `{ "type": "medical", "description": "optional", "latitude": 11.2433, "longitude": 125.0036, "accuracy_m": 12.0, "location_notes": null, "location_recorded_at": "2026-10-14T07:04:53.000000Z" }`
Request without GPS: `{ "type": "medical", "description": "optional", "latitude": null, "longitude": null, "accuracy_m": null, "location_notes": "Blue gate beside the barangay hall", "location_recorded_at": null }`
Response 201: `{ Incident }` with `status: "pending"`.
Coordinates must be a valid pair; capture time and accuracy require a coordinate pair. The app reads one current fix only after the requester taps Android's system location button; that fix is transmitted only if the requester later confirms the SOS. No background or continuous requester tracking is implemented.

### PATCH /incidents/{id}/status (the assigned responder or driver)

Request: `{ "status": "en_route" }`. Response: `{ Incident }`.

Allowed transitions (anything else is 422):

| From | To |
|---|---|
| assigned | en_route |
| en_route | on_scene |
| on_scene | transporting, resolved |
| transporting | resolved |

Side effects the server must apply to the unit's `duty_status`:
`en_route` -> `en_route`, `on_scene` -> `on_scene`, `transporting` -> `transporting`, `resolved` -> `available`.
Only the user in `assigned_to` may change an incident's status (403 otherwise).

## Dispatcher endpoints used by the prototype

| Route | Purpose |
|---|---|
| `GET /incidents` | Dispatcher: full incident queue |
| `GET /units` | Dispatcher: responders and drivers with duty status |
| `POST /incidents/{id}/assign` | Dispatcher: `{ "user_id": 2 }`, assigns an available unit |

`POST /incidents/{id}/assign` should reject assignments when the incident is not `pending`, the selected user is not a responder/driver, or the unit is not `available`.

## Planned endpoints (not used by the current prototype)

| Route | Purpose |
|---|---|
| `POST /me/locations` | Batch of `{ latitude, longitude, accuracy_m, recorded_at }` for live tracking and offline sync |
| `POST /me/fcm-token` | Register the phone's FCM token for push notifications |
| `GET /incidents` | Dispatcher: full incident queue |
| `POST /incidents/{id}/assign` | Dispatcher: `{ "user_id": 2 }`, sets `assigned_to` and `status: "assigned"`, sends FCM |
| `GET /units` | Dispatcher: responders and drivers with duty status and last known position |

## Suggested tables

- `users`: name, email, phone, password, `role`, `duty_status`
- `incidents`: `requester_id`, `type`, `description`, `latitude`, `longitude`, `accuracy_m`, `status`, `assigned_to`
- `incident_status_logs`: `incident_id`, `status`, `changed_by`, `created_at` (audit trail and response-time reports)
- `location_logs`: `user_id`, `latitude`, `longitude`, `accuracy_m`, `recorded_at` (next phase)
