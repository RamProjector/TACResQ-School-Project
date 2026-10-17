<?php

use Illuminate\Support\Facades\Route;

Route::get('/', static function () {
    return response()->file(public_path('dispatch/index.html'));
});

Route::get('/dispatch/config.json', static function () {
    return response()->json([
        'supabaseUrl' => config('tacru.supabase_url'),
        'supabasePublishableKey' => config('tacru.supabase_publishable_key'),
    ])->header('Cache-Control', 'no-store, private');
});
