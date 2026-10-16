#!/bin/sh
set -eu

: "${APP_KEY:?Set APP_KEY in Render environment (generate with php artisan key:generate --show)}"
: "${DATABASE_URL:?Attach a Render PostgreSQL database so DATABASE_URL is available}"
export DB_URL="$DATABASE_URL"
export PORT="${PORT:-10000}"

php artisan config:clear
php artisan migrate --force
exec php artisan serve --host=0.0.0.0 --port="$PORT"
