# Requester Location-Edit Validation Review — 2026-11-16

## Confirmed finding

The live `public.api_update_my_incident(...)` SECURITY DEFINER RPC did not fully match the validation contract in `public.api_create_incident_v3(...)` and the responder location writer.

The edit RPC:
- accepted manual pins with non-null GPS accuracy;
- did not require a location timestamp when a pin was changed;
- did not bound accuracy to the same 0–100,000 metre range used by incident creation;
- did not reject changed-pin timestamps more than five minutes in the future or more than fifteen minutes old.

The database's existing location-source check prevents some invalid source/coordinate combinations, but it does not encode the full provenance/freshness contract.

## Correction in this PR

Migration `20261116190000_validate_requester_location_edits.sql` replaces the current edit RPC while preserving requester ownership checks, row locks, audit log writes, cooldown, urgent location-only correction behavior, and authenticated-only execute grants.

It adds:
- explicit null and supported-value validation for incident type and location source;
- accuracy bound of 0–100,000 metres;
- manual map pins must not claim GPS accuracy;
- changed location metadata must carry a timestamp;
- changed-pin timestamp may be at most five minutes in the future and fifteen minutes old.

A text-only edit may retain unchanged existing location metadata, including its original capture time. This avoids forcing the requester to obtain another GPS fix merely to correct the incident description. Any changed coordinate, accuracy value, source, or capture timestamp must satisfy freshness validation.

## Validation and limitations

- A Python source-contract test is included in CI.
- Clean PostgreSQL migration replay and the broader CI suite must pass on the exact PR head.
- No live Supabase migration was applied.
- No production data, Render settings, secrets, notification settings, or infrastructure were changed.
- Physical-device GPS behavior remains a separate manual validation requirement.

## Research comparison

- Android location APIs expose accuracy and elapsed/recorded time as distinct properties; a manual map selection is not a GPS measurement and should not be assigned GPS accuracy.
- PostgreSQL CHECK constraints protect row invariants, but the RPC must validate semantic freshness and provenance that depend on the submitted change.
- OWASP API Security recommends server-side validation of untrusted client input and object-level authorization; this change preserves the requester ownership and row-lock checks while tightening semantic validation.

References:
- Android location permissions and location data: https://developer.android.com/develop/sensors-and-location/location/permissions
- Android location APIs: https://developer.android.com/develop/sensors-and-location/location
- OWASP API Security Top 10: https://owasp.org/www-project-api-security/
- PostgreSQL constraints: https://www.postgresql.org/docs/current/ddl-constraints.html

## Additional provenance audit — provider identity and recording API

A source-level review of the Android location path found that the current schema records capture mode, not the actual platform provider or the API path that persisted a coordinate.

Confirmed:
- `incidents.location_source` distinguishes `gps`, `manual`, and `none`. It does not identify the Android provider implementation.
- `LocationProvider.kt` obtains fixes through `FusedLocationProviderClient` and converts them to `GeoFix` using latitude, longitude, accuracy, wall-clock capture time, elapsed-realtime time, and permission precision. The conversion does not preserve `Location.provider`.
- The responder tracking service sends fixes through `api_record_location_v2`. The RPC and `location_logs` row do not store a provider identifier or a recording-operation identifier.
- The existing server receipt timestamp (where present) is distinct from the device capture timestamp; it does not identify which RPC or external provider supplied the coordinate.

Do not conflate these provenance dimensions:
1. **Capture method:** GPS-backed device fix versus manual map selection.
2. **Platform location provider:** a client-reported Android provider label such as `fused`. This is diagnostic metadata, not proof of GNSS hardware origin or an attested location.
3. **Recording operation:** the server-known RPC/function that accepted the write. This should be recorded server-side as a fixed operation identifier, not accepted as arbitrary client text.
4. **Map-content provider:** the map/tile service used to render a map or support manual selection.
5. **Routing provider:** the external route service that may receive origin/destination coordinates. It is a recipient of location data, not the source of the device fix.

Recommended follow-up:
- Keep capture method as a constrained field.
- If provider diagnostics are required, add a separate short, allowlisted `platform_location_provider` field to location records and define whether it applies to requester incident pins, responder tracking, or both.
- Record the recording RPC/action as a server-assigned constant or controlled audit event. Do not trust a caller-supplied `rpc_name` field.
- Record map and routing providers separately only where the app actually invokes them; do not infer a map provider from a GPS fix.
- Bound any provider label (for example, 64 characters), normalize it to an allowlist, and treat it as untrusted client metadata. It must never influence authorization, dispatch eligibility, or emergency status.
- Preserve server receipt time separately from client capture time, and define retention/access rules because location coordinates and provider metadata are sensitive.

The present schema has no provider-name field, so there is currently no provider-name length limit to report. The existing `location_source` domain is constrained to the supported capture-mode values; it is not a free-form provider-name column. Adding provider metadata requires a separately reviewed schema/API/client change and compatibility tests; this validation PR does not silently introduce an unverified provider contract.

This limitation is consistent with Google's fused-location documentation: clients cannot select the exact sensors used to satisfy a fused location request, and the fused provider may combine several sensor/provider results into one estimate. The Android `fused` provider name therefore identifies the fused API path, not which underlying GNSS, Wi-Fi, cellular, or other signals contributed to an individual fix. Keep it as diagnostic provenance only. See the official [FusedLocationProviderClient reference](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient), [LocationRequest reference](https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest), and [Android LocationManager provider constants](https://developer.android.com/reference/android/location/LocationManager#FUSED_PROVIDER).


## Client timestamp trust boundary

The freshness checks on `p_location_recorded_at` validate a client-supplied timestamp's allowed range; they do **not** prove when the coordinate was physically captured, that the device clock is accurate, that the fix came from GNSS, or that the requester is physically present at that location. A modified client can submit a plausible timestamp with fabricated coordinates. Treat this field as reported capture metadata only, never as an authorization signal, responder-presence attestation, or evidence of physical location.

Keep the following times distinct:
- `location_recorded_at`: client-reported capture/selection time, subject to the RPC's five-minute future and fifteen-minute age bounds when location metadata changes.
- Server receipt/row-write time: generated by the database or platform and trusted as the time the server accepted the write, not as the time the device observed the location.

The server receipt time does not authenticate the location itself. If a later design needs stronger location assurance, it requires a separate threat model and platform integrity/anti-spoofing assessment; adding a provider label or timestamp alone cannot provide that assurance.

### RPC/data-dictionary contract

The `api_update_my_incident(..., p_location_recorded_at, ...)` parameter is explicitly **client-reported capture/selection time**. The function's PostgreSQL comment now carries this definition for schema introspection and API-contract tooling. Consumers must not rename or describe it as `server_recorded_at`, `verified_at`, `gps_time`, or a location-attestation field. The database's server-generated receipt/write timestamp is a separate fact: it establishes when the database accepted the change, not when or where the device obtained the coordinate. The timestamp freshness check is input validation only and must never participate in authorization, dispatch eligibility, responder-presence decisions, or proof of physical location.
