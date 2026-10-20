#!/usr/bin/env python3
"""Regression test for tools/stress_test.py using a local disposable HTTP server."""
from __future__ import annotations

import http.server
import subprocess
import sys
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
        if self.path in {"/up", "/dispatch/", "/dispatch/config.json"}:
            body = b"{}"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, *_args: object) -> None:
        pass


def main() -> int:
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        url = f"http://127.0.0.1:{server.server_port}"
        result = subprocess.run(
            [
                sys.executable,
                str(ROOT / "tools/stress_test.py"),
                "--base-url",
                url,
                "--allow-localhost",
                "--requests",
                "12",
                "--concurrency",
                "3",
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode:
            print(result.stdout)
            print(result.stderr, file=sys.stderr)
            raise SystemExit(result.returncode)
        assert "STRESS TEST PASS" in result.stdout
        assert "pass=12 fail=0" in result.stdout
        print("STRESS UTILITY TEST PASS")
        return 0
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    raise SystemExit(main())
