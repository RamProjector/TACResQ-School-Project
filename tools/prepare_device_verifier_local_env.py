#!/usr/bin/env python3
"""Provision the dedicated verifier login only inside the disposable local Supabase stack."""
from __future__ import annotations

import os
import secrets
import shlex
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote, urlsplit, urlunsplit


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        parsed = shlex.split(value, posix=True)
        values[key.strip()] = parsed[0] if parsed else ""
    return values


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: prepare_device_verifier_local_env.py <supabase-status-env> <edge-env-file>", file=sys.stderr)
        return 2
    source = read_env(Path(sys.argv[1]))
    api_url = source.get("API_URL", "").rstrip("/")
    anon_key = source.get("ANON_KEY", "")
    db_url = source.get("DB_URL", "")
    if not api_url.startswith(("http://127.0.0.1:", "http://localhost:")):
        raise RuntimeError("Refusing to provision a verifier login outside a loopback Supabase API")
    parsed = urlsplit(db_url)
    if parsed.scheme not in ("postgres", "postgresql") or parsed.username != "postgres" or parsed.hostname not in ("127.0.0.1", "localhost"):
        raise RuntimeError("Refusing to provision a verifier login outside the local PostgreSQL instance")
    database_name = parsed.path.lstrip("/") or "postgres"
    if not database_name.replace("_", "").isalnum():
        raise RuntimeError("Unexpected local database name")
    if not anon_key:
        raise RuntimeError("Local Supabase publishable/anon key is missing")

    password = secrets.token_urlsafe(32)
    sql = (
        "ALTER ROLE tacresq_device_verifier LOGIN PASSWORD '"
        + password
        + "'; GRANT CONNECT ON DATABASE "
        + database_name
        + " TO tacresq_device_verifier;"
    )
    result = subprocess.run(
        ["psql", db_url, "-X", "-v", "ON_ERROR_STOP=1", "-q", "-c", sql],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env={**os.environ, "PGOPTIONS": "-c client_min_messages=warning"},
    )
    if result.returncode != 0:
        raise RuntimeError("Could not provision the isolated local device-verifier database login")

    username = quote("tacresq_device_verifier", safe="")
    encoded_password = quote(password, safe="")
    authority = f"{username}:{encoded_password}@{parsed.hostname}:{parsed.port or 5432}"
    verifier_url = urlunsplit((parsed.scheme, authority, parsed.path, parsed.query, parsed.fragment))
    target = Path(sys.argv[2])
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        "SUPABASE_URL=" + api_url + "\n"
        + "SUPABASE_ANON_KEY=" + anon_key + "\n"
        + "TACRESQ_DEVICE_VERIFIER_DATABASE_URL=" + verifier_url + "\n",
        encoding="utf-8",
    )
    target.chmod(0o600)
    print("Provisioned the dedicated local verifier login; credentials were not printed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
