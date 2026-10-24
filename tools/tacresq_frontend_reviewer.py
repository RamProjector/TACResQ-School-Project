#!/usr/bin/env python3
"""Static UI/UX reviewer for TACResQ Android and dispatcher web frontend diffs.

This is a triage aid, not a visual or usability test. It never executes PR code.
"""
from __future__ import annotations
import os, re, subprocess, sys
from dataclasses import dataclass

FRONTEND_SUFFIXES = (".kt", ".java", ".xml", ".tsx", ".ts", ".jsx", ".js", ".css", ".scss", ".html")
IGNORE_PREFIXES = ("docs/", "tools/", ".github/")
@dataclass(frozen=True)
class Finding:
    severity: str
    category: str
    path: str
    line: int
    message: str
    evidence: str

def run_git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, text=True, capture_output=True).stdout

def parse_added_lines(patch: str):
    line_no = 0
    for line in patch.splitlines():
        if line.startswith("@@"):
            match = re.search(r"\+(\d+)", line)
            if match: line_no = int(match.group(1))
        elif line.startswith(("+++", "\\\\")):
            continue
        elif line.startswith("+"):
            yield line_no, line[1:]
            line_no += 1
        elif line.startswith("-"):
            continue
        else:
            line_no += 1

def review_file(path: str, patch: str) -> list[Finding]:
    if not path.endswith(FRONTEND_SUFFIXES) or path.startswith(IGNORE_PREFIXES):
        return []
    lines = list(parse_added_lines(patch))
    findings: list[Finding] = []
    def add(severity, category, line, message, evidence):
        findings.append(Finding(severity, category, path, line, message, evidence.strip()[:180]))
    for i, (number, text) in enumerate(lines):
        s = text.strip()
        if not s or s.startswith(("//", "/*", "*", "<!--")):
            continue
        if re.search(r"Modifier\.(?:clickable|combinedClickable)\s*\(", s) and not any(
            re.search(r"(?:semantics\s*\{|contentDescription|onClickLabel|role\s*=)", lines[j][1])
            for j in range(max(0, i-4), min(len(lines), i+5))
        ):
            add("MEDIUM", "ACCESSIBILITY", number,
                "Clickable Compose UI may lack an accessible role/label. Verify TalkBack announces the action and its purpose.", s)
        if re.search(r"(?:size|width|height)\(\s*(?:[0-3][0-9])\.dp\s*\)", s) and any(
            re.search(r"clickable|IconButton|Button\s*\(", lines[j][1])
            for j in range(max(0, i-4), min(len(lines), i+5))
        ):
            add("MEDIUM", "TOUCH_TARGET", number,
                "A clickable control may have a dimension below Android's recommended 48dp touch target.", s)
        if re.search(r"(?:width|height)\(\s*\d{3,}\.dp\s*\)", s):
            add("LOW", "RESPONSIVE_LAYOUT", number,
                "Large fixed dp dimension may clip on smaller screens or with large font scaling; verify compact and accessibility layouts.", s)
        if re.search(r"\bLaunchedEffect\s*\(\s*Unit\s*\)", s) and re.search(r"location|gps|poll|refresh|request", patch, re.I):
            add("MEDIUM", "LIFECYCLE", number,
                "One-shot composition effect appears in a screen involving location or refresh work. Verify cancellation/restart behavior across lifecycle and navigation.", s)
        if re.search(r"\b(?:Toast\.makeText|Log\.[diwe]|printStackTrace\s*\()", s) and re.search(r"error|exception|fail", s, re.I):
            add("LOW", "ERROR_UX", number,
                "Error handling may be transient or developer-only. Prefer actionable inline feedback for recoverable user-facing failures.", s)
        if re.search(r"\b(?:requestHelp|submitSos|createIncident|sendSos)\b", s, re.I) and re.search(r"onClick|clickable|launch", s, re.I):
            if not re.search(r"isSubmitting|isSending|inFlight|submissionInProgress|isLoading", patch, re.I):
                add("HIGH", "EMERGENCY_DOUBLE_SUBMIT", number,
                    "Emergency submission action changed without an obvious in-flight guard in this diff. Verify idempotency and disable duplicate taps while submitting.", s)
    # TACResQ-specific review gates: always report as manual checks when relevant screen files change.
    lower = path.lower()
    if lower.endswith((".kt", ".java")) and any(k in lower for k in ("map", "location", "gps")):
        add("MANUAL", "GPS_DEVICE_CHECK", 0,
            "Static analysis cannot verify device location services, runtime permission denial/revocation, stale fixes, map tiles, manual pin provenance, or lifecycle return from Settings.",
            "Required: test permission allow/deny/permanent denial, device Location off/on, no-fix and stale-fix states, network loss, map interaction, and returning from Settings.")
    if "requester" in lower or "incident" in lower or "sos" in lower:
        add("MANUAL", "REQUESTER_WORKFLOW", 0,
            "Verify one active request per requester, server-side duplicate protection, safe accidental-edit/cancel recovery, and honest server-confirmed status.",
            "Required: retry/timeout, double tap, offline submission, cancel then create, draft discard confirmation, and process recreation.")
    if any(k in lower for k in ("responder", "dispatch", "assignment")):
        add("MANUAL", "ROLE_WORKFLOW", 0,
            "Verify role-appropriate navigation, server-authorized actions, per-responder status, consent and unavailable/error states.",
            "Required: unauthorized role, stale assignment, no work, loading, upstream failure, and returning to a screen after backgrounding.")
    return findings

