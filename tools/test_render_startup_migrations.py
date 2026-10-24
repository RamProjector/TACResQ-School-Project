#!/usr/bin/env python3
"""Contract checks that the Render Supabase bridge starts without a SQL database."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
dockerfile = (ROOT / "backend/Dockerfile").read_text()

checks = {
    "Docker image starts Laravel directly on Render's exposed port":
        'CMD ["php", "artisan", "serve", "--host=0.0.0.0", "--port=10000"]' in dockerfile
        and "EXPOSE 10000" in dockerfile,
    "Docker image does not install a migration runner or entrypoint":
        "migrate-with-lock.php" not in dockerfile and "tacresq-entrypoint" not in dockerfile,
    "runtime cache does not require a SQL database":
        "CACHE_STORE=file" in dockerfile,
    "image does not start application migrations":
        "artisan migrate" not in dockerfile.lower(),
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Render bridge startup contract check(s) failed")
print(f"Render bridge startup contract OK: {len(checks)} checks.")
