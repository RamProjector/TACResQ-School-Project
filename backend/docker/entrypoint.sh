#!/bin/sh
set -eu

: "${APP_KEY:?Set APP_KEY in Render environment (generate with php artisan key:generate --show)}"
export PORT="${PORT:-10000}"

php artisan config:clear
exec php artisan serve --host=0.0.0.0 --port="$PORT"
