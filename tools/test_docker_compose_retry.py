#!/usr/bin/env python3
"""Offline regression tests for the Docker Compose transient-failure retry helper."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "docker_compose_retry.sh"


class DockerComposeRetryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.counter_file = self.root / "calls.txt"
        self.fake_docker = self.bin_dir / "docker"
        self.fake_docker.write_text(
            """#!/usr/bin/env bash
set -eu
count=0
if [ -f "$FAKE_DOCKER_COUNTER" ]; then count="$(cat "$FAKE_DOCKER_COUNTER")"; fi
count=$((count + 1))
printf '%s' "$count" > "$FAKE_DOCKER_COUNTER"
case "$FAKE_DOCKER_MODE" in
  transient-once)
    if [ "$count" -eq 1 ]; then
      echo 'unexpected status from registry: 502 Bad Gateway' >&2
      exit 1
    fi
    echo 'Compose stack started'
    exit 0
    ;;
  permanent)
    echo 'Dockerfile syntax error: invalid instruction' >&2
    exit 17
    ;;
  always-transient)
    echo 'registry request failed: 503 Service Unavailable' >&2
    exit 1
    ;;
  *)
    echo "unknown fake mode" >&2
    exit 99
    ;;
esac
""",
            encoding="utf-8",
        )
        self.fake_docker.chmod(0o755)

    def run_script(self, mode: str, attempts: str = "3") -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{self.bin_dir}{os.pathsep}{env.get('PATH', '')}",
                "FAKE_DOCKER_MODE": mode,
                "FAKE_DOCKER_COUNTER": str(self.counter_file),
                "DOCKER_COMPOSE_MAX_ATTEMPTS": attempts,
                "DOCKER_COMPOSE_RETRY_DELAY_SECONDS": "0",
            }
        )
        return subprocess.run(
            ["bash", str(SCRIPT)],
            cwd=ROOT,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=15,
            check=False,
        )

    def calls(self) -> int:
        return int(self.counter_file.read_text(encoding="utf-8")) if self.counter_file.exists() else 0

    def test_retries_transient_502_and_then_succeeds(self) -> None:
        result = self.run_script("transient-once")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.calls(), 2)
        self.assertIn("502 Bad Gateway", result.stdout)
        self.assertIn("Compose stack started", result.stdout)
        self.assertIn("retrying", result.stderr)

    def test_does_not_retry_non_transient_failure(self) -> None:
        result = self.run_script("permanent")
        self.assertEqual(result.returncode, 17, result.stdout + result.stderr)
        self.assertEqual(self.calls(), 1)
        self.assertIn("no safe transient retry remains", result.stderr)

    def test_stops_after_configured_attempts(self) -> None:
        result = self.run_script("always-transient", attempts="3")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual(self.calls(), 3)

    def test_rejects_invalid_retry_count_before_calling_docker(self) -> None:
        result = self.run_script("permanent", attempts="0")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.calls(), 0)
        self.assertIn("positive integer", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
