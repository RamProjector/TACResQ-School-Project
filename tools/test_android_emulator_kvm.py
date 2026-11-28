#!/usr/bin/env python3
"""Guard the manually dispatched Android emulator workflow against losing KVM setup."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
ci_workflow = (root / ".github/workflows/ci.yml").read_text()
manual_workflow = (root / ".github/workflows/android-emulator-manual.yml").read_text()

assert "android-emulator-smoke:" not in ci_workflow, (
    "Android emulator UI tests must not run as an automatic pull-request check."
)
assert "pull_request:" not in manual_workflow, (
    "The emulator workflow must not subscribe to pull-request events."
)
assert "workflow_dispatch:" in manual_workflow, (
    "The emulator workflow must be manually dispatchable."
)

kvm_step = manual_workflow.find("- name: Enable KVM permissions for Android emulator")
emulator_step = manual_workflow.find("uses: reactivecircus/android-emulator-runner@")
assert kvm_step >= 0, "Manual Android emulator workflow must configure KVM permissions."
assert emulator_step > kvm_step, "KVM permissions must be configured before emulator startup."
assert "uses: reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d # v2" in manual_workflow, (
    "Android emulator runner must remain pinned to its reviewed full commit SHA."
)
assert "script: ./gradlew --no-daemon connectedDebugAndroidTest" in manual_workflow, (
    "Manual emulator workflow must execute the connected Android instrumentation suite."
)
assert "uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7" in manual_workflow, (
    "Instrumentation result upload action must remain pinned to its reviewed full commit SHA."
)

log_capture = manual_workflow.find("- name: Capture Android emulator diagnostics on failure")
artifact_upload = manual_workflow.find("- name: Upload Android instrumentation results")
assert log_capture > emulator_step, "Emulator diagnostics must be captured after the instrumented test step."
assert artifact_upload > log_capture, "Instrumentation results must be uploaded after diagnostic capture."
for required in (
    "if: failure()",
    "adb logcat -d > artifacts/android-emulator/logcat.txt",
    "if: always()",
    "tacresq-android-instrumentation-results",
    "app/build/outputs/androidTest-results/connected/",
    "app/build/reports/androidTests/connected/",
    "retention-days: 7",
):
    assert required in manual_workflow, f"Missing emulator diagnostics/artifact contract: {required}"
setup = manual_workflow[kvm_step:emulator_step]
for required in (
    'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"',
    "sudo udevadm control --reload-rules",
    "sudo udevadm trigger --name-match=kvm",
    "if [ -e /dev/kvm ]; then",
):
    assert required in setup, f"Missing KVM setup contract: {required}"

print("Manual Android emulator dispatch and KVM setup contract passed.")
