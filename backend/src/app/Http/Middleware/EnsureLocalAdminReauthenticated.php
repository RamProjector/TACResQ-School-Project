<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\RateLimiter;
use Illuminate\Support\Facades\DB;
use App\Models\User;
use App\Services\LocalAdminTotpService;
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

        try {
            $credentials = $request->validate([
                'current_password' => ['required', 'string', 'max:72'],
                'totp_code' => ['required', 'string', 'regex:/^\\d{6}$/'],
            ]);
        } catch (ValidationException $exception) {
            // Malformed step-up attempts must not bypass the same throttle or
            // return cacheable validation details from a privileged boundary.
            RateLimiter::hit($key, 900);

            if (RateLimiter::tooManyAttempts($key, 5)) {
                return response()->json(['message' => 'Too many re-authentication attempts. Please retry later.'], 429)
                    ->header('Retry-After', (string) RateLimiter::availableIn($key))
                    ->header('Cache-Control', 'no-store, private');
            }

            return response()->json([
                'message' => $exception->getMessage(),
                'errors' => $exception->errors(),
            ], 422)->header('Cache-Control', 'no-store, private');
        }

        $authorized = DB::transaction(function () use ($user, $credentials): bool {
            // Re-read and lock the current account state so a role change or
            // suspension racing this request cannot pass using a stale model.
            $lockedUser = User::whereKey($user->getAuthIdentifier())->lockForUpdate()->first();
            if ($lockedUser === null || $lockedUser->role !== 'admin' || $lockedUser->is_suspended) {
                return false;
            }

            if (!Hash::check($credentials['current_password'], $lockedUser->password)) {
                return false;
            }

            return app(LocalAdminTotpService::class)->verifyAndConsume($lockedUser, $credentials['totp_code']);
        }, 3);

        if (!$authorized) {
            RateLimiter::hit($key, 900);
            return response()->json(['message' => 'Administrator re-authentication failed.'], 403)
                ->header('Cache-Control', 'no-store, private');
        }

        RateLimiter::clear($key);

        return $next($request);
    }
}
