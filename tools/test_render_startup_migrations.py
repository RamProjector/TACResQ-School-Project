#!/usr/bin/env python3
"""Contract checks that the Render Supabase bridge starts without a SQL database."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
dockerfile = (ROOT / "backend/Dockerfile").read_text()
router = (ROOT / "backend/router.php").read_text()

checks = {
    "Docker image runs the PHP server directly on Render's exposed port":
        'exec php -S 0.0.0.0:${PORT:-10000} -t public router.php' in dockerfile
        and "EXPOSE 10000" in dockerfile
        and "php artisan serve" not in dockerfile,
    "Docker image includes the environment-preserving router":
        "COPY router.php ./router.php" in dockerfile
        and "require __DIR__ . '/public/index.php';" in router,
    "router serves only existing public assets directly":
        "realpath($publicDirectory . DIRECTORY_SEPARATOR . ltrim($requestPath, '/'))" in router
        and "str_starts_with($candidate, $publicDirectory . DIRECTORY_SEPARATOR)" in router
        and "return false;" in router,
    "runtime cache does not require a SQL database":
        "CACHE_STORE=file" in dockerfile,
    "Docker image does not install a migration runner or entrypoint":
        "migrate-with-lock.php" not in dockerfile and "tacresq-entrypoint" not in dockerfile,
    "image does not start application migrations":
        "artisan migrate" not in dockerfile.lower(),
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Render bridge startup contract check(s) failed")
print(f"Render bridge startup contract OK: {len(checks)} checks.")
