#!/usr/bin/env python3
"""Real-browser smoke tests for the TACResQ React Admin portal.

All Supabase requests are intercepted and answered with deterministic fixtures.
No production credentials, database writes, or live user data are used.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
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
    return {
        "access_token": "browser-test-access-token",
        "token_type": "bearer",
        "expires_in": 3600,
        "expires_at": now + 3600,
        "refresh_token": "browser-test-refresh-token",
        "user": {
            "id": "00000000-0000-4000-8000-000000000001",
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
        headers={"Access-Control-Allow-Origin": "*"},
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
                    "Access-Control-Allow-Origin": "*",
                    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
                    "Access-Control-Allow-Headers": "apikey,authorization,content-type,x-client-info,traceparent",
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
            with urllib.request.urlopen(BASE_URL, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            time.sleep(0.5)
    raise TimeoutError("Vite did not become ready within 60 seconds")


def run_browser_tests(browser: Browser) -> list[str]:
    completed: list[str] = []

    # Invalid credentials must be reported in-page and must not open the portal.
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    mock_supabase(page, login="invalid")
    page.goto(BASE_URL, wait_until="networkidle")
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
    page.goto(BASE_URL, wait_until="networkidle")
    page.get_by_label("Email address").fill("admin@example.test")
    page.get_by_label("Password").fill("fixture-password")
    page.get_by_role("button", name="Open admin portal").click()
    expect(page.get_by_role("heading", name="Dispatch is handled in the Android app.")).to_be_visible(timeout=10000)
    expect(page.locator(".admin-sidebar")).to_have_count(0)
    expect(page.get_by_role("heading", name="Administrator sign in")).to_have_count(0)
    page.get_by_role("button", name="Sign out").click()
    expect(page.get_by_role("heading", name="Administrator sign in")).to_be_visible(timeout=10000)
    completed.append("authenticated non-admin is denied; sign-out returns to login")
    page.close()

    # Admin credentials alone must not bypass the verified TOTP gate.
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    mock_supabase(page, login="valid", role="admin")
    page.goto(BASE_URL, wait_until="networkidle")
    page.get_by_label("Email address").fill("admin@example.test")
    page.get_by_label("Password").fill("fixture-password")
    page.get_by_role("button", name="Open admin portal").click()
    expect(page.get_by_role("heading", name="Verify your identity")).to_be_visible(timeout=10000)
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
        page.goto(BASE_URL, wait_until="networkidle")
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
        ["pnpm", "dev", "--", "--host", HOST, "--port", str(PORT), "--strictPort"],
        cwd=WEB,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
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
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        if process.stdout:
            output = process.stdout.read()
            if process.returncode not in (0, -15, 143) and output:
                print(output, file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
