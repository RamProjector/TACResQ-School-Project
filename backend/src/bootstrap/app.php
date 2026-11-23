<?php

use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use App\Http\Middleware\RequestCorrelationId;
use App\Http\Middleware\ApiSecurityHeaders;
use App\Http\Middleware\EnsureLocalAccountIsActive;
use App\Http\Middleware\EnsureLocalAdminReauthenticated;

return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(web: __DIR__.'/../routes/web.php', api: __DIR__.'/../routes/api.php', commands: __DIR__.'/../routes/console.php', health: '/up')
    ->withMiddleware(function (Middleware $middleware): void {
        $middleware->append(RequestCorrelationId::class);
        $middleware->append(ApiSecurityHeaders::class);
        $middleware->alias([
            'account.active' => EnsureLocalAccountIsActive::class,
            'local.admin.reauthenticated' => EnsureLocalAdminReauthenticated::class,
        ]);
        // Only the signed internal Cron hook bypasses browser CSRF; HMAC auth is mandatory.
        $middleware->validateCsrfTokens(except: ['internal/fcm/dispatch']);
    })
    ->withExceptions(function (Exceptions $exceptions): void {})
    ->create();
