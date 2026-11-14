from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
workflow = (ROOT / ".github/workflows/signed-release-readiness.yml").read_text()
runbook = (ROOT / "docs/FCM_OPERATIONS_RUNBOOK.md").read_text()
qa = (ROOT / "docs/PHYSICAL_DEVICE_QA_MATRIX.md").read_text()
render = (ROOT / "render-fcm-cron.example.yaml").read_text()
research = (ROOT / "docs/RESEARCH_TARGET2_RELEASE_READINESS.md").read_text()

def check(label, passed):
    print(f"{'PASS' if passed else 'FAIL'} {label}")
    return passed

checks = [
    check("target2 release research exists before controls", "Status:** Research-only" in research),
    check("signed release workflow is manual only", "workflow_dispatch:" in workflow and "push:" not in workflow and "pull_request:" not in workflow),
    check("signed release requires all keystore inputs", all(token in workflow for token in (
        "ANDROID_SIGNING_KEYSTORE_BASE64", "ANDROID_SIGNING_KEY_ALIAS",
        "ANDROID_SIGNING_KEY_PASSWORD", "ANDROID_SIGNING_STORE_PASSWORD",
    ))),
    check("signed release fails closed and verifies APK", "test -n \"$ANDROID_SIGNING_KEYSTORE_BASE64\"" in workflow and "apksigner\" verify" in workflow),
    check("signed release uploads checksum", "sha256sum" in workflow and "upload-artifact" in workflow),
    check("FCM runbook isolates private worker credentials", "must remain server-side" in runbook and "must not be exposed as a public HTTP route" in runbook and "SUPABASE_SECRET_KEY" in runbook and "GOOGLE_APPLICATION_CREDENTIALS" in runbook),
    check("FCM runbook defines aggregate observability", all(token in runbook for token in (
        "run identifier", "invalid-token cleanup count", "oldest due failed-attempt age",
    ))),
    check("FCM runbook has check-only and rollback paths", "--check-config" in runbook and ("pause the private Cron Job" in runbook or "pause the approved worker/Cron Job" in runbook)),
    check("physical QA matrix separates unverified evidence", "Not run: device/live QA required" in qa and "synthetic accounts" in qa),
    check("Render FCM example contains no credential values", "tacresq-fcm-dispatch" in render and "sync: false" in render and "service-account.json" in render),
]
if not all(checks):
    raise SystemExit(1)
print(f"Target-2 release controls contract OK: {len(checks)} checks.")
