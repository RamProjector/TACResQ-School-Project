FROM composer:2 AS composer-bin
FROM php:8.3-cli

RUN apt-get update && apt-get install -y --no-install-recommends \
    git unzip libpq-dev libzip-dev libonig-dev libxml2-dev \
    && docker-php-ext-install pdo_pgsql mbstring bcmath zip \
    && rm -rf /var/lib/apt/lists/*
COPY --from=composer-bin /usr/bin/composer /usr/bin/composer
WORKDIR /var/www

# Create a conventional Laravel 11 application, then overlay this repository's API files.
RUN composer create-project laravel/laravel:^11.0 . --no-interaction
RUN composer require laravel/sanctum:^4.0 --no-interaction
RUN rm -f database/migrations/*.php
COPY src/ ./
COPY docker/entrypoint.sh /usr/local/bin/tacresq-entrypoint
RUN chmod +x /usr/local/bin/tacresq-entrypoint \
    && php artisan optimize:clear

ENV APP_ENV=production APP_DEBUG=false LOG_CHANNEL=stderr
EXPOSE 10000
CMD ["/usr/local/bin/tacresq-entrypoint"]
