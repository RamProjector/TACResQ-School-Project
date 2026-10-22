#!/usr/bin/env python3
"""Capture and sanity-check the interactive prototype at several viewport sizes.

This complements the fixed-size pixel baseline test: it checks responsive
geometry and saves reviewable screenshots for each viewport. These are browser
wireframe screenshots, not native Android emulator screenshots.
"""
from __future__ import annotations

import http.server
import socketserver
import threading
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
GALLERY = "/docs/wireframes/gallery.html"
VIEWPORTS = (
    ("mobile-320x568", 320, 568),
    ("mobile-360x800", 360, 800),
    ("mobile-393x873", 393, 873),
    ("mobile-432x960", 432, 960),
    ("tablet-768x1024", 768, 1024),
)
SCREENS = (
    ("requester-home", None),
    ("requester-history", "requests"),
    ("requester-map", "map"),
)


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        pass


def serve() -> tuple[socketserver.TCPServer, str]:
    handler = lambda *args, **kwargs: QuietHandler(*args, directory=str(ROOT), **kwargs)
    server = socketserver.TCPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}{GALLERY}"


def assert_layout(page, width: int, label: str) -> None:
    metrics = page.evaluate("""() => {
      const screen = document.querySelector('#phone-screen');
      const nav = [...document.querySelectorAll('#phone-screen .nav-bar button')];
      const rect = el => {
        const r = el.getBoundingClientRect();
        return {left:r.left, right:r.right, top:r.top, bottom:r.bottom,
                width:r.width, height:r.height};
      };
      return {
        viewportWidth: window.innerWidth,
        documentWidth: document.documentElement.scrollWidth,
        screen: screen ? rect(screen) : null,
        nav: nav.map(el => ({
          text: (el.getAttribute("aria-label") || el.innerText).trim(),
          rect: rect(el),
          clientWidth: el.clientWidth,
          scrollWidth: el.scrollWidth,
          visible: !!(el.offsetWidth && el.offsetHeight)
        }))
      };
    }""")
    assert metrics["documentWidth"] <= width, (
        f"{label}: page has horizontal overflow "
        f"({metrics['documentWidth']}px document vs {width}px viewport)"
    )
    screen = metrics["screen"]
    assert screen and screen["width"] > 0, f"{label}: phone screen is not visible"
    assert screen["left"] >= -1 and screen["right"] <= width + 1, (
        f"{label}: phone screen escapes viewport bounds: {screen}"
    )
    if not metrics["nav"] and page.locator(".map-fullscreen").count():
        map_box = page.locator(".map-fullscreen").bounding_box()
        assert map_box and map_box["width"] > 0 and map_box["height"] > 0, (
            f"{label}: full-screen map is not visible"
        )
        assert map_box["x"] >= screen["left"] - 1 and (
            map_box["x"] + map_box["width"] <= screen["right"] + 1
        ) and map_box["y"] >= screen["top"] - 1 and (
            map_box["y"] + map_box["height"] <= screen["bottom"] + 1
        ), f"{label}: full-screen map escapes the phone screen: {map_box}"
        return
    assert len(metrics["nav"]) == 5, (
        f"{label}: expected five bottom navigation destinations, found {len(metrics['nav'])}"
    )
    for item in metrics["nav"]:
        assert item["visible"], f"{label}: nav destination is hidden: {item['text']!r}"
        assert item["text"], f"{label}: nav destination has no accessible label"
        rect = item["rect"]
        assert rect["left"] >= screen["left"] - 1 and rect["right"] <= screen["right"] + 1, (
            f"{label}: nav destination escapes the phone screen: {item['text']!r} {rect}"
        )
        assert item["scrollWidth"] <= item["clientWidth"] + 2, (
            f"{label}: nav label/content is clipped: {item['text']!r}"
        )

    # The central map destination intentionally floats above the nav bar, but
    # horizontal overlap between adjacent destination buttons is not intended.
    nav_rects = [item["rect"] for item in metrics["nav"]]
    ordered = sorted(nav_rects, key=lambda rect: rect["left"])
    for previous, current in zip(ordered, ordered[1:]):
        assert previous["right"] <= current["left"] + 1, (
            f"{label}: bottom navigation buttons overlap horizontally: "
            f"{previous} and {current}"
        )


def main() -> int:
    output = ROOT / "artifacts/visual/multi-viewport"
    output.mkdir(parents=True, exist_ok=True)
    server, url = serve()
    failures: list[str] = []
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(
                executable_path="/usr/bin/chromium", headless=True, args=["--no-sandbox"]
            )
            for viewport_name, width, height in VIEWPORTS:
                page = browser.new_page(
                    viewport={"width": width, "height": height}, device_scale_factor=1
                )
                # External tiles/fonts are not part of this responsive layout check.
                page.route("https://tile.openstreetmap.org/**", lambda route: route.abort())
                page.goto(url, wait_until="networkidle")
                page.locator("#phone-screen").wait_for()
                page.locator("#phone-screen .nav-bar button").first.wait_for()
                # Remove the wireframe studio chrome so the app content itself
                # receives the exact requested CSS viewport dimensions.
                page.add_style_tag(content="""
                  html, body { width: 100%; min-width: 0; margin: 0; }
                  .studio-rail, .workspace-header, .device-caption,
                  .device-camera, .device-footnote, .inspector { display: none !important; }
                  .studio-shell, .workspace, .workspace-content, .device-column,
                  .device-frame {
                    display: block !important; width: 100% !important;
                    max-width: none !important; min-width: 0 !important;
                    margin: 0 !important; padding: 0 !important;
                    border-radius: 0 !important; box-shadow: none !important;
                  }
                  .device-screen {
                    width: 100vw !important; height: 100vh !important;
                    min-height: 0 !important; max-height: none !important;
                    border-radius: 0 !important;
                  }
                """)
                assert_layout(page, width, viewport_name)
                for screen_name, destination in SCREENS:
                    if destination:
                        if page.locator(".map-fullscreen").count():
                            page.locator(".map-back").click()
                        page.locator(f'.nav-bar button[data-page="{destination}"]').click()
                        if destination == "map":
                            page.locator(".map-fullscreen").wait_for()
                        else:
                            page.wait_for_function(
                                "(destination) => document.querySelector('#phone-screen .nav-bar button[aria-current=page]')?.dataset.page === destination",
                                arg=destination,
                            )
                    assert_layout(page, width, f"{viewport_name}/{screen_name}")
                    page.screenshot(
                        path=str(output / f"{viewport_name}-{screen_name}.png"),
                        full_page=True,
                        animations="disabled",
                    )
                print(f"PASS {viewport_name} ({width}x{height}): app viewport geometry and 3 screenshots")
                page.close()
            browser.close()
    except Exception as exc:
        failures.append(str(exc))
    finally:
        server.shutdown()
        server.server_close()

    if failures:
        print("MULTI-VIEWPORT CHECK FAILED")
        for failure in failures:
            print(f"- {failure}")
        return 1
    print(f"Saved responsive screenshots to {output}")
    print("MULTI-VIEWPORT CHECK PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
