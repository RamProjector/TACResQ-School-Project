#!/usr/bin/env python3
"""Deterministic first-pass TACResQ pull-request reviewer; standard library only."""
from __future__ import annotations
import json, os, re, sys, urllib.error, urllib.request
from dataclasses import dataclass
API="https://api.github.com"
REPO,TOKEN=os.environ.get("GITHUB_REPOSITORY",""),os.environ.get("GITHUB_TOKEN","")
PR_NUMBER,HEAD_SHA=os.environ.get("PR_NUMBER",""),os.environ.get("PR_HEAD_SHA","")
@dataclass(frozen=True)
class Finding:
 severity:str
 rule:str
 path:str
 line:int|None
 message:str
 evidence:str
RULES=[
 ("CRITICAL","PRIVATE_KEY",re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")),
 ("CRITICAL","SUPABASE_SERVICE_ROLE",re.compile(r"SUPABASE_SERVICE_ROLE_KEY|sb_secret_[A-Za-z0-9_-]{12,}|service_role\s*[:=]\s*['\"][^'\"]{12,}",re.I)),
 ("HIGH","DISABLE_TLS_VALIDATION",re.compile(r"(verify\s*=\s*False|CURLOPT_SSL_VERIFYPEER\s*,\s*false|TrustAllCerts|ALLOW_ALL_HOSTNAME_VERIFIER)",re.I)),
 ("HIGH","PERMISSIVE_RLS_POLICY",re.compile(r"CREATE\s+POLICY[\s\S]{0,300}USING\s*\(\s*true\s*\)|WITH\s+CHECK\s*\(\s*true\s*\)",re.I)),
 ("HIGH","RAW_SQL_CONCAT",re.compile(r"(?:DB::select|DB::statement|queryRaw|execSQL)\s*\([^\n]*(?:\$[A-Za-z_]|{\s*[A-Za-z_]| \+\s*[A-Za-z_])",re.I)),
 ("HIGH","SECRET_IN_SOURCE",re.compile(r"(?:password|secret|api[_-]?key|access[_-]?token)\s*[:=]\s*['\"][^'\"]{12,}['\"]",re.I)),
 ("MEDIUM","DISABLE_APP_DEBUG",re.compile(r"APP_DEBUG\s*[:=]\s*['\"]?true",re.I)),
 ("MEDIUM","UNSAFE_PERMISSIVE_CORS",re.compile(r"Access-Control-Allow-Origin['\"]?\s*[,=:]+\s*['\"]\*['\"]",re.I)),
]
SKIP_SUFFIXES=(".png",".jpg",".jpeg",".webp",".gif",".ico",".pdf",".lock",".svg")
SKIP_NAMES={"gradlew","gradlew.bat"}
def api_request(path,method="GET",payload=None):
 req=urllib.request.Request(API+path,data=None if payload is None else json.dumps(payload).encode(),method=method,headers={"Accept":"application/vnd.github+json","Authorization":"Bearer "+TOKEN,"X-GitHub-Api-Version":"2022-11-28",**({"Content-Type":"application/json"} if payload is not None else {})})
 try:
  with urllib.request.urlopen(req,timeout=20) as response:
   raw=response.read()
   return json.loads(raw) if raw else {}
 except urllib.error.HTTPError as exc:
  detail=exc.read().decode("utf-8","replace")[:500]
  raise RuntimeError(f"GitHub API {method} {path} returned {exc.code}: {detail}") from exc
def added_lines(patch):
 new_line=0
 for line in patch.splitlines():
  if line.startswith("@@"):
   match=re.search(r"\+(\d+)",line)
   if match:new_line=int(match.group(1))
   continue
  if line.startswith(("+++","\\\\")):continue
  if line.startswith("+"):
   yield new_line,line[1:];new_line+=1
  elif line.startswith("-"):continue
  else:new_line+=1
def message_for(rule):
 return {
 "PRIVATE_KEY":"A private-key header appears in added source. Remove the credential and rotate it if real.",
 "SUPABASE_SERVICE_ROLE":"A Supabase service-role/secret credential pattern appears in the diff. Never ship privileged credentials in an app, dashboard, or ordinary source.",
 "DISABLE_TLS_VALIDATION":"TLS certificate/hostname validation appears disabled, which can permit man-in-the-middle attacks.",
 "PERMISSIVE_RLS_POLICY":"A permissive RLS policy may expose rows broadly. Verify the full policy and role grants.",
 "RAW_SQL_CONCAT":"Potential string-concatenated raw SQL detected. Prefer parameterized queries and inspect context.",
 "SECRET_IN_SOURCE":"A hard-coded credential-like value appears in source. Verify whether it is real and move real secrets to managed environment configuration.",
 "DISABLE_APP_DEBUG":"Debug mode appears enabled in configuration. Ensure it is not used in deployed environments.",
 "UNSAFE_PERMISSIVE_CORS":"Wildcard CORS configuration detected. Confirm it is intentional and safe for authenticated endpoints."
 }[rule]
def review_file(path,patch):
 findings=[]
 if path.endswith(SKIP_SUFFIXES) or path.rsplit("/",1)[-1] in SKIP_NAMES:return findings
 for line_no,text in added_lines(patch):
  stripped=text.strip()
  if not stripped or stripped.startswith(("//","#","*","/*","<!--")):continue
  for severity,rule,pattern in RULES:
   if pattern.search(text):findings.append(Finding(severity,rule,path,line_no,message_for(rule),stripped[:220]))
 return findings
def build_report(findings,file_count,skipped):
 order={"CRITICAL":0,"HIGH":1,"MEDIUM":2,"LOW":3}
 findings=sorted(findings,key=lambda f:(order.get(f.severity,9),f.path,f.line or 0,f.rule))
 counts={s:sum(f.severity==s for f in findings) for s in ("CRITICAL","HIGH","MEDIUM","LOW")}
 lines=["<!-- tacresq-automated-review -->","## TACResQ automated code review","",f"Reviewed **{file_count} changed files** at head {HEAD_SHA[:12]}. Skipped {skipped} file(s) without a patch.","",f"**Findings:** {counts['CRITICAL']} critical · {counts['HIGH']} high · {counts['MEDIUM']} medium · {counts['LOW']} low",""," > Deterministic first-pass review, not proof of correctness. It scans added diff lines and can miss context-dependent defects. Required CI and human review still apply.",""]
 if not findings:lines+=["No configured pattern-based findings were detected in added lines.",""]
 else:
  for f in findings:
   evidence=f.evidence.replace(chr(96),"'")
   lines += [f"### {f.severity}: {f.rule}",f"- **Location:** {f.path}:{f.line or '?'}",f"- **Finding:** {f.message}",f"- **Added-line evidence:** {evidence}",""]
 lines += ["### Limitations","- Does not execute pull-request code.","- Does not access production secrets or production data.","- Does not approve or merge pull requests.","- A clean report does not replace tests, threat modeling, or human review.",""]
 return "\n".join(lines),counts
def main():
 if not(REPO and TOKEN and PR_NUMBER):
  print("GITHUB_REPOSITORY, GITHUB_TOKEN and PR_NUMBER are required.",file=sys.stderr);return 2
 files=[];page=1
 while True:
  batch=api_request(f"/repos/{REPO}/pulls/{PR_NUMBER}/files?per_page=100&page={page}")
  if not batch:break
  files.extend(batch)
  if len(batch)<100:break
  page+=1
 findings=[];skipped=0
 for item in files:
  patch=item.get("patch")
  if not patch:skipped+=1;continue
  findings.extend(review_file(item.get("filename",""),patch))
 report,counts=build_report(findings,len(files),skipped)
 print(report)
 summary=os.environ.get("GITHUB_STEP_SUMMARY")
 if summary:
  with open(summary,"a",encoding="utf-8") as output:
   output.write(report+"\\n")
 marker="<!-- tacresq-automated-review -->"
 try:
  comments=api_request(f"/repos/{REPO}/issues/{PR_NUMBER}/comments?per_page=100")
  existing=next((c for c in comments if marker in c.get("body","")),None)
  if existing:api_request(f"/repos/{REPO}/issues/comments/{existing['id']}","PATCH",{"body":report})
  else:api_request(f"/repos/{REPO}/issues/{PR_NUMBER}/comments","POST",{"body":report})
 except RuntimeError as exc:
  # Keep the report available in the Actions summary even when repository token
  # policy blocks comments; only actual critical findings should fail this job.
  print(f"::warning::Could not publish reviewer comment; report is in the Actions summary. {exc}",file=sys.stderr)
 return 1 if counts["CRITICAL"] else 0
if __name__=="__main__":raise SystemExit(main())
