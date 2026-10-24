#!/bin/sh
set -eu

: "${APP_KEY:?Set APP_KEY in Render environment (generate with php artisan key:generate --show)}"
export PORT="${PORT:-10000}"

php artisan config:clear
php artisan config:cache

# Apply pending schema changes before accepting requests. The migration runner
# serializes overlapping Render instances with a PostgreSQL advisory lock and
# exits non-zero on failure so an un-migrated instance never serves traffic.
php /usr/local/bin/tacresq-migrate-with-lock.php

exec php artisan serve --host=0.0.0.0 --port="$PORT"