def main() -> int:
    base = os.environ.get("BASE_SHA", "")
    head = os.environ.get("HEAD_SHA", "HEAD")
    if not base:
        print("BASE_SHA is required", file=sys.stderr)
        return 2
    try:
        changed = run_git("diff", "--name-only", f"{base}...{head}").splitlines()
        raw = run_git("diff", "--unified=4", f"{base}...{head}", "--", *[p for p in changed if p.endswith(FRONTEND_SUFFIXES)])
    except subprocess.CalledProcessError as exc:
        print(exc.stderr, file=sys.stderr)
        return 2
    findings: list[Finding] = []
    # Parse each file patch independently to keep paths attached to findings.
    chunks = re.split(r"(?=^diff --git )", raw, flags=re.M)
    for chunk in chunks:
        match = re.search(r"^diff --git a/(.*?) b/(.*?)$", chunk, re.M)
        if match:
            findings.extend(review_file(match.group(2), chunk))
    rank = {"HIGH": 0, "MEDIUM": 1, "LOW": 2, "MANUAL": 3}
    findings.sort(key=lambda f: (rank.get(f.severity, 9), f.path, f.line, f.category))
    counts = {level: sum(f.severity == level for f in findings) for level in ("HIGH", "MEDIUM", "LOW", "MANUAL")}
    lines = [
        "# TACResQ Frontend Review",
        "",
        f"Reviewed **{len(changed)} changed files** between `{base[:12]}` and `{head[:12]}`.",
        "",
        f"**Findings:** {counts['HIGH']} high · {counts['MEDIUM']} medium · {counts['LOW']} low · {counts['MANUAL']} manual verification gates",
        "",
        "> This is heuristic static triage. It does not render screens, run Android, inspect runtime state, or prove usability. Findings need human triage; absence of findings is not approval.",
        "",
    ]
    if not findings:
        lines += ["No configured frontend heuristics matched the added lines.", ""]
    for f in findings:
        loc = f"{f.path}:{f.line}" if f.line else f.path
        lines += [f"## {f.severity}: {f.category}", f"- **Location:** `{loc}`", f"- {f.message}", f"- Evidence/check: {f.evidence}", ""]
    lines += [
        "## TACResQ review checklist",
        "- [ ] Navigation labels and selected state are clear; system Back and role changes behave correctly.",
        "- [ ] GPS/map remains a primary destination; location is never fabricated and stale/unavailable fixes are explicit.",
        "- [ ] Requester has at most one active request; accidental edits/cancellation and duplicate taps are safely handled.",
        "- [ ] Responder/dispatcher actions reflect server-authoritative permissions and confirmed backend results.",
        "- [ ] Loading, empty, error, offline, retry, and permission-denied states are distinct and actionable.",
        "- [ ] TalkBack labels, contrast, 48dp targets, font scaling, small screens, and dark/light themes are checked.",
        "- [ ] Compose work, polling, location callbacks, and map resources follow lifecycle and process recreation.",
        "- [ ] Visual review uses screenshots/emulator or device; backend integration is tested separately from mock/demo mode.",
        "",
        "**Sign-off rule:** CI/static review alone cannot approve UI quality or operational emergency readiness. Record device and end-to-end checks separately.",
        "",
    ]
    report = "\n".join(lines)
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(report + "\n")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
