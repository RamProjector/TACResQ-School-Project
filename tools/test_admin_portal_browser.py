#!/usr/bin/env python3
"""Real-browser smoke tests for the TACResQ React Admin portal.

All Supabase requests are intercepted and answered with deterministic fixtures.
No production credentials, database writes, or live user data are used.
"""
from __future__ import annotations

import base64
import json
import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from playwright.sync_api import Browser, Page, Route, expect, sync_playwright

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "backend" / "web"
HOST = "127.0.0.1"
PORT = 4173
BASE_URL = f"http://{HOST}:{PORT}/admin/"
SUPABASE_URL = "https://tacresq-browser-test.invalid"
PUBLISHABLE_KEY = "sb_publishable_browser_test_only"


def fixture_session() -> dict[str, Any]:
    now = int(time.time())
    user_id = "00000000-0000-4000-8000-000000000001"
    header = base64.urlsafe_b64encode(b'{"alg":"HS256","typ":"JWT"}').rstrip(b"=").decode()
    claims = {
        "aud": "authenticated",
        "exp": now + 3600,
        "iat": now,
        "sub": user_id,
        "role": "authenticated",
        "aal": "aal1",
        "amr": [{"method": "password", "timestamp": now}],
    }
    payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b"=").decode()
    # Supabase's client decodes the JWT claims locally; no signature is trusted
    # or sent to a live Auth service in these intercepted browser tests.
    access_token = f"{header}.{payload}.dGVzdC1zaWduYXR1cmU"
    return {
        "access_token": access_token,
        "token_type": "bearer",
        "expires_in": 3600,
        "expires_at": now + 3600,
        "refresh_token": "browser-test-refresh-token",
        "user": {
            "id": user_id,
            "aud": "authenticated",
            "role": "authenticated",
            "email": "admin@example.test",
            "app_metadata": {"provider": "email", "providers": ["email"]},
            "user_metadata": {},
            "created_at": "2026-01-01T00:00:00.000Z",
        },
    }


def profile_for(role: str) -> dict[str, Any]:
    return {
        "id": 1001,
        "name": "Browser Test Account",
        "email": "admin@example.test",
        "phone": None,
        "role": role,
        "duty_status": None,
    }


def fulfill_json(route: Route, payload: Any, *, status: int = 200) -> None:
    route.fulfill(
        status=status,
        content_type="application/json",
        headers={"Access-Control-Allow-Origin": "http://127.0.0.1:4173"},
        body=json.dumps(payload),
    )


def mock_supabase(page: Page, *, login: str, role: str | None = None) -> None:
    """Intercept only the known Auth/RPC endpoints used by this flow."""
    def handle(route: Route) -> None:
        request = route.request
        url = request.url
        if request.method == "OPTIONS":
            route.fulfill(
                status=204,
                headers={
                    "Access-Control-Allow-Origin": "http://127.0.0.1:4173",
                    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
                    "Access-Control-Allow-Headers": "apikey,authorization,content-type,x-client-info,x-supabase-api-version,traceparent",
                },
            )
            return
        if "/auth/v1/token" in url and "grant_type=password" in url:
            if login == "invalid":
                fulfill_json(route, {
                    "code": "invalid_credentials",
                    "msg": "Invalid login credentials",
                    "message": "Invalid login credentials",
                }, status=400)
            else:
                fulfill_json(route, fixture_session())
            return

        if "/rest/v1/rpc/api_me" in url and role:
            fulfill_json(route, profile_for(role))
            return

        if "/auth/v1/aal" in url:
            fulfill_json(route, {
                "currentLevel": "aal1",
                "nextLevel": "aal2",
                "currentAuthenticationMethods": [],
            })
            return

        if "/auth/v1/factors" in url:
            factors = [{
                "id": "browser-test-totp-factor",
                "friendly_name": "Test authenticator",
                "factor_type": "totp",
                "status": "verified",
                "created_at": "2026-01-01T00:00:00.000Z",
            }]
            fulfill_json(route, {"all": factors, "totp": factors, "phone": []})
            return

        # Unanticipated calls should not be silently converted into fake success.
        route.continue_()

    page.route(f"{SUPABASE_URL}/**", handle)


def wait_for_server(process: subprocess.Popen[str]) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Vite exited early with code {process.returncode}")
        try:
            with socket.create_connection((HOST, PORT), timeout=1):
                return
        except OSError:
            time.sleep(0.5)
    raise TimeoutError("Vite did not become ready within 60 seconds")


