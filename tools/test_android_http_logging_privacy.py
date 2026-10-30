#!/usr/bin/env python3
"""Prevent Android HTTP logging from exposing emergency or authentication data.

OkHttp's BASIC level records request lines and response status only. HEADERS and
BODY can expose bearer tokens, phone numbers, precise locations, incident
descriptions, and chat messages. These source checks complement runtime review.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
api_client_path = ROOT / "app/src/main/java/com/tacresq/app/data/remote/ApiClient.kt"
supabase_client_path = ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt"

api_client = api_client_path.read_text()
supabase_client = supabase_client_path.read_text()

checks = {
    "legacy API client explicitly limits HTTP logs to BASIC": bool(
        re.search(r"HttpLoggingInterceptor\(\)\s*\.apply\s*\{\s*level\s*=\s*HttpLoggingInterceptor\.Level\.BASIC", api_client)
    ),
    "Supabase API client explicitly limits HTTP logs to BASIC": bool(
        re.search(r"HttpLoggingInterceptor\(\)\s*\.apply\s*\{\s*level\s*=\s*HttpLoggingInterceptor\.Level\.BASIC", supabase_client)
    ),
    "direct Supabase client attaches the safe logger": ".addInterceptor(logging)" in supabase_client,
    "optional Render bridge also attaches the safe logger": supabase_client.count(".addInterceptor(logging)") >= 2,
    "legacy API client does not enable header or body logging": not re.search(
        r"HttpLoggingInterceptor\.Level\.(?:HEADERS|BODY)", api_client
    ),
    "Supabase and bridge clients do not enable header or body logging": not re.search(
        r"HttpLoggingInterceptor\.Level\.(?:HEADERS|BODY)", supabase_client
    ),
}

for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")

failed = [name for name, passed in checks.items() if not passed]
if failed:
    raise SystemExit(f"{len(failed)} HTTP logging privacy check(s) failed")
print(f"Android HTTP logging privacy contract OK: {len(checks)} checks.")
