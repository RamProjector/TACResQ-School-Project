<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\RateLimiter;
use Symfony\Component\HttpFoundation\Response;

final class EnsureLocalAdminReauthenticated
{
    public function handle(Request $request, Closure $next): Response
    {
        $user = $request->user();

        if ($user === null || $user->role !== 'admin') {
            return response()->json(['message' => 'Administrator access is required.'], 403)
                ->header('Cache-Control', 'no-store, private');
        }

        $key = 'local-admin-reauth:'.hash('sha256', (string) $user->getAuthIdentifier().'|'.(string) $request->ip());
        if (RateLimiter::tooManyAttempts($key, 5)) {
            return response()->json(['message' => 'Too many re-authentication attempts. Please retry later.'], 429)
                ->header('Retry-After', (string) RateLimiter::availableIn($key))
                ->header('Cache-Control', 'no-store, private');
        }

        $credentials = $request->validate([
            'current_password' => ['required', 'string', 'max:72'],
        ]);

        if (!Hash::check($credentials['current_password'], $user->password)) {
            RateLimiter::hit($key, 900);
            return response()->json(['message' => 'Administrator re-authentication failed.'], 403)
                ->header('Cache-Control', 'no-store, private');
        }

        RateLimiter::clear($key);

        return $next($request);
    }
}
