#!/usr/bin/env python3
"""Regression checks for dangerous GitHub Actions workflow patterns."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"


def main() -> None:
    files = sorted([*WORKFLOWS.glob("*.yml"), *WORKFLOWS.glob("*.yaml")])
    assert files, "No GitHub Actions workflows were found"

    for path in files:
        source = path.read_text(encoding="utf-8")
        relative = path.relative_to(ROOT)

        assert not re.search(r"(?m)^\s*permissions:\s*write-all\s*$", source), (
            f"{relative}: wildcard write permissions are forbidden"
        )

        assert not re.search(r"(?m)^\\s*uses:\\s*[^\\s]+@(latest|main|master)\\b", source), (
            f"{relative}: mutable latest/main/master action references are forbidden"
        )

        if re.search(r"(?m)^\s*pull_request_target\s*:", source):
            checkout_head = re.search(
                r"uses:\s*actions/checkout@[^\n]+"
                r"(?:\n(?!\s*- uses:).*){0,8}"
                r"\n\s+ref:\s*\$\{\{\s*github\.event\.pull_request\.head\.",
                source,
            )
            assert checkout_head is None, (
                f"{relative}: never check out untrusted pull-request head code in pull_request_target"
            )

        untrusted_shell_expression = re.search(
            r"(?ms)^\s+run:\s*(?:\|\s*)?[^\n]*"
            r".{0,500}?\$\{\{\s*github\.event\.pull_request\."
            r"(?:title|body|head\.ref|head\.label|comment\.body)",
            source,
        )
        assert untrusted_shell_expression is None, (
            f"{relative}: untrusted pull-request text must not be interpolated directly into shell"
        )

    security_path = WORKFLOWS / "security.yml"
    security = security_path.read_text(encoding="utf-8")
    assert re.search(r"(?m)^permissions:\n  contents: read\s*$", security), (
        "Security workflow must default to contents: read only"
    )
    guardrails = security.split("  codeql-android:", 1)[0]
    assert "security-events: write" not in guardrails, (
        "Repository guardrails and dependency review must not receive CodeQL write permissions"
    )
    for job in ("codeql-android:", "codeql-web-actions:"):
        section = security.split(job, 1)[1]
        assert re.search(r"(?m)^      security-events: write$", section), (
            f"{job} must receive security-events: write to upload CodeQL results"
        )

    assert "vars.DEPENDENCY_GRAPH_ENABLED == 'true'" in security, (
        "Dependency review must run only after the repository dependency graph is enabled"
    )
    assert "fail-on-severity: moderate" in security, (
        "Dependency review must fail on newly introduced moderate-or-higher vulnerabilities"
    )
    assert "Explain dependency review prerequisite" in security, (
        "Skipped dependency review must produce an actionable warning, not silently pass"
    )

    quality = (WORKFLOWS / "quality-assurance.yml").read_text(encoding="utf-8")
    assert "STRESS_REQUESTS:" in quality and "inputs.stress_requests" in quality, (
        "Manual stress-test input must be passed through an environment variable"
    )
    stress_step = re.search(
        r"(?ms)- name: Run bounded GET stress test\\n(.*?)(?=\\n      - name:|\\Z)", quality
    )
    assert stress_step and '--requests "$STRESS_REQUESTS"' in stress_step.group(1), (
        "Stress-test input must be quoted as an environment variable"
    )
    assert "${{ inputs.stress_requests" not in stress_step.group(1).split("run:", 1)[-1], (
        "Untrusted workflow input must not be interpolated into shell source"
    )
    assert not any("anomalyco/opencode/github" in path.read_text(encoding="utf-8") for path in files), (
        "The retired OpenCode GitHub Action must not be referenced by workflows"
    )

    print(f"Workflow security regression checks passed ({len(files)} workflow files).")


if __name__ == "__main__":
    main()
