<?php

declare(strict_types=1);

// Use PHP's built-in server directly so Render's runtime environment is inherited
// by the request process. Laravel's "artisan serve" starts a child server and can
// mask runtime variables needed by /runtime-config.json in this container setup.
$publicDirectory = realpath(__DIR__ . '/public');
$requestPath = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
$requestPath = rawurldecode(is_string($requestPath) ? $requestPath : '/');

if ($publicDirectory !== false && $requestPath !== '/' && !str_contains($requestPath, "\0")) {
    $candidate = realpath($publicDirectory . DIRECTORY_SEPARATOR . ltrim($requestPath, '/'));

    // Let the built-in server serve only real files/directories inside public/.
    // Everything else, including Laravel routes, goes through the front controller.
    if (
        $candidate !== false
        && str_starts_with($candidate, $publicDirectory . DIRECTORY_SEPARATOR)
        && (is_file($candidate) || is_dir($candidate))
    ) {
        return false;
    }
}

require __DIR__ . '/public/index.php';
