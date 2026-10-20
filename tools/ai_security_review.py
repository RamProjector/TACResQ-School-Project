#!/usr/bin/env python3
"""Run local security guardrails and optionally ask an LLM for a code review.

The deterministic checks always run. The AI step is opt-in with --ai and needs
OPENAI_API_KEY (and optionally OPENAI_API_BASE / AI_SECURITY_MODEL). The script
sends only bounded source excerpts; it never reads .env files or git secrets.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEXT_EXTENSIONS = {".kt", ".java", ".php", ".ts", ".tsx", ".js", ".sql", ".yml", ".yaml", ".json"}
SKIP_PARTS = {".git", "node_modules", "vendor", "build", "dist", "screenshots", "baseline"}
RULES = (
    ("possible secret assignment", re.compile(r"(?i)(api[_-]?key|secret|password|token)\\s*[:=]\\s*[\\\"'][^\\\"']{12,}")),
    ("service-role key in client source", re.compile(r"(?i)(service[_-]?role|supabase_service_role)[_-]?(key|token)?\s*[:=]\s*[\"']?[A-Za-z0-9._-]{12,}")),
    ("unsafe HTML injection", re.compile(r"(?i)(dangerouslySetInnerHTML|innerHTML\\s*=)")),
    ("disabled TLS verification", re.compile(r"(?i)(verify\\s*=\\s*false|trustAll|hostnameVerifier)")),
    ("debug mode in production config", re.compile(r"(?i)(APP_DEBUG\\s*[:=]\\s*true|debug\\s*[:=]\\s*true)")),
)


def source_files() -> list[Path]:
    files = []
    for path in ROOT.rglob("*"):
        if path.is_file() and path.suffix in TEXT_EXTENSIONS and not any(part in SKIP_PARTS for part in path.parts):
            files.append(path)
    return sorted(files)


def local_scan() -> list[dict[str, object]]:
    findings: list[dict[str, object]] = []
    for path in source_files():
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for name, pattern in RULES:
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                findings.append({"rule": name, "file": str(path.relative_to(ROOT)), "line": line})
    return findings


def ai_review(files: list[Path]) -> str:
    key = os.getenv("OPENAI_API_KEY")
    if not key:
        return "AI review skipped: OPENAI_API_KEY is not configured."
    base = os.getenv("OPENAI_API_BASE", "https://api.openai.com/v1").rstrip("/")
    model = os.getenv("AI_SECURITY_MODEL", "gpt-4o-mini")
    excerpts = []
    budget = 45_000
    for path in files:
        text = path.read_text(encoding="utf-8", errors="ignore")
        if len(text) > 4_000:
            text = text[:4_000] + "\n...[truncated]"
        block = f"FILE: {path.relative_to(ROOT)}\n{text}"
        if sum(len(item) for item in excerpts) + len(block) > budget:
            break
        excerpts.append(block)
    prompt = (
        "Review this educational emergency-coordination project for exploitable security bugs. "
        "Prioritize auth/RLS/role escalation, location privacy, secret exposure, injection, and unsafe logging. "
        "Return concise findings with severity, file/line evidence, impact, and a concrete fix. "
        "Do not invent issues; say no finding when evidence is absent.\n\n" + "\n\n".join(excerpts)
    )
    payload = json.dumps({"model": model, "temperature": 0, "messages": [
        {"role": "system", "content": "You are a defensive application-security reviewer."},
        {"role": "user", "content": prompt},
    ]}).encode()
    request = urllib.request.Request(
        f"{base}/chat/completions", data=payload,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"}, method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        data = json.loads(response.read())
    return data["choices"][0]["message"]["content"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ai", action="store_true", help="Run the optional model review")
    parser.add_argument("--json", action="store_true", help="Emit deterministic findings as JSON")
    args = parser.parse_args()
    findings = local_scan()
    if args.json:
        print(json.dumps({"findings": findings}, indent=2))
    else:
        print(f"LOCAL SECURITY SCAN: {len(findings)} finding(s)")
        for item in findings:
            print(f"{item['file']}:{item['line']} [{item['rule']}]")
    if args.ai:
        print("\nAI SECURITY REVIEW\n" + ai_review(source_files()))
    if findings:
        raise SystemExit("security guardrail failed; review findings before merging")
    print("SECURITY GUARDRAILS PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
