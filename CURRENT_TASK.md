# Current task handoff

**Last updated:** 2026-10-20
**Repository:** `RamProjector/TACResQ-School-Project`
**Base:** `main` at `320ddec6db46f7909c6ddf4c1493f4915f212702`
**Task branch:** `agent/android-gradle-wrapper`
**Related existing PR:** [#21 — Review-first autonomous coding workflow](https://github.com/RamProjector/TACResQ-School-Project/pull/21), separate and still unmerged when this task began.

## Objective

Add a complete Android Gradle project/wrapper so CI performs real debug/release builds, and retry the earlier Docker integration failure without changing Render or Supabase production configuration.

## Verified findings

- The Android source was missing root Gradle settings/build files, app module configuration, a main Android manifest, and the Gradle Wrapper.
- Existing README documents Android API 37 and the AndroidX location-button permissions.
- Build compatibility selected for this first build setup: JDK 17, Android Gradle Plugin 9.1.1, Kotlin/Compose plugins 2.2.10, Gradle 9.3.1, compile/target SDK 37. Gradle 9.3.1 distribution checksum is pinned in `gradle-wrapper.properties`. The wrapper JAR and its SHA-256 reference are from the upstream Gradle v9.3.1 source.
- Earlier CI failure was `docker compose up -d --build`, which received HTTP 502 from Docker Hub while fetching metadata for `node:26-alpine`. The failed job was rerun with GitHub Actions rerun-failed-jobs: run [39431224588, attempt 2](https://github.com/RamProjector/TACResQ-School-Project/actions/runs/39431224588) completed successfully. This supports a transient registry failure rather than a source defect. No Dockerfile change is justified by that evidence.
- No Render service, Supabase database, live data, or deployment settings were changed.

## Task changes prepared on the branch

- Add root `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, and `app/build.gradle.kts`.
- Add `app/src/main/AndroidManifest.xml` while retaining the existing debug-only cleartext manifest.
- Add Gradle 9.3.1 wrapper scripts, JAR, wrapper properties with the distribution checksum, and wrapper-JAR checksum file.
- Update Android CI to install API 37/build-tools 36.0.0, always run the Android source/wrapper-integrity check, and perform actual debug/release builds when the wrapper is present.
- Update README and QA/project/backend documentation that described the Android client as an unbuildable source pack or stated that responder foreground location was not wired into the RPC.
- No application Kotlin source or Supabase migrations are intentionally modified.

## Validation status

- Earlier documentation PR #21 CI, including Laravel/dashboard integration, passed on retry attempt 2. Android steps in that unrelated PR were skipped because no wrapper existed at that commit.
- This branch's Gradle build has **not yet run**; it must be validated by PR CI after committing these files.
- The local environment has no Gradle installation, and `git clone` could not reach GitHub due DNS/network restrictions. Use GitHub Actions to validate Gradle dependency resolution and APK builds.
- The wrapper JAR source Git blob SHA matches the upstream Gradle repository blob SHA `61285a659d17295f1de7c53e24fdf13ad755c379`. The CI source check validates the stored JAR against the official checksum file.
- Treat Android compilation as unverified until the actual `assembleDebug` and `assembleRelease` steps pass.

## Safety boundaries

TACResQ remains an educational prototype, not an emergency-services provider. Preserve authorization, explicit location consent, minimal location sharing, and truthful location freshness. Do not deploy, modify live data, apply migrations, or adjust production services for this task.

## Next actions

1. Commit the prepared Gradle, CI, and documentation changes to this task branch only.
2. Open a focused pull request to `main`; do not merge.
3. Inspect CI jobs and logs. Fix any reproducible Kotlin/Gradle/SDK/dependency errors found, and distinguish them from infrastructure/transient failures.
4. Verify the final run actually executes and passes `./gradlew --no-daemon assembleDebug` and `./gradlew --no-daemon assembleRelease`. If Docker Hub 502 recurs, rerun the failed integration job and only alter image sources/pinning if evidence shows a persistent issue.
5. Update this handoff with final commit, PR, and test results.
