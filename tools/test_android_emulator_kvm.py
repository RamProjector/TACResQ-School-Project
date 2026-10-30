#!/usr/bin/env python3
"""Guard the Android emulator CI against accidentally losing Linux KVM setup."""
from pathlib import Path

workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml").read_text()
kvm_step = workflow.find("- name: Enable KVM permissions for Android emulator")
emulator_step = workflow.find("uses: reactivecircus/android-emulator-runner@")

assert kvm_step >= 0, "Android emulator job must configure KVM permissions."
assert emulator_step > kvm_step, "KVM permissions must be configured before emulator startup."
setup = workflow[kvm_step:emulator_step]
for required in (
    'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"',
    "sudo udevadm control --reload-rules",
    "sudo udevadm trigger --name-match=kvm",
    "if [ -e /dev/kvm ]; then",
):
    assert required in setup, f"Missing KVM setup contract: {required}"

print("Android emulator KVM setup contract passed.")
