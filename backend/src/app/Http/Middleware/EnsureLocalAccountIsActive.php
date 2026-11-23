<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

class EnsureLocalAccountIsActive
{
    public function handle(Request $request, Closure $next): Response
    {
        $user = $request->user();

        if ($user !== null && (bool) $user->is_suspended) {
            return response()->json([
                'message' => 'This local TACResQ account is suspended. Contact an administrator for assistance.',
            ], 403)->header('Cache-Control', 'no-store, private');
        }

        return $next($request);
    }
}
