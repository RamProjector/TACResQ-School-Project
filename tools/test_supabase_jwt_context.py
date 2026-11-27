#!/usr/bin/env python3
"""Disposable local Supabase Auth -> PostgREST JWT-context integration proof.

This test is restricted to the ephemeral local Supabase stack. It creates two
synthetic Auth users, proves caller JWT claims survive PostgREST, exercises
negative/spoofing/concurrency cases, and removes its probe and users.
"""
from __future__ import annotations

import concurrent.futures
import json
import os
import secrets
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse
import uuid
from pathlib import Path


PROBE = "public.__tacresq_jwt_context_probe"
PROBE_SQL = """
CREATE OR REPLACE FUNCTION public.__tacresq_jwt_context_probe(
  p_claimed_sub uuid DEFAULT NULL,
  p_claimed_session_id uuid DEFAULT NULL
)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
  SELECT jsonb_build_object(
    'actual_uid', auth.uid(),
    'actual_session_id', auth.jwt() ->> 'session_id',
    'claimed_sub', p_claimed_sub,
    'claimed_session_id', p_claimed_session_id
  );
$function$;
REVOKE ALL ON FUNCTION public.__tacresq_jwt_context_probe(uuid, uuid)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.__tacresq_jwt_context_probe(uuid, uuid)
  TO authenticated;
NOTIFY pgrst, 'reload schema';
"""
DROP_SQL = "DROP FUNCTION IF EXISTS public.__tacresq_jwt_context_probe(uuid, uuid); NOTIFY pgrst, 'reload schema';"


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        parsed = shlex.split(value, posix=True)
        values[key.strip()] = parsed[0] if parsed else ""
    required = ("API_URL", "ANON_KEY", "SERVICE_ROLE_KEY", "JWT_SECRET", "DB_URL")
    missing = [key for key in required if not values.get(key)]
    if missing:
        raise RuntimeError("Local Supabase status output is missing: " + ", ".join(missing))
    api = values["API_URL"].rstrip("/")
    if not api.startswith(("http://127.0.0.1:", "http://localhost:")):
        raise RuntimeError("Refusing to test against a non-loopback Supabase API URL")
    db = urlparse(values["DB_URL"])
    if db.scheme not in ("postgres", "postgresql") or db.username != "postgres" or db.hostname not in ("127.0.0.1", "localhost"):
        raise RuntimeError("Refusing to test against a non-loopback database URL")
    return values


def request_json(url: str, *, method: str = "GET", headers: dict[str, str] | None = None,
                 payload: dict | None = None) -> tuple[int, object]:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = None
        return error.code, parsed


def psql(db_url: str, sql: str, *, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["psql", db_url, "-X", "-v", "ON_ERROR_STOP=1", "-q", "-t", "-A", "-c", sql],
        check=check, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env={**os.environ, "PGOPTIONS": "-c client_min_messages=warning"},
    )


def b64url(data: bytes) -> str:
    import base64
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def expired_signed_jwt(secret: str, valid_token: str) -> str:
    import base64
    import hashlib
    import hmac

    segments = valid_token.split(".")
    if len(segments) != 3:
        raise AssertionError("Cannot derive expiry test from malformed Auth token")
    claims = json.loads(base64.urlsafe_b64decode(segments[1] + "=" * (-len(segments[1]) % 4)))
    now = int(time.time())
    claims["iat"] = now - 7200
    claims["exp"] = now - 3600
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(json.dumps(claims, separators=(",", ":")).encode())
    unsigned = f"{header}.{payload}"
    signature = hmac.new(secret.encode(), unsigned.encode(), hashlib.sha256).digest()
    return f"{unsigned}.{b64url(signature)}"

