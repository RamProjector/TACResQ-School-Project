<?php

use App\FcmNotificationDispatcher;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Route;

Route::get('/', static function () {
    return response()->file(public_path('dispatch/index.html'));
});

$runtimeConfig = static function () {
    // This endpoint is intentionally runtime-configured for the static dashboard.
    // Read process environment values directly so a config cache baked into an
    // image cannot pin the dashboard to build-time Supabase settings.
    $supabaseUrl = getenv('SUPABASE_URL') ?: ($_SERVER['SUPABASE_URL'] ?? $_ENV['SUPABASE_URL'] ?? config('tacru.supabase_url'));
    $publishableKey = getenv('SUPABASE_PUBLISHABLE_KEY') ?: ($_SERVER['SUPABASE_PUBLISHABLE_KEY'] ?? $_ENV['SUPABASE_PUBLISHABLE_KEY'] ?? config('tacru.supabase_publishable_key'));

    return response()->json([
        'supabaseUrl' => $supabaseUrl,
        'supabasePublishableKey' => $publishableKey,
    ])->header('Cache-Control', 'no-store, private');
};

Route::get('/runtime-config.json', $runtimeConfig);
Route::get('/dispatch/config.json', $runtimeConfig);

// Private, payload-free trigger used only by the Supabase Cron job. The caller
// signs a timestamp, unique request ID, and exact empty JSON body with HMAC-SHA256.
// No notification IDs, recipients, tokens, or message content are accepted here.
Route::post('/internal/fcm/dispatch', function (Request $request, FcmNotificationDispatcher $dispatcher) {
    $json = static fn (array $body, int $status) => response()->json($body, $status)
        ->header('Cache-Control', 'no-store, private');
    $secret = (string) config('tacru.fcm_dispatch_webhook_secret', '');
    if (strlen($secret) < 32) {
        return $json(['message' => 'Dispatch trigger is not configured.'], 503);
    }

    $timestamp = (string) $request->header('X-TACResQ-Timestamp', '');
    $requestId = strtolower((string) $request->header('X-TACResQ-Request-Id', ''));
    $signature = strtolower((string) $request->header('X-TACResQ-Signature', ''));
    if (!ctype_digit($timestamp)
        || abs(time() - (int) $timestamp) > 300
        || !preg_match('/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/', $requestId)
        || !preg_match('/^[0-9a-f]{64}$/', $signature)) {
        return $json(['message' => 'Unauthorized.'], 401);
    }

    $rawBody = $request->getContent();
    $body = json_decode($rawBody);
    if (!$body instanceof \stdClass || count((array) $body) !== 0 || $request->query->count() !== 0) {
        return $json(['message' => 'This endpoint accepts only an empty JSON object.'], 422);
    }

    $signedContent = $timestamp."\n".$requestId."\n".$rawBody;
    $expected = hash_hmac('sha256', $signedContent, $secret);
    if (!hash_equals($expected, $signature)) {
        return $json(['message' => 'Unauthorized.'], 401);
    }

    $replayKey = 'tacresq:fcm-dispatch:request:'.hash('sha256', $requestId);
    if (!Cache::add($replayKey, true, now()->addMinutes(10))) {
        return $json(['message' => 'Request already processed.'], 409);
    }

    // File-cache locking is available on the current single-instance Render
    // deployment and prevents overlapping Cron invocations from draining batches.
    $lock = Cache::lock('tacresq:fcm-dispatch:worker', 90);
    if (!$lock->get()) {
        return $json(['ok' => true, 'skipped' => 'dispatch_already_running'], 202);
    }

    try {
        // Five token deliveries cap worst-case serial provider waits to ~50 seconds.
        $stats = $dispatcher->dispatch(5);
        $status = ($stats['failed'] ?? 0) > 0 ? 503 : 200;
        return $json(['ok' => $status === 200, 'counts' => $stats], $status);
    } catch (\Throwable) {
        // Never return exception text: it may contain provider URLs or configuration.
        return $json(['message' => 'FCM dispatch is temporarily unavailable.'], 503);
    } finally {
        $lock->release();
    }
})->withoutMiddleware([\Illuminate\Foundation\Http\Middleware\ValidateCsrfToken::class])
  ->middleware('throttle:5,1');
