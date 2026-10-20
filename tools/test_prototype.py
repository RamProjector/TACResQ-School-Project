#!/usr/bin/env python3
"""Exercise the interactive TACResQ prototype in a real headless browser.

This is intentionally dependency-light: CI installs Playwright and Chromium,
then this script starts the checked-in prototype through a local HTTP server.
"""
from __future__ import annotations

import argparse
import http.server
import socketserver
import threading
from pathlib import Path

from playwright.sync_api import Page, sync_playwright

ROOT = Path(__file__).resolve().parents[1]
GALLERY = "/docs/wireframes/gallery.html"


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        pass


def serve() -> tuple[socketserver.TCPServer, str]:
    handler = lambda *args, **kwargs: QuietHandler(*args, directory=str(ROOT), **kwargs)
    server = socketserver.TCPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}{GALLERY}"


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)
    print(f"PASS  {message}")


def page_name(page: Page) -> str:
    current = page.locator('.nav-bar button[aria-current="page"]').first
    return current.get_attribute("data-page") or "" if current.count() else ""


def go(page: Page, destination: str) -> None:
    if page.locator(".map-fullscreen").count():
        page.locator(".map-back").click()
    page.locator(f'.nav-bar button[data-page="{destination}"]').click()
    if destination == "map":
        page.wait_for_timeout(250)
        page.locator(".map-back").wait_for()
        check(page.locator(".map-fullscreen").count() == 1, "navigation opens map")
    else:
        check(page_name(page) == destination, f"navigation opens {destination}")


def run(url: str) -> None:
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            executable_path="/usr/bin/chromium", headless=True, args=["--no-sandbox"]
        )
        page = browser.new_page(viewport={"width": 1440, "height": 1080}, device_scale_factor=1)
        page.goto(url, wait_until="networkidle")
        page.locator("#phone-screen").wait_for()
        check(page.locator(".role-choice").count() == 3, "role selector renders requester, responder, and dispatcher")
        check(page_name(page) == "home", "requester starts on home")

        for destination in ("requests", "map", "privacy", "profile"):
            go(page, destination)
        page.locator('[data-page="home"]').click()

        # Requester: consent and SOS submission.
        page.locator('[data-action="start-sos"]').click()
        page.locator("#consent-check").check()
        page.locator('[data-action="accept-consent"]').click()
        check(page.locator('[data-action="confirm-sos"]').count() == 1, "SOS consent opens final review")
        page.locator('[data-action="confirm-sos"]').click()
        check(page.locator("text=Waiting for dispatch").count() > 0, "SOS creates a waiting incident")
        go(page, "requests")
        check(page.locator("text=Waiting for dispatch").count() > 0, "request history shows the new incident")

        # Dispatcher: queue, assignment confirmation, and assignment.
        page.locator('.role-choice[data-role="dispatcher"]').click()
        check(page_name(page) == "queue", "dispatcher role lands on queue")
        check(page.locator('[data-action="start-assign"]').count() == 1, "dispatcher sees assignment action")
        page.locator('[data-action="start-assign"]').first.click()
        page.locator('[data-action="confirm-assign"]').click()
        check(page.locator("text=Unit assigned").count() > 0, "dispatcher assignment changes incident status")
        go(page, "units")
        go(page, "activity")
        go(page, "map")

        # Responder: complete the status state machine and verify history.
        page.locator('.role-choice[data-role="responder"]').click()
        check(page_name(page) == "call", "responder role lands on current call")
        for expected in ("Unit en route", "Unit on scene", "Transporting", "Resolved"):
            page.locator('[data-action="advance-status"]').click()
            page.locator('[data-action="confirm-status"]').click()
            check(page.locator(f"text={expected}").count() > 0, f"responder advances to {expected}")
        go(page, "history")
        check(page.locator("text=Resolved").count() > 0, "responder history contains the resolved incident")
        browser.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", help="Use an already-running prototype URL")
    args = parser.parse_args()
    server = None
    try:
        if args.url:
            run(args.url)
        else:
            server, url = serve()
            run(url)
    finally:
        if server:
            server.shutdown()
            server.server_close()
    print("\nPROTOTYPE SMOKE PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
