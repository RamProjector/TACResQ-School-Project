<?php

use Illuminate\Support\Facades\Artisan;

Artisan::command('tacru:dependency-health', function (): int {
    // This is deliberately a configuration-only diagnostic. It performs no
    // network requests, database queries, queue claims, or provider actions.
    $configured = static fn (string $key): bool => trim((string) config($key, '')) !== '';

    $supabaseBase = $configured('tacru.supabase_url')
        && $configured('tacru.supabase_publishable_key');
    $supabaseServer = $configured('tacru.supabase_secret_key');
    $fcmConfigured = $supabaseServer && $configured('tacru.fcm_project_id');

    $routingEnabled = (bool) config('tacru.route_preview_enabled', false);
    $provider = strtolower(trim((string) config('tacru.route_provider', '')));
    $routingConfigured = match ($provider) {
        'geoapify' => $configured('tacru.geoapify_api_key'),
        'osrm' => $configured('tacru.osrm_base_url'),
        default => false,
    };

    $report = [
        'mode' => 'configuration_only',
        'network_requests' => false,
        'database_queries' => false,
        'mutations' => false,
        'components' => [
            'app' => ['status' => 'healthy', 'scope' => 'artisan_booted'],
            'supabase_auth' => [
                'status' => $supabaseBase ? 'configuration_present' : 'not_configured',
                'scope' => 'url_and_publishable_key_presence_only',
            ],
            'supabase_rpc' => [
                'status' => $supabaseBase ? 'configuration_present' : 'not_configured',
                'scope' => 'url_and_publishable_key_presence_only',
            ],
            'fcm' => [
                'status' => $fcmConfigured ? 'configuration_present' : 'not_configured',
                'scope' => 'server_key_and_project_id_presence_only',
            ],
            'routing' => [
                'status' => !$routingEnabled ? 'disabled' : ($routingConfigured ? 'configuration_present' : 'not_configured'),
                'scope' => 'rollout_and_selected_provider_configuration_presence_only',
            ],
        ],
    ];

    $this->line(json_encode($report, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR));

    return self::SUCCESS;
})->purpose('Report dependency configuration presence without contacting providers');
