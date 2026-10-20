#!/usr/bin/env python3
"""Bounded smoke/load test for public TACResQ endpoints.

This is intentionally safe for CI and staging: it uses only GET requests,
keeps concurrency and request count explicit, and refuses to target localhost
unless the caller opts in with --allow-localhost.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import statistics
import time
import urllib.error
import urllib.request
from dataclasses import dataclass


@dataclass(frozen=True)
class Result:
    endpoint: str
    status: int | None
    elapsed_ms: float
    error: str | None = None


def request(base_url: str, endpoint: str, timeout: float) -> Result:
    started = time.perf_counter()
    try:
        req = urllib.request.Request(
            base_url.rstrip("/") + endpoint,
            headers={"Accept": "text/html,application/json"},
        )
        with urllib.request.urlopen(req, timeout=timeout) as response:
            response.read(4096)
            return Result(endpoint, response.status, (time.perf_counter() - started) * 1000)
    except urllib.error.HTTPError as exc:
        return Result(endpoint, exc.code, (time.perf_counter() - started) * 1000, str(exc))
    except Exception as exc:  # noqa: BLE001 - report each failed request, do not abort the run
        return Result(endpoint, None, (time.perf_counter() - started) * 1000, str(exc))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--requests", type=int, default=120, help="Total requests; default: 120")
    parser.add_argument("--concurrency", type=int, default=8, help="Worker count; default: 8")
    parser.add_argument("--timeout", type=float, default=5.0)
    parser.add_argument("--allow-localhost", action="store_true")
    args = parser.parse_args()
    if args.requests < 1 or args.concurrency < 1 or args.requests > 5000:
        parser.error("--requests must be 1..5000 and --concurrency must be positive")
    if ("localhost" in args.base_url or "127.0.0.1" in args.base_url) and not args.allow_localhost:
        parser.error("refusing localhost; pass --allow-localhost for an explicitly local run")

    endpoints = ["/up", "/dispatch/", "/dispatch/config.json"]
    work = [endpoints[index % len(endpoints)] for index in range(args.requests)]
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(lambda endpoint: request(args.base_url, endpoint, args.timeout), work))
    elapsed = time.perf_counter() - started

    successful = [item for item in results if item.status is not None and 200 <= item.status < 400]
    failed = [item for item in results if item not in successful]
    latencies = [item.elapsed_ms for item in results]
    rate = len(results) / elapsed if elapsed else 0
    print(f"stress target={args.base_url} requests={len(results)} concurrency={args.concurrency}")
    print(f"pass={len(successful)} fail={len(failed)} throughput={rate:.1f} req/s")
    print(
        "latency_ms="
        f"p50={statistics.median(latencies):.1f} "
        f"max={max(latencies):.1f}"
    )
    for item in failed[:10]:
        print(f"FAIL {item.endpoint} status={item.status} error={item.error}")
    if failed:
        raise SystemExit(f"stress test failed: {len(failed)} of {len(results)} requests failed")
    print("STRESS TEST PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
