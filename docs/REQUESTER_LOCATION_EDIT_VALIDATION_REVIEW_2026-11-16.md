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

A text-only edit may retain unchanged existing location metadata, including its original capture time. This avoids forcing the requester to obtain another GPS fix merely to correct the incident description. Any changed coordinate, source, or capture timestamp must satisfy freshness validation.

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