def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: test_supabase_jwt_context.py <supabase-status-env-file>", file=sys.stderr)
        return 2
    cfg = load_env(Path(sys.argv[1]))
    api, anon, service = cfg["API_URL"].rstrip("/"), cfg["ANON_KEY"], cfg["SERVICE_ROLE_KEY"]
    auth_headers = {"apikey": anon, "Content-Type": "application/json"}
    admin_headers = {"apikey": service, "Authorization": f"Bearer {service}", "Content-Type": "application/json"}
    users: list[dict[str, str]] = []
    probe_created = False
    try:
        psql(cfg["DB_URL"], PROBE_SQL)
        probe_created = True
        time.sleep(1)  # allow PostgREST to consume the schema-reload notification

        for label in ("alpha", "bravo"):
            email = f"tacresq-jwt-{label}-{uuid.uuid4().hex}@example.invalid"
            password = secrets.token_urlsafe(24) + "Aa1!"
            status, created = request_json(
                f"{api}/auth/v1/admin/users", method="POST", headers=admin_headers,
                payload={"email": email, "password": password, "email_confirm": True,
                         "user_metadata": {"name": f"Local JWT Probe {label}", "phone": "+639000000000"}},
            )
            if status not in (200, 201) or not isinstance(created, dict) or not created.get("id"):
                raise AssertionError(f"Local Auth Admin user creation failed (HTTP {status})")
            user_id = str(created["id"])
            users.append({"id": user_id, "email": email, "password": password})
            status, session = request_json(
                f"{api}/auth/v1/token?grant_type=password", method="POST",
                headers=auth_headers, payload={"email": email, "password": password},
            )
            if status != 200 or not isinstance(session, dict) or not session.get("access_token"):
                raise AssertionError(f"Local Auth password sign-in failed (HTTP {status})")
            token = str(session["access_token"])
            segments = token.split(".")
            if len(segments) != 3:
                raise AssertionError("Auth returned a malformed access token")
            import base64
            claims = json.loads(base64.urlsafe_b64decode(segments[1] + "=" * (-len(segments[1]) % 4)))
            if claims.get("sub") != user_id or not claims.get("session_id"):
                raise AssertionError("Access token is missing the expected sub/session_id claims")
            users[-1].update({"token": token, "session_id": str(claims["session_id"])})
            session_id = str(uuid.UUID(str(claims["session_id"])))
            user_id = str(uuid.UUID(user_id))
            session_sql = (
                "SELECT EXISTS (SELECT 1 FROM auth.sessions WHERE id = '"
                + session_id + "'::uuid AND user_id = '" + user_id + "'::uuid);"
            )
            if psql(cfg["DB_URL"], session_sql).stdout.strip() != "t":
                raise AssertionError("Issued JWT session_id has no matching live auth.sessions row")
            print(f"PASS: local Auth issued a session JWT for synthetic user {label}")

        psql(cfg["DB_URL"], PROBE_SQL)
        time.sleep(1)
        def probe(user: dict[str, str], claimed_user: str | None = None,
                  claimed_session: str | None = None) -> dict:
            status, body = request_json(
                f"{api}/rest/v1/rpc/__tacresq_jwt_context_probe", method="POST",
                headers={**auth_headers, "Authorization": f"Bearer {user['token']}"},
                payload={"p_claimed_sub": claimed_user, "p_claimed_session_id": claimed_session},
            )
            if status != 200 or not isinstance(body, dict):
                raise AssertionError(f"Authenticated PostgREST probe failed (HTTP {status})")
            return body

        for user in users:
            result = probe(user)
            if result.get("actual_uid") != user["id"] or result.get("actual_session_id") != user["session_id"]:
                raise AssertionError("PostgREST auth.uid()/session_id did not match the caller JWT")
        print("PASS: each caller JWT preserved its own auth.uid() and session_id through PostgREST")

        spoofed = probe(users[0], users[1]["id"], users[1]["session_id"])
        if spoofed.get("actual_uid") != users[0]["id"] or spoofed.get("actual_session_id") != users[0]["session_id"]:
            raise AssertionError("Caller-supplied identity fields changed the verified JWT context")
        if spoofed.get("claimed_sub") != users[1]["id"] or spoofed.get("claimed_session_id") != users[1]["session_id"]:
            raise AssertionError("Spoofing fixture did not reach the probe as expected")
        print("PASS: forged body identity/session fields did not override JWT-derived identity")

        def concurrent_check(index: int) -> bool:
            user = users[index % 2]
            result = probe(user)
            return result.get("actual_uid") == user["id"] and result.get("actual_session_id") == user["session_id"]
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            outcomes = list(pool.map(concurrent_check, range(24)))
        if not all(outcomes):
            raise AssertionError("Concurrent requests crossed caller JWT identity context")
        print("PASS: 24 concurrent requests retained isolated caller identities")

        status, _ = request_json(
            f"{api}/rest/v1/rpc/__tacresq_jwt_context_probe", method="POST",
            headers=auth_headers, payload={"p_claimed_sub": None, "p_claimed_session_id": None},
        )
        if status not in (401, 403, 404):
            raise AssertionError(f"API-key-only anonymous request unexpectedly reached authenticated probe (HTTP {status})")
        print("PASS: anonymous API-key-only request was denied")

        status, _ = request_json(
            f"{api}/rest/v1/rpc/__tacresq_jwt_context_probe", method="POST",
            headers={**auth_headers, "Authorization": f"Bearer {anon}"},
            payload={"p_claimed_sub": None, "p_claimed_session_id": None},
        )
        if status not in (401, 403, 404):
            raise AssertionError(f"Anon-role JWT unexpectedly reached authenticated probe (HTTP {status})")
        print("PASS: anon-role JWT was rejected by the authenticated-only probe")

        status, _ = request_json(
            f"{api}/rest/v1/rpc/__tacresq_jwt_context_probe", method="POST",
            headers={**auth_headers, "Authorization": "Bearer not-a-jwt"},
            payload={"p_claimed_sub": None, "p_claimed_session_id": None},
        )
        if status != 401:
            raise AssertionError(f"Malformed JWT was not rejected with HTTP 401 (got {status})")
        print("PASS: malformed bearer JWT was rejected")

        expired = expired_signed_jwt(cfg["JWT_SECRET"], users[0]["token"])
        status, _ = request_json(
            f"{api}/rest/v1/rpc/__tacresq_jwt_context_probe", method="POST",
            headers={**auth_headers, "Authorization": f"Bearer {expired}"},
            payload={"p_claimed_sub": None, "p_claimed_session_id": None},
        )
        if status != 401:
            raise AssertionError(f"Expired signed JWT was not rejected with HTTP 401 (got {status})")
        print("PASS: expired locally signed JWT was rejected")

        print("RESULT: local Auth -> PostgREST JWT context proof passed")
        return 0
    finally:
        if probe_created:
            psql(cfg["DB_URL"], DROP_SQL, check=False)
        for user in reversed(users):
            request_json(f"{api}/auth/v1/admin/users/{user['id']}", method="DELETE",
                         headers=admin_headers)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        # Do not print request headers, passwords, JWTs, or secret values.
        print(f"FAIL: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
