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
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo
from typing import Any

from playwright.sync_api import Browser, Page, Route, expect, sync_playwright

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "backend" / "web"
HOST = "127.0.0.1"
PORT = 4173
BASE_URL = f"http://{HOST}:{PORT}/admin/"
SUPABASE_URL = "https://tacresq-browser-test.invalid"
PUBLISHABLE_KEY = "sb_publishable_browser_test_only"


def fixture_session(aal: str = "aal1") -> dict[str, Any]:
    now = int(time.time())
    user_id = "00000000-0000-4000-8000-000000000001"
    header = base64.urlsafe_b64encode(b'{"alg":"HS256","typ":"JWT"}').rstrip(b"=").decode()
    claims = {
        "aud": "authenticated",
        "exp": now + 3600,
        "iat": now,
        "sub": user_id,
        "role": "authenticated",
        "aal": aal,
        "amr": [{"method": "password", "timestamp": now}] + ([{"method": "totp", "timestamp": now}] if aal == "aal2" else []),
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
            "factors": [{
                "id": "browser-test-totp-factor",
                "friendly_name": "Test authenticator",
                "factor_type": "totp",
                "status": "verified",
                "created_at": "2026-01-01T00:00:00.000Z",
                "updated_at": "2026-01-01T00:00:00.000Z",
            }],
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


def mock_supabase(page: Page, *, login: str, role: str | None = None, aal: str = "aal1") -> None:
    """Intercept only the known Auth/RPC endpoints used by this flow."""
    managed_user: dict[str, Any] = {
        "id": 1002,
        "name": "Test Requester",
        "email": "requester@example.test",
        "role": "requester",
        "duty_status": None,
        "account_status": "active",
        "created_at": "2026-10-01T00:00:00.000Z",
    }

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
                fulfill_json(route, fixture_session(aal))
            return

        if "/auth/v1/user" in url:
            fulfill_json(route, fixture_session(aal)["user"])
            return

        if "/rest/v1/rpc/api_me" in url and role:
            fulfill_json(route, profile_for(role))
            return

        if "/rest/v1/rpc/api_admin_list_users_page" in url:
            fulfill_json(route, {"users": [dict(managed_user)], "total_count": 1})
            return

        if "/rest/v1/rpc/api_admin_set_user_role" in url:
            params = request.post_data_json or {}
            requested_role = str(params.get("p_role", ""))
            old_role = str(managed_user["role"])
            if params.get("p_user_id") != managed_user["id"] or requested_role not in {"requester", "responder", "driver", "dispatcher"}:
                fulfill_json(route, {"code": "22023", "message": "Invalid test role change"}, status=400)
                return
            managed_user["role"] = requested_role
            managed_user["duty_status"] = "off_duty" if requested_role in {"responder", "driver"} else None
            fulfill_json(route, {
                "id": managed_user["id"],
                "name": managed_user["name"],
                "email": managed_user["email"],
                "old_role": old_role,
                "role": requested_role,
                "duty_status": managed_user["duty_status"],
            })
            return

        if "/rest/v1/rpc/api_admin_list_account_lifecycle_events" in url:
            fulfill_json(route, {"events": [], "total_count": 0})
            return

        if "/rest/v1/rpc/api_admin_list_auth_sessions_page" in url:
            fulfill_json(route, {
                "items": [],
                "has_more": False,
                "next_cursor": None,
                "source": "supabase_auth_sessions",
                "source_status": "available",
                "observed_at": datetime.now().astimezone().isoformat(),
                "status_semantics": "A record being present does not establish that the session is currently valid.",
            })
            return

        if "/rest/v1/rpc/api_admin_list_investigation_events" in url:
            fulfill_json(route, {
                "contract_version": "session_trace_v1",
                "events": [],
                "total_count": 0,
            })
            return

        if "/rest/v1/rpc/api_admin_analytics_summary" in url:
            params = request.post_data_json or {}
            period_end = str(params.get("p_to", ""))
            period_start = str(params.get("p_from", ""))
            report_day = datetime.fromisoformat(
                period_end.replace("Z", "+00:00")
            ).astimezone(ZoneInfo("Asia/Manila")).date().isoformat()
            fulfill_json(route, {
                "period_start": period_start,
                "period_end": period_end,
                "reporting_timezone": "Asia/Manila",
                "total_requests": 1,
                "pending": 0,
                "active": 0,
                "resolved": 1,
                "cancelled": 0,
                "average_assignment_minutes": 5,
                "median_assignment_minutes": 4,
                "assigned_request_count": 1,
                "daily_requests": [{"day": report_day, "requests": 1}],
            })
            return

        if "/rest/v1/rpc/api_dependency_health" in url:
            fulfill_json(route, "tacresq_database_ok")
            return

        if "/auth/v1/health" in url:
            route.fulfill(
                status=200,
                headers={"Access-Control-Allow-Origin": "http://127.0.0.1:4173"},
                body="ok",
            )
            return

        if "/auth/v1/aal" in url:
            fulfill_json(route, {
                "currentLevel": "aal1",
                "nextLevel": "aal2",
                "currentAuthenticationMethods": [],
            })
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
    requests_in_flight: dict[str, str] = {}
    page.on("request", lambda request: requests_in_flight.__setitem__(request.url, request.method))
    page.on("requestfinished", lambda request: requests_in_flight.pop(request.url, None))
    page.on("requestfailed", lambda request: (
        failed_requests.append(f"{request.method} {request.url}: {request.failure}"),
        requests_in_flight.pop(request.url, None),
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
            f"Failed requests: {failed_requests} In-flight requests: {requests_in_flight}"
        ) from error
    expect(page.get_by_text("Enter the current six-digit code from Google Authenticator")).to_be_visible()
    expect(page.locator(".admin-sidebar")).to_have_count(0)
    completed.append("admin session remains behind MFA until TOTP verification")
    page.close()

    # A session already verified at AAL2 must enter the actual Admin console,
    # load protected dashboard records, and display read-only service health.
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    mock_supabase(page, login="valid", role="admin", aal="aal2")
    page.route(
        f"http://{HOST}:{PORT}/up",
        lambda route: route.fulfill(status=200, body="ok"),
    )
    page.goto(BASE_URL, wait_until="domcontentloaded")
    page.get_by_label("Email address").fill("admin@example.test")
    page.get_by_label("Password").fill("fixture-password")
    page.get_by_role("button", name="Open admin portal").click()
    expect(page.get_by_role("heading", name="Overview")).to_be_visible(timeout=15000)
    expect(page.get_by_role("navigation", name="Admin sections")).to_be_visible()
    expect(page.get_by_role("heading", name="Dependency reachability")).to_be_visible()
    expect(page.get_by_text("All three dependencies responded within the probe threshold")).to_be_visible(timeout=10000)
    expect(page.get_by_text("No events are recorded in the integrated investigation sources today.")).to_be_visible()
    expect(page.get_by_role("navigation", name="Admin sections").get_by_role("button", name="User management")).to_be_visible()
    completed.append("verified AAL2 administrator reaches the live Admin console with mocked read-only dashboard data")

    # Exercise the real user directory and role-change dialog without writing
    # a role change. The reason and changed-role requirements must gate submit.
    page.get_by_role("navigation", name="Admin sections").get_by_role("button", name="User management").click()
    expect(page.get_by_role("heading", name="User management").first).to_be_visible()
    expect(page.get_by_role("table", name="Administrator account directory")).to_be_visible()
    expect(page.get_by_text("Test Requester")).to_be_visible()
    expect(page.get_by_text("requester@example.test")).to_be_visible()
    page.get_by_role("button", name="View details for Test Requester").click()
    expect(page.get_by_role("heading", name="Authentication sessions")).to_be_visible()
    page.get_by_role("button", name="View sessions").click()
    expect(page.get_by_text("No session records were returned for this account by the selected source.")).to_be_visible(timeout=10000)
    page.get_by_role("button", name="Change role for Test Requester").click()
    dialog = page.get_by_role("dialog", name="Change account role")
    expect(dialog).to_be_visible()
    confirm = dialog.get_by_role("button", name="Confirm role change")
    expect(confirm).to_be_disabled()
    dialog.get_by_label("New role").select_option("responder")
    expect(confirm).to_be_disabled()
    dialog.locator("#admin-change-reason").fill("Operational reassignment approved")
    expect(confirm).to_be_enabled()
    confirm.click()
    expect(dialog).to_have_count(0, timeout=10000)
    expect(
        page.get_by_role("row").filter(has_text="Test Requester").get_by_text("Responder")
    ).to_be_visible(timeout=10000)
    expect(page.get_by_text("Test Requester's role changed from Requester to Responder. The action was recorded in the audit log.")).to_be_visible()
    completed.append("user directory, reason validation, role-change RPC confirmation, and refreshed role state work with an isolated mock")

    # The same verified Admin session can open the unified read-only audit feed.
    page.get_by_role("navigation", name="Admin sections").get_by_role("button", name="Activity log").click()
    expect(page.get_by_role("heading", name="Audit history")).to_be_visible()
    expect(page.get_by_text("No matching activity")).to_be_visible(timeout=10000)
    completed.append("activity log loads the protected investigation feed and renders its truthful empty state")

    # Aggregate analytics and CSV export use validated reporting data only.
    page.get_by_role("navigation", name="Admin sections").get_by_role("button", name="Analytics & reports").click()
    expect(page.get_by_role("heading", name="Request performance")).to_be_visible()
    expect(page.locator(".admin-analytics-metrics article strong")).to_have_text("1", timeout=10000)
    expect(page.get_by_role("heading", name="Request status distribution")).to_be_visible()
    expect(page.get_by_role("heading", name="Time to first assignment")).to_be_visible()
    expect(page.get_by_role("heading", name="Daily requests")).to_be_visible()
    with page.expect_download() as download_info:
        page.get_by_role("button", name="Export incident performance report as CSV").click()
    download = download_info.value
    if not download.suggested_filename.endswith(".csv"):
        raise AssertionError(f"Analytics export did not produce a CSV: {download.suggested_filename}")
    expect(page.locator(".admin-analytics-export-notice")).to_contain_text("Export prepared:")
    completed.append("analytics page renders validated aggregate metrics and exports a CSV")
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
