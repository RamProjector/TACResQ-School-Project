<?php

use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Http;

Artisan::command('tacru:dependency-health {--live : Perform bounded read-only Supabase health probes}', function (): int {
    // The default remains configuration-only. Live mode is explicit and
    // contacts only the two safe Supabase health endpoints below.
    $configured = static fn (string $key): bool => trim((string) config($key, '')) !== '';

    $supabaseBase = $configured('tacru.supabase_url')
        && $configured('tacru.supabase_publishable_key');
    $supabaseUrl = rtrim((string) config('tacru.supabase_url', ''), '/');
    $publishableKey = (string) config('tacru.supabase_publishable_key', '');
    $supabaseServer = $configured('tacru.supabase_secret_key');
    $fcmConfigured = $supabaseServer && $configured('tacru.fcm_project_id');

    $routingEnabled = (bool) config('tacru.route_preview_enabled', false);
    $provider = strtolower(trim((string) config('tacru.route_provider', '')));
    $routingConfigured = match ($provider) {
        'geoapify' => $configured('tacru.geoapify_api_key'),
        'osrm' => $configured('tacru.osrm_base_url'),
        default => false,
    };

    $live = (bool) $this->option('live');
    $probe = static function (callable $request, callable $validResponse): array {
        $startedAt = hrtime(true);
        try {
            $response = $request();
            $status = $response->successful() && $validResponse($response)
                ? 'healthy'
                : ($response->status() === 429 ? 'degraded' : 'unavailable');
        } catch (\Throwable) {
            // Provider exceptions can contain URLs, response bodies, or other
            // sensitive details. Report only a stable outcome category.
            $status = 'unavailable';
        }

        return [
            'status' => $status,
            'duration_ms' => (int) ((hrtime(true) - $startedAt) / 1_000_000),
        ];
    };

    $authStatus = [
        'status' => $supabaseBase ? 'configuration_present' : 'not_configured',
        'scope' => 'url_and_publishable_key_presence_only',
    ];
    $rpcStatus = [
        'status' => $supabaseBase ? 'configuration_present' : 'not_configured',
        'scope' => 'url_and_publishable_key_presence_only',
    ];

    if ($live && $supabaseBase) {
        $authStatus = $probe(
            static fn () => Http::acceptJson()
                ->withHeaders(['apikey' => $publishableKey])
                ->connectTimeout(2)
                ->timeout(5)
                ->get($supabaseUrl.'/auth/v1/health'),
            static fn ($response): bool => $response->successful(),
        );
        $authStatus['scope'] = 'supabase_auth_health_endpoint';

        $rpcStatus = $probe(
            static fn () => Http::acceptJson()
                ->asJson()
                ->withHeaders(['apikey' => $publishableKey])
                ->connectTimeout(2)
                ->timeout(5)
                ->post($supabaseUrl.'/rest/v1/rpc/api_dependency_health', new \stdClass()),
            static fn ($response): bool => json_decode($response->body(), true) === 'tacresq_database_ok',
        );
        $rpcStatus['scope'] = 'data_free_database_health_rpc';
    }

    $report = [
        'mode' => $live ? 'live_read_only' : 'configuration_only',
        'network_requests' => $live && $supabaseBase,
        'database_queries' => $live && $supabaseBase,
        'mutations' => false,
        'components' => [
            'app' => ['status' => 'healthy', 'scope' => 'artisan_booted'],
            'supabase_auth' => $authStatus,
            'supabase_rpc' => $rpcStatus,
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
})->purpose('Report dependency configuration or perform bounded read-only Supabase health probes');
