#!/usr/bin/env python3
"""Capture the TACResQ interactive wireframe screens and key states.

Requires Python Playwright, Chromium, and an internet connection for Leaflet and visible OpenStreetMap tiles. By default, starts a temporary localhost HTTP server so the tile provider receives a normal page Referer.
"""
from __future__ import annotations

import argparse
import atexit
from functools import partial
from html import escape
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
GALLERY = ROOT / "docs/wireframes/gallery.html"
OUT = ROOT / "docs/wireframes/screenshots"

SHOTS: list[tuple[str, str]] = []


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        pass


def start_preview_server() -> tuple[ThreadingHTTPServer, str]:
    handler = partial(QuietHandler, directory=str(ROOT))
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    Thread(target=server.serve_forever, daemon=True).start()
    atexit.register(server.server_close)
    atexit.register(server.shutdown)
    return server, f"http://127.0.0.1:{server.server_port}/docs/wireframes/gallery.html"


def capture(page, filename: str, description: str) -> None:
    page.evaluate("""() => {
      document.querySelector('#toast-root').replaceChildren();
      const content = document.querySelector('.phone-scroll');
      if (content) content.scrollTop = 0;
      const device = document.querySelector('#phone-screen');
      if (device) device.scrollTop = 0;
    }""")
    box = page.locator("#phone-screen").bounding_box()
    page.screenshot(path=str(OUT / filename), clip=box, animations="disabled")
    SHOTS.append((filename, description))


def role(page, name: str) -> None:
    page.locator(f'.role-choice[data-role="{name}"]').click()


def screen(page, name: str) -> None:
    if page.locator(".map-fullscreen").count():
        page.locator(".map-back").click()
    page.locator(f'.nav-bar button[data-page="{name}"]').click()
    if name == "map":
        page.wait_for_function("""() => {
          const status = document.querySelector('#map-provider-status');
          return status && (status.classList.contains('is-hidden') || status.classList.contains('map-provider-status--error'));
        }""", timeout=12000)


def close_modal(page) -> None:
    page.locator('.modal [data-action="close-modal"]').last.click()