def run_browser_tests(browser: Browser) -> list[str]:
    completed: list[str] = []

    # Invalid credentials must be reported in-page and must not open the portal.
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    mock_supabase(page, login="invalid")
    page.goto(BASE_URL, wait_until="domcontentloaded")
    expect(page.get_by_role("heading", name="Administrator sign in")).to_be_visible()
    expect(page.get_by_label("Email address")).to_be_visible()
    expect(page.get_by_label("Password")).to_be_visible()
    expect(page.locator(".admin-sidebar")).to_have_count(0)
    page.get_by_label("Email address").fill("admin@example.test")
    page.get_by_label("Password").fill("wrong-password")
    page.get_by_role("button", name="Open admin portal").click()
    expect(page.get_by_role("alert")).to_contain_text("Invalid login credentials")
    expect(page.get_by_role("heading", name="Administrator sign in")).to_be_visible()
    expect(page.locator(".admin-sidebar")).to_have_count(0)
    completed.append("invalid credentials show accessible error; login remains isolated")
    page.close()

    # A validly authenticated non-admin must still be denied the Admin workspace.
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    mock_supabase(page, login="valid", role="dispatcher")
    page.goto(BASE_URL, wait_until="domcontentloaded")
    page.get_by_label("Email address").fill("admin@example.test")
    page.get_by_label("Password").fill("fixture-password")
    page.get_by_role("button", name="Open admin portal").click()
    expect(page.get_by_role("heading", name="Dispatch is handled in the Android app.")).to_be_visible(timeout=10000)
    expect(page.locator(".admin-sidebar")).to_have_count(0)
    expect(page.get_by_role("heading", name="Administrator sign in")).to_have_count(0)
    page.get_by_role("button", name="Sign out").click()
    expect(page.get_by_role("heading", name="Administrator sign in")).to_be_visible(timeout=10000)
    completed.append("authenticated dispatcher is denied; sign-out returns to login")
    page.close()

    # Requester/responder accounts must not enter the Admin workspace either.
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    mock_supabase(page, login="valid", role="requester")
    page.goto(BASE_URL, wait_until="domcontentloaded")
    page.get_by_label("Email address").fill("admin@example.test")
    page.get_by_label("Password").fill("fixture-password")
    page.get_by_role("button", name="Open admin portal").click()
    expect(page.get_by_role("heading", name="Administrator access is required.")).to_be_visible(timeout=10000)
    expect(page.locator(".admin-sidebar")).to_have_count(0)
    page.close()
    completed.append("requester role cannot enter the Admin workspace")

    # Admin credentials alone must not bypass the verified TOTP gate.
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    failed_requests: list[str] = []
    page.on("requestfailed", lambda request: failed_requests.append(
        f"{request.method} {request.url}: {request.failure}"
    ))
    mock_supabase(page, login="valid", role="admin")
    page.goto(BASE_URL, wait_until="domcontentloaded")
    page.get_by_label("Email address").fill("admin@example.test")
    page.get_by_label("Password").fill("fixture-password")
    page.get_by_role("button", name="Open admin portal").click()
    try:
        expect(page.get_by_role("heading", name="Verify your identity")).to_be_visible(timeout=10000)
    except AssertionError as error:
        raise AssertionError(
            f"Admin MFA gate did not reach verification. Body: {page.locator('body').inner_text()} "
            f"Failed requests: {failed_requests}"
        ) from error
    expect(page.get_by_text("Enter the current six-digit code from Google Authenticator")).to_be_visible()
    expect(page.locator(".admin-sidebar")).to_have_count(0)
    completed.append("admin session remains behind MFA until TOTP verification")
    page.close()

    # Check both responsive layouts on the real login screen.
    for viewport, story_visible, label in [
        ({"width": 1440, "height": 900}, True, "desktop"),
        ({"width": 390, "height": 844}, False, "mobile"),
    ]:
        page = browser.new_page(viewport=viewport)
        page.goto(BASE_URL, wait_until="domcontentloaded")
        expect(page.get_by_role("heading", name="Administrator sign in")).to_be_visible()
        story = page.locator(".login-story")
        if story_visible:
            expect(story).to_be_visible()
        else:
            expect(story).to_be_hidden()
        overflow = page.evaluate("document.documentElement.scrollWidth > window.innerWidth")
        if overflow:
            raise AssertionError(f"{label} login layout has horizontal overflow at {viewport['width']}px")
        page.close()
        completed.append(f"{label} login layout fits viewport without horizontal overflow")

    return completed


def main() -> int:
    env = os.environ.copy()
    env["VITE_SUPABASE_URL"] = SUPABASE_URL
    env["VITE_SUPABASE_PUBLISHABLE_KEY"] = PUBLISHABLE_KEY
    env["VITE_BASE_PATH"] = "/admin/"
    process = subprocess.Popen(
        ["pnpm", "dev", "--host", HOST, "--port", str(PORT), "--strictPort"],
        cwd=WEB,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )
    try:
        wait_for_server(process)
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch()
            try:
                for result in run_browser_tests(browser):
                    print(f"PASS: {result}")
            finally:
                browser.close()
        print("Admin portal browser smoke tests passed (Supabase responses were mocked).")
        return 0
    finally:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
        if process.stdout:
            output = process.stdout.read()
            if output:
                print(output, file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
