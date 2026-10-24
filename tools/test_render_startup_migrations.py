#!/usr/bin/env python3
"""Contract checks for safe, serialized Laravel migrations at Render startup."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
dockerfile = (ROOT / "backend/Dockerfile").read_text()
entrypoint = (ROOT / "backend/docker/entrypoint.sh").read_text()
runner = (ROOT / "backend/docker/migrate-with-lock.php").read_text()
cache_migration = ROOT / "backend/src/database/migrations/2026_10_24_000006_create_cache_tables.php"

checks = {
    "Docker image packages the migration runner":
        "COPY docker/migrate-with-lock.php /usr/local/bin/tacresq-migrate-with-lock.php" in dockerfile,
    "entrypoint runs migrations before serving requests":
        entrypoint.index("php /usr/local/bin/tacresq-migrate-with-lock.php")
        < entrypoint.index('exec php artisan serve'),
    "entrypoint fails closed if migrations fail":
        "set -eu" in entrypoint and "php /usr/local/bin/tacresq-migrate-with-lock.php" in entrypoint,
    "migration runner boots Laravel and runs forced migrations":
        "Illuminate\\Contracts\\Console\\Kernel::class" in runner
        and "Illuminate\\Support\\Facades\\Artisan::call('migrate', ['--force' => true])" in runner,
    "PostgreSQL advisory lock serializes concurrent Render instances":
        "pg_advisory_lock" in runner and "pg_advisory_unlock" in runner
        and runner.index("pg_advisory_lock") < runner.index("Artisan::call")
        and runner.index("Artisan::call") < runner.index("pg_advisory_unlock"),
    "runner propagates migration failure as a non-zero exit":
        "exit($exitCode);" in runner and "$exitCode = 1;" in runner,
    "database cache tables required by bridge throttling exist as a migration":
        cache_migration.is_file()
        and "Schema::create('cache'" in cache_migration.read_text()
        and "Schema::create('cache_locks'" in cache_migration.read_text(),
    "runner does not introduce privileged Supabase credentials":
        "service_role" not in runner.lower() and "SUPABASE_SERVICE_ROLE_KEY" not in dockerfile,
}

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Render startup migration contract check(s) failed")
print(f"Render startup migration contract OK: {len(checks)} checks.")
