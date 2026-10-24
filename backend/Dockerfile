FROM node:26-alpine AS dashboard-build
WORKDIR /dashboard
RUN npm install --global pnpm@10.18.0
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
ENV VITE_BASE_PATH=/dispatch/
RUN pnpm build

FROM composer:2 AS composer-bin
FROM php:8.5-cli

RUN apt-get update && apt-get install -y --no-install-recommends \
    git unzip libpq-dev libzip-dev libonig-dev libxml2-dev \
    && docker-php-ext-install pdo_pgsql mbstring bcmath zip \
    && rm -rf /var/lib/apt/lists/*
COPY --from=composer-bin /usr/bin/composer /usr/bin/composer
WORKDIR /app

# Create a conventional Laravel 13 application, then overlay this repository's API files.
RUN composer create-project laravel/laravel:^13.0 . --no-interaction
RUN composer require laravel/sanctum:^4.0 --no-interaction
RUN rm -f database/migrations/*.php
COPY src/ ./
COPY --from=dashboard-build /dashboard/dist/ ./public/dispatch/
COPY router.php ./router.php
RUN php artisan optimize:clear

# Supabase owns application auth/data. The bridge must not need a Render SQL database.
# File cache lets the rate limiter work without a database-backed cache store.
# These are browser-safe Supabase client settings, not service-role credentials.
ENV APP_ENV=production APP_DEBUG=false LOG_CHANNEL=stderr SESSION_DRIVER=file CACHE_STORE=file \
    SUPABASE_URL=https://kttguamhznoflzagfutr.supabase.co \
    SUPABASE_PUBLISHABLE_KEY=sb_publishable_1-_oEKiNRWO0tvEOr5Gr3g_USY07s2g
EXPOSE 10000
CMD ["sh", "-ec", "exec php -S 0.0.0.0:${PORT:-10000} -t public router.php"]
