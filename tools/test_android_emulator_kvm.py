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
setup = manual_workflow[kvm_step:emulator_step]
for required in (
    'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"',
    "sudo udevadm control --reload-rules",
    "sudo udevadm trigger --name-match=kvm",
    "if [ -e /dev/kvm ]; then",
):
    assert required in setup, f"Missing KVM setup contract: {required}"

print("Manual Android emulator dispatch and KVM setup contract passed.")
