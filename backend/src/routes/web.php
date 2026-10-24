<?php

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