def main() -> None:
    global OUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", help="Optional gallery URL; default starts a temporary localhost HTTP server.")
    parser.add_argument("--output", type=Path, default=OUT, help="Screenshot output folder.")
    args = parser.parse_args()
    output = args.output.resolve()
    OUT = output
    output.mkdir(parents=True, exist_ok=True)
    if args.url:
        preview_url = args.url
    else:
        _, preview_url = start_preview_server()

    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path="/usr/bin/chromium", headless=True, args=["--no-sandbox"])
        page = browser.new_page(viewport={"width": 1440, "height": 1080}, device_scale_factor=2)
        page.goto(preview_url, wait_until="networkidle")
        page.locator("#phone-screen").wait_for()

        # Requester: empty-state destinations and sign-in preview.
        capture(page, "01-requester-home.png", "Requester home: emergency type, optional details, location readiness, and review action.")
        screen(page, "requests")
        capture(page, "02-requester-requests-empty.png", "Requester request history before any report exists.")
        screen(page, "map")
        capture(page, "03-requester-map-empty.png", "Requester full-screen OpenStreetMap view with no active incident; centered on the Tacloban sample area.")
        screen(page, "privacy")
        capture(page, "04-requester-safety.png", "Requester privacy and safety information.")
        screen(page, "profile")
        capture(page, "05-requester-profile.png", "Requester profile and location-consent entry points.")
        page.locator('[data-action="sign-out"]').click()
        capture(page, "06-requester-sign-out-confirmation.png", "Sign-out confirmation in the requester profile.")
        page.locator('[data-action="confirm-sign-out"]').click()
        capture(page, "07-sign-in.png", "Role-aware sign-in preview; no real credentials are sent or stored.")
        page.locator('[data-action="demo-sign-in"]').click()

        # Responder: unassigned state and role destinations.
        role(page, "responder")
        capture(page, "08-responder-current-call-unassigned.png", "Responder duty state before an assignment.")
        screen(page, "history")
        capture(page, "09-responder-history-empty.png", "Responder history before any assignment.")
        screen(page, "map")
        capture(page, "10-responder-map-unassigned.png", "Responder full-screen OpenStreetMap view with no assigned incident or fabricated unit position.")
        screen(page, "privacy")
        capture(page, "11-responder-safety.png", "Responder privacy and safety information.")
        screen(page, "profile")
        capture(page, "12-responder-profile.png", "Responder profile and duty information.")

        # Dispatcher: empty queue and unit visibility.
        role(page, "dispatcher")
        capture(page, "13-dispatcher-queue-empty.png", "Dispatcher response desk before a requester report.")
        screen(page, "units")
        capture(page, "14-dispatcher-field-units-empty.png", "Dispatcher field-unit list with duty and GPS availability separated.")
        screen(page, "map")
        capture(page, "15-dispatcher-map-empty.png", "Dispatcher full-screen OpenStreetMap view with no incidents and no live unit positions.")
        screen(page, "activity")
        capture(page, "16-dispatcher-activity-empty.png", "Dispatcher activity log before any incident events.")
        screen(page, "profile")
        capture(page, "17-dispatcher-profile.png", "Dispatcher profile.")

        # Requester: consent, review, and one shared pending incident.
        role(page, "requester")
        page.locator('[data-action="start-sos"]').click()
        capture(page, "18-requester-location-consent.png", "Location-sharing disclosure with an unchecked acknowledgement.")
        page.locator("#consent-check").check()
        page.locator('[data-action="accept-consent"]').click()
        capture(page, "19-requester-sos-final-review.png", "Distinct final SOS review and confirmation.")
        page.locator('[data-action="confirm-sos"]').click()
        page.wait_for_timeout(80)
        screen(page, "requests")
        capture(page, "20-requester-active-request.png", "Requester incident detail with pending status, timeline, and location accuracy.")
        screen(page, "home")
        capture(page, "21-requester-home-active.png", "Requester home when a single active request exists.")
        screen(page, "map")
        capture(page, "22-requester-map-active.png", "Requester full-screen OpenStreetMap view with a sample incident pin and no live tracking.")
        screen(page, "home")
        page.locator('[data-action="cancel-request"]').click()
        capture(page, "23-requester-cancel-confirmation.png", "Cancellation confirmation with a reason selector.")
        close_modal(page)

        # Dispatcher: pending incident and explicit assignment.
        role(page, "dispatcher")
        capture(page, "24-dispatcher-queue-pending.png", "Pending incident in the dispatcher queue with available-unit assignment action.")
        page.locator('[data-action="start-assign"]').first.click()
        capture(page, "25-dispatcher-assignment-confirmation.png", "Dispatcher assignment confirmation dialog.")
        page.locator('[data-action="confirm-assign"]').click()
        capture(page, "26-dispatcher-queue-assigned.png", "Queue after explicit assignment; demo state only.")
        screen(page, "units")
        capture(page, "27-dispatcher-field-units-assigned.png", "Unit list showing assignment state distinct from GPS position.")
        screen(page, "map")
        capture(page, "28-dispatcher-map-assigned.png", "Dispatcher full-screen OpenStreetMap view with an incident sample point and no live unit coordinates.")
        screen(page, "activity")
        capture(page, "29-dispatcher-activity-assigned.png", "Dispatcher activity log with incident creation and assignment events.")

        # Responder: assigned call, navigation, confirmation, offline queue, resolution/history.
        role(page, "responder")
        capture(page, "30-responder-current-call-assigned.png", "Responder active call with initial requester fix and safe-response choices.")
        screen(page, "map")
        capture(page, "31-responder-map-assigned.png", "Responder full-screen OpenStreetMap view with the assigned sample incident only; no live route or ETA.")
        screen(page, "call")
        page.locator('[data-action="advance-status"]').click()
        capture(page, "32-responder-en-route-confirmation.png", "Explicit confirmation before the responder marks en route.")
        page.locator('[data-action="confirm-status"]').click()
        page.locator('#network-mode').select_option("offline")
        page.locator('[data-action="advance-status"]').click()
        page.locator('[data-action="confirm-status"]').click()
        capture(page, "33-responder-offline-status-queued.png", "Offline responder update marked unsynced; dispatch is not told it arrived.")
        page.locator('#network-mode').select_option("online")
        # After online restoration, the prototype synchronizes the queued demo update.
        for expected in ("Unit transporting", "Mark resolved"):
            page.locator('[data-action="advance-status"]').click()
            page.locator('[data-action="confirm-status"]').click()
        screen(page, "history")
        capture(page, "34-responder-history-completed.png", "Responder history with the completed incident and status events.")
        role(page, "dispatcher")
        capture(page, "35-dispatcher-queue-resolved.png", "Dispatcher queue after the sample incident has been resolved.")
        screen(page, "activity")
        capture(page, "36-dispatcher-activity-completed.png", "Dispatcher event timeline after response completion.")
        role(page, "requester")
        screen(page, "requests")
        capture(page, "37-requester-requests-resolved.png", "Requester request history after the sample response is resolved.")

        # GPS permission and accuracy/error UI states.
        page.locator('#reset-prototype').click()
        page.locator('#gps-mode').select_option("permission")
        page.locator('[data-action="start-sos"]').click()
        capture(page, "38-requester-location-permission.png", "Permission request explanation and non-coercive not-now option.")
        page.locator('[data-action="deny-location"]').click()
        page.locator('#gps-mode').select_option("stale")
        page.locator('[data-action="start-sos"]').click()
        capture(page, "39-requester-stale-gps-blocked.png", "Stale fix is blocked and a fresh-location retry is offered.")
        close_modal(page)
        page.locator('#gps-mode').select_option("unavailable")
        page.locator('[data-action="start-sos"]').click()
        capture(page, "40-requester-gps-unavailable.png", "GPS-unavailable state with retry; no guessed location is sent.")
        close_modal(page)
        page.locator('#gps-mode').select_option("poor")
        page.locator('[data-action="start-sos"]').click()
        page.locator('#consent-check').check()
        page.locator('[data-action="accept-consent"]').click()
        capture(page, "41-requester-low-accuracy-review.png", "Low-accuracy GPS warning and explicit final SOS review.")
        close_modal(page)

        # Offline request: explicitly remains unsent until retry.
        page.locator('#reset-prototype').click()
        page.locator('#network-mode').select_option("offline")
        page.locator('[data-action="start-sos"]').click()
        page.locator('#consent-check').check()
        page.locator('[data-action="accept-consent"]').click()
        page.locator('[data-action="confirm-sos"]').click()
        capture(page, "42-requester-offline-not-sent.png", "Offline SOS is explicitly shown as not sent and awaiting user retry.")

        browser.close()

    index = [
        "# TACResQ UI screenshots",
        "",
        "Clean mobile-viewport screenshots captured from the interactive prototype at 2× scale. Filenames group each role and the key flow states. These depict simulated/fictitious data only; none of the screens contact live dispatch or emergency services.",
        "",
        "Browse every screen visually in the [clickable screenshot gallery](gallery.html). Select any thumbnail to open the full-size image.",
        "",
        "**Design color guidance:** the current gallery is a high-fidelity functional prototype rather than a low-fidelity monochrome wireframe. For early IA/layout reviews, use grayscale with color reserved for meaning (not decoration); preserve semantic red for SOS/danger, and status colors only with text labels. Do not remove accessible contrast or rely on color alone.",
        "",
        "| Screenshot | Screen / state |",
        "|---|---|",
    ]
    for filename, description in SHOTS:
        index.append(f"| [`{filename}`]({filename}) | {description} |")
    index.extend(["", "## Re-capture", "", "From the repository root, run `python3 tools/capture_wireframe_screenshots.py`. The script uses `/usr/bin/chromium`, Playwright, and a temporary localhost HTTP server; internet access is needed to load Leaflet and visible OpenStreetMap tiles. It does not prefetch map areas or create offline tiles.", ""])
    (output / "README.md").write_text("\n".join(index), encoding="utf-8")
    cards = "\n".join(
        f'<a class="shot" href="{escape(filename, quote=True)}"><img src="{escape(filename, quote=True)}" alt="{escape(description, quote=True)}" loading="lazy"><span class="shot-name">{escape(filename[:-4].replace("-", " ").title())}</span><span class="shot-description">{escape(description)}</span></a>'
        for filename, description in SHOTS
    )
    gallery = f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#142a31"><title>TACResQ · Screen gallery</title>
