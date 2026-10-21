# Testing the real Supabase-backed Android app

Use this guide when you need to test real authentication and database RPCs instead of the in-memory demo backend.

## 1. Choose the correct APK

The GitHub Actions artifact named `tacresq-android-apks` contains:

- `tacresq-demo-debug.apk` — fictional in-memory accounts and incidents; no live Supabase writes.
- `tacresq-supabase-debug.apk` — uses the configured Supabase project and real Auth/RPC calls.
- `tacresq-release.apk` — real Supabase mode; unsigned unless release-signing secrets are configured.

The two debug APKs share the same Android application ID. Installing one replaces the other.

For a local real-backend debug build:

```bash
./gradlew --no-daemon assembleDebug -PuseSupabase=true
```

## 2. Provision test identities

**Prefer a separate Supabase project for QA.** The checked-in Android configuration points at the configured project, so any incident created by the Supabase debug APK is persistent database data. Never test with real emergency reports or real people's contact/location details.

In the Supabase Dashboard for the intended QA project:

1. Open **Authentication → Users** and create dedicated test users for requester, responder, driver, and dispatcher. Use mailboxes you control, unique strong passwords, and mark the test users confirmed if your test setup requires confirmation.
2. Verify that the profile-creation trigger created a matching row in `public.profiles` for each Auth user. It runs when the Auth user is created; it creates requester profiles only and intentionally does not trust public metadata to grant privileged roles. If a profile is missing, troubleshoot the trigger instead of inserting directly into `auth.users`.
3. Open **SQL Editor** and replace the example email addresses below with the exact emails of those test accounts. Run this only against your intended QA project.

```sql
update public.profiles
set
  role = case lower(email)
    when lower('qa-requester@your-domain.example') then 'requester'
    when lower('qa-responder@your-domain.example') then 'responder'
    when lower('qa-driver@your-domain.example') then 'driver'
    when lower('qa-dispatcher@your-domain.example') then 'dispatcher'
  end,
  duty_status = case
    when lower(email) in (lower('qa-responder@your-domain.example'),
                          lower('qa-driver@your-domain.example')) then 'available'
    else null
  end
where lower(email) in (
  lower('qa-requester@your-domain.example'),
  lower('qa-responder@your-domain.example'),
  lower('qa-driver@your-domain.example'),
  lower('qa-dispatcher@your-domain.example')
);
```

Confirm that all four profiles were found and have the intended roles before testing:

```sql
select email, role, duty_status
from public.profiles
where lower(email) in (
  lower('qa-requester@your-domain.example'),
  lower('qa-responder@your-domain.example'),
  lower('qa-driver@your-domain.example'),
  lower('qa-dispatcher@your-domain.example')
)
order by role;
```

If fewer than four rows return, stop and resolve the missing Auth/profile records rather than assuming the accounts are ready. Do not put passwords, service-role keys, or other secrets into this SQL or into the Android app.

## 3. Minimum verification flow

- Requester: sign in, create a clearly labeled **test-only** incident using a test location, and confirm it appears in request history.
- Dispatcher: sign in, verify the queue and field-unit list, then assign the test incident to an available test responder/driver.
- Responder/driver: sign in, confirm the assigned incident, move through the allowed status sequence, and test foreground location sharing only with consent.
- Dispatcher: verify assignment/status updates and fresh field-unit location reports on the map.
- Authentication: test invalid email, missing fields, wrong password, offline/network failure, and successful sign-out.

Delete test incidents and test Auth users when QA is finished. In the Supabase Dashboard, review **Authentication → Settings → Password Security** and enable leaked-password protection where available. This is an Auth project setting, not a SQL migration; CI cannot enable it for the live project.

## 4. What CI does not prove

CI compiles the APKs and runs source-contract, prototype, and backend integration tests. It does **not** prove that a real phone can obtain GPS, load OpenStreetMap tiles on its network, receive a current location fix, or successfully authenticate against the live project's current Auth settings. Perform those checks on a physical device before relying on the app.
