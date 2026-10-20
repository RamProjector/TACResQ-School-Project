#!/usr/bin/env python3
"""Capture stable prototype screens and compare them with checked-in baselines.

The report is intentionally human-readable and CI-friendly. A changed screen
fails the job and writes a diff image plus a Markdown summary for upload as a
GitHub Actions artifact. Map screens are excluded because third-party tiles are
not deterministic in CI.
"""
from __future__ import annotations

import argparse
import http.server
import socketserver
import threading
from pathlib import Path

from PIL import Image, ImageChops, ImageEnhance
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASELINE = ROOT / "tests/visual/baseline"
MAX_ACCEPTED_CHANGED_RATIO = 0.0005
SCREENS = (
    ("requester-home", None, "Requester home"),
    ("requester-requests", "requests", "Requester request history"),
    ("dispatcher-queue", "dispatcher", "Dispatcher queue"),
    ("responder-call", "responder", "Responder current call"),
)


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        pass


def serve() -> tuple[socketserver.TCPServer, str]:
    handler = lambda *args, **kwargs: QuietHandler(*args, directory=str(ROOT), **kwargs)
    server = socketserver.TCPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}/docs/wireframes/gallery.html"


def capture(page, path: Path) -> None:
    box = page.locator("#phone-screen").bounding_box()
    assert box, "phone preview is not visible"
    page.screenshot(path=str(path), clip=box, animations="disabled")


def compare(actual: Path, baseline: Path, diff: Path, threshold: int) -> tuple[bool, float]:
    current = Image.open(actual).convert("RGBA")
    expected = Image.open(baseline).convert("RGBA")
    if current.size != expected.size:
        return False, 1.0
    delta = ImageChops.difference(current, expected).convert("RGB")
    changed = sum(1 for pixel in delta.getdata() if max(pixel) > threshold)
    ratio = changed / (current.width * current.height)
    if ratio:
        ImageEnhance.Contrast(delta).enhance(4).save(diff)
    # Browser/Pillow upgrades can change a handful of anti-aliased pixels.
    # Keep the gate strict enough to catch layout/content changes while
    # accepting sub-0.05% raster noise.
    return ratio <= MAX_ACCEPTED_CHANGED_RATIO, ratio


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--actual", type=Path, default=Path("artifacts/visual/actual"))
    parser.add_argument("--report", type=Path, default=Path("artifacts/visual/report.md"))
    parser.add_argument("--update", action="store_true", help="Replace baselines instead of checking them")
    args = parser.parse_args()
    args.baseline.mkdir(parents=True, exist_ok=True)
    args.actual.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    server, url = serve()
    results: list[tuple[str, str, str, float]] = []
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(executable_path="/usr/bin/chromium", headless=True, args=["--no-sandbox"])
            page = browser.new_page(viewport={"width": 1440, "height": 1080}, device_scale_factor=1)
            page.goto(url, wait_until="networkidle")
            page.locator("#phone-screen").wait_for()
            for name, action, label in SCREENS:
                if action == "dispatcher":
                    page.locator('.role-choice[data-role="dispatcher"]').click()
                elif action == "responder":
                    page.locator('.role-choice[data-role="responder"]').click()
                elif action:
                    page.locator(f'.nav-bar button[data-page="{action}"]').click()
                actual = args.actual / f"{name}.png"
                baseline = args.baseline / f"{name}.png"
                diff = args.actual / f"{name}.diff.png"
                capture(page, actual)
                if args.update or not baseline.exists():
                    baseline.write_bytes(actual.read_bytes())
                    results.append((label, "baseline created", str(actual), 0.0))
                else:
                    ok, ratio = compare(actual, baseline, diff, threshold=8)
                    results.append((label, "pass" if ok else "changed", str(diff if not ok else actual), ratio))
            browser.close()
    finally:
        server.shutdown()
        server.server_close()
    lines = ["# TACResQ visual regression", "", "| Screen | Result | Changed pixels | Artifact |", "|---|---|---:|---|"]
    failed = False
    for label, result, artifact, ratio in results:
        failed |= result == "changed"
        lines.append(f"| {label} | {result} | {ratio:.3%} | `{artifact}` |")
    lines += ["", "Map screens are intentionally excluded because OpenStreetMap tiles are external and nondeterministic in CI.", ""]
    args.report.write_text("\n".join(lines), encoding="utf-8")
    print(args.report.read_text())
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