<style>
:root{{font-family:Inter,"DM Sans",system-ui,-apple-system,"Segoe UI",sans-serif;color:#1c3035;background:#f0f1eb;font-synthesis:none;text-rendering:optimizeLegibility}}*{{box-sizing:border-box}}body{{margin:0}}header{{background:#142a31;color:#fffefa;padding:30px clamp(18px,5vw,64px) 26px}}header p{{margin:8px 0 0;color:#bbced3;font-size:14px;line-height:1.5}}header a{{color:#b6eee1}}.eyebrow{{font-size:10px;font-weight:800;letter-spacing:.16em;text-transform:uppercase;color:#89c6b8}}h1{{margin:8px 0 0;font-size:clamp(24px,4vw,38px);letter-spacing:-.04em}}main{{max-width:1500px;margin:0 auto;padding:24px clamp(14px,3vw,36px) 48px}}.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:16px}}.shot{{display:flex;flex-direction:column;gap:8px;min-width:0;padding:9px;border:1px solid #dce6e9;border-radius:16px;background:#fff;color:inherit;text-decoration:none;box-shadow:0 5px 16px rgba(14,42,52,.06);transition:transform .16s ease,box-shadow .16s ease}}.shot:hover{{transform:translateY(-2px);box-shadow:0 10px 26px rgba(14,42,52,.12)}}.shot:focus-visible{{outline:3px solid #e49b2e;outline-offset:3px}}.shot img{{display:block;width:100%;height:auto;border-radius:10px;background:#e7edef}}.shot-name{{font-size:11px;font-weight:800;line-height:1.3}}.shot-description{{font-size:10px;line-height:1.4;color:#6c7b80}}footer{{padding:18px clamp(18px,5vw,64px);border-top:1px solid #dce6e9;color:#687980;font-size:11px;line-height:1.6}}@media(max-width:500px){{.grid{{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}}.shot{{padding:7px}}}}
</style></head><body><header><div class="eyebrow">TACResQ · DESIGN PREVIEW</div><h1>All mobile screens</h1><p>{len(SHOTS)} captured UI states · requester, responder/driver, and dispatcher · fictional prototype data only. <a href="README.md">Read the screen descriptions</a>.</p></header><main><section class="grid" aria-label="All TACResQ wireframe screenshots">{cards}</section></main><footer>Every image is a captured prototype screen. The basemap uses OpenStreetMap; demo markers are fictional. The prototype does not access device GPS, dispatch, or emergency services.</footer></body></html>'''
    (output / "gallery.html").write_text(gallery, encoding="utf-8")
    print(f"Captured {len(SHOTS)} mobile screenshots to {output}")
    for filename, _ in SHOTS:
        print(filename)


if __name__ == "__main__":
    main()
