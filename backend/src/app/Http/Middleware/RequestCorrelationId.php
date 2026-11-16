<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Str;
use Symfony\Component\HttpFoundation\Response;

final class RequestCorrelationId
{
    public function handle(Request $request, Closure $next): Response
    {
        $provided = trim((string) $request->header('X-Request-ID', ''));
        $correlationId = Str::isUuid($provided) ? $provided : (string) Str::uuid();
        $request->attributes->set('correlation_id', $correlationId);

        return $next($request)->header('X-Request-ID', $correlationId);
    }
}
