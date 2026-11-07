<?php

namespace Tests\Feature;

use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class RoutePreviewTest extends TestCase
{
    private const BASE = 'https://tacresq-route.example.test';
    private const OSRM_BASE = 'http://osrm-router:5000';
    private const TOKEN = 'synthetic-test-access-token';
    private const GEOAPIFY_KEY = 'synthetic-test-geoapify-key';
    private const PROFILE_ID = 17;
    private const INCIDENT_ID = 81;

    protected function setUp(): void
    {
        parent::setUp();
        Cache::flush();
        config([
            'tacru.route_preview_enabled' => true,
            'tacru.supabase_url' => self::BASE,
            'tacru.supabase_publishable_key' => 'test-publishable-key',
            'tacru.route_provider' => 'osrm',
            'tacru.osrm_base_url' => self::OSRM_BASE,
            'tacru.geoapify_api_key' => self::GEOAPIFY_KEY,
        ]);
    }

    public function test_disabled_rollout_gate_rejects_provider_disclosure_without_auth_or_upstream_calls(): void
    {
        config(['tacru.route_preview_enabled' => false]);
        Http::preventStrayRequests();

        $this->getJson('/api/route-preview/provider')
            ->assertStatus(503)
            ->assertExactJson(['message' => 'In-app routing is not enabled for this environment.']);

        Http::assertNothingSent();
    }

    public function test_disabled_rollout_gate_rejects_route_requests_before_auth_or_coordinate_processing(): void
    {
        config(['tacru.route_preview_enabled' => false]);
        Http::preventStrayRequests();

        $this->withToken(self::TOKEN)
            ->postJson('/api/route-preview', $this->input())
            ->assertStatus(503)
            ->assertExactJson(['message' => 'In-app routing is not enabled for this environment.']);

        Http::assertNothingSent();
    }

    public function test_route_preview_requires_bearer_authentication(): void
    {
        Http::preventStrayRequests();
        $this->postJson('/api/route-preview', $this->input())->assertUnauthorized();
        Http::assertNothingSent();
    }

    public function test_invalid_token_is_rejected(): void
    {
        Http::fake([self::BASE.'/auth/v1/user' => Http::response(['message' => 'expired'], 401)]);
        $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input())->assertUnauthorized();
        Http::assertSentCount(1);
    }

    public function test_provider_disclosure_requires_bearer_authentication(): void
    {
        Http::preventStrayRequests();
        $this->getJson('/api/route-preview/provider')->assertUnauthorized();
        Http::assertNothingSent();
    }

    public function test_provider_disclosure_names_the_configured_recipient_without_sending_coordinates(): void
    {
        foreach ([
            'geoapify' => 'geoapify',
            'osrm' => 'private_osrm',
        ] as $configuredProvider => $expectedProviderId) {
            Cache::flush();
            config(['tacru.route_provider' => $configuredProvider]);
            $this->fakeSupabase();
            $this->withToken(self::TOKEN)->getJson('/api/route-preview/provider')
                ->assertOk()
                ->assertExactJson(['provider_id' => $expectedProviderId]);
        }

        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/')
            || str_starts_with($request->url(), 'https://api-eu.geoapify.com/'));
    }

    public function test_non_field_role_cannot_read_provider_disclosure(): void
    {
        $this->fakeSupabase(['role' => 'dispatcher']);
        $this->withToken(self::TOKEN)->getJson('/api/route-preview/provider')->assertForbidden();
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/')
            || str_starts_with($request->url(), 'https://api-eu.geoapify.com/'));
    }

    public function test_route_preview_rejects_provider_change_since_consent_before_forwarding_coordinates(): void
    {
        config(['tacru.route_provider' => 'geoapify']);
        $this->fakeSupabase();

        $this->withToken(self::TOKEN)
            ->postJson('/api/route-preview', $this->input(['expected_provider_id' => 'private_osrm']))
            ->assertStatus(409)
            ->assertExactJson([
                'message' => 'The routing provider changed. Verify the recipient again before requesting a route.',
            ]);

        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/')
            || str_starts_with($request->url(), 'https://api-eu.geoapify.com/'));
    }

    public function test_requester_or_dispatcher_profile_cannot_preview_routes(): void
    {
        foreach (['requester', 'dispatcher'] as $role) {
            Cache::flush();
            $this->fakeSupabase(['role' => $role]);
            $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input())->assertForbidden();
        }
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/'));
    }

    public function test_foreign_or_unassigned_or_inactive_incident_is_not_disclosed(): void
    {
        foreach ([
            $this->incident(['assigned_user_ids' => [999]]),
            $this->incident(['status' => 'resolved']),
            null,
        ] as $incident) {
            Cache::flush();
            $this->fakeSupabase([], $incident === null ? [] : [$incident]);
            $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input())->assertNotFound();
        }
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/'));
    }

    public function test_incident_without_coordinates_fails_without_contacting_osrm(): void
    {
        $this->fakeSupabase([], [$this->incident(['latitude' => null, 'longitude' => null])]);
        $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input())->assertStatus(422);
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/'));
    }

    public function test_missing_private_router_returns_safe_503(): void
    {
        config(['tacru.osrm_base_url' => '']);
        $this->fakeSupabase();
        $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input())
            ->assertStatus(503)->assertExactJson(['message' => 'In-app routing is not configured.']);
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/'));
    }

    public function test_public_router_hosts_are_rejected_before_coordinates_are_sent(): void
    {
        foreach ([
            'https://public-router.example.test',
            'http://8.8.8.8:5000',
            'http://127.0.0.1:5000',
            'http://[::1]:5000',
            'http://internal-user@osrm-router:5000',
            'http://osrm-router:5000?debug=1',
        ] as $url) {
            Cache::flush();
            config(['tacru.osrm_base_url' => $url]);
            $this->fakeSupabase();
            $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input())
                ->assertStatus(503)->assertExactJson(['message' => 'In-app routing is not configured.']);
        }
        Http::assertNotSent(fn ($request) => !str_starts_with($request->url(), self::BASE.'/'));
    }

    public function test_valid_preview_posts_only_coordinates_to_private_osrm_and_normalizes_result(): void
    {
        $this->fakeServices($this->osrmBody());
        $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input())
            ->assertOk()
            ->assertExactJson([
                'geometry' => [
                    ['latitude' => 14.5, 'longitude' => 121.0],
                    ['latitude' => 14.51, 'longitude' => 121.01],
                ],
                'distance_meters' => 1400.5,
                'duration_seconds' => 125.25,
                'instructions' => [['text' => 'Turn right onto Synthetic Road', 'distance_meters' => 15.5, 'duration_seconds' => 3.2]],
            ]);
        Http::assertSent(fn ($request) => $request->method() === 'POST'
            && $request->url() === self::OSRM_BASE.'/route/v1/driving'
            && $request->data()['coordinates'] === [[121.25, 14.25], [120.75, 14.75]]
            && $request->data()['steps'] === true
            && $request->data()['overview'] === 'full'
            && $request->data()['geometries'] === 'geojson'
            && !$request->hasHeader('Authorization')
            && !str_contains($request->url(), '121.25')
            && !str_contains($request->body(), self::TOKEN)
            && !str_contains($request->body(), 'incident narrative')
            && !str_contains($request->body(), (string) self::INCIDENT_ID));
    }

    public function test_geoapify_provider_uses_eu_endpoint_and_normalizes_route_without_leaking_key_or_context(): void
    {
        config(['tacru.route_provider' => 'geoapify']);
        $this->fakeGeoapify($this->geoapifyBody());

        $response = $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input());

        $response->assertOk()->assertExactJson([
            'geometry' => [
                ['latitude' => 14.5, 'longitude' => 121.0],
                ['latitude' => 14.51, 'longitude' => 121.01],
            ],
            'distance_meters' => 1400.5,
            'duration_seconds' => 125.25,
            'instructions' => [[
                'text' => 'Turn right onto Synthetic Road',
                'distance_meters' => 15.5,
                'duration_seconds' => 3.2,
            ]],
        ]);
        $this->assertStringNotContainsString(self::GEOAPIFY_KEY, $response->getContent());
        Http::assertSent(fn ($request) => $request->method() === 'GET'
            && parse_url($request->url(), PHP_URL_SCHEME) === 'https'
            && parse_url($request->url(), PHP_URL_HOST) === 'api-eu.geoapify.com'
            && parse_url($request->url(), PHP_URL_PATH) === '/v1/routing'
            && (function () use ($request): bool {
                $query = [];
                parse_str((string) parse_url($request->url(), PHP_URL_QUERY), $query);
                return ($query['waypoints'] ?? null) === '14.2500000,121.2500000|14.7500000,120.7500000'
                    && ($query['mode'] ?? null) === 'drive'
                    && ($query['format'] ?? null) === 'json'
                    && ($query['units'] ?? null) === 'metric'
                    && ($query['apiKey'] ?? null) === self::GEOAPIFY_KEY;
            })()
            && !$request->hasHeader('Authorization')
            && !str_contains($request->url(), (string) self::INCIDENT_ID)
            && !str_contains($request->url(), 'incident narrative'));
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/'));
    }

    public function test_geoapify_key_errors_are_sanitized_and_do_not_silently_retry_osrm(): void
    {
        config(['tacru.route_provider' => 'geoapify']);
        $this->fakeGeoapify(['message' => 'synthetic provider diagnostic '.self::GEOAPIFY_KEY], 403);

        $response = $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input());

        $response->assertStatus(502)->assertExactJson([
            'message' => 'Geoapify did not accept the configured server key.',
        ]);
        $this->assertStringNotContainsString(self::GEOAPIFY_KEY, $response->getContent());
        $this->assertStringNotContainsString('synthetic provider diagnostic', $response->getContent());
        Http::assertSentCount(4);
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/'));
    }

    public function test_geoapify_routing_fails_closed_when_server_key_is_missing(): void
    {
        config(['tacru.route_provider' => 'geoapify', 'tacru.geoapify_api_key' => '']);
        $this->fakeSupabase();

        $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input())
            ->assertStatus(503)
            ->assertExactJson(['message' => 'Geoapify routing is not configured.']);
        Http::assertSentCount(3);
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), 'https://api-eu.geoapify.com/'));
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/'));
    }

    public function test_geoapify_quota_and_network_errors_are_sanitized_without_osrm_retry(): void
    {
        config(['tacru.route_provider' => 'geoapify']);
        $this->fakeGeoapify(['message' => 'synthetic private quota detail'], 429);

        $quotaResponse = $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input());
        $quotaResponse->assertStatus(503)->assertExactJson([
            'message' => 'Geoapify routing is busy or out of credits. Use external Maps instead.',
        ]);
        $this->assertStringNotContainsString('synthetic private quota detail', $quotaResponse->getContent());
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/'));

        Cache::flush();
        Http::fake(array_merge($this->supabaseFakes(), [
            'https://api-eu.geoapify.com/v1/routing*' => static function () {
                throw new \Illuminate\Http\Client\ConnectionException('synthetic URL must not be exposed');
            },
        ]));
        $networkResponse = $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input());
        $networkResponse->assertStatus(502)->assertExactJson([
            'message' => 'Geoapify routing is temporarily unavailable.',
        ]);
        $this->assertStringNotContainsString('synthetic URL must not be exposed', $networkResponse->getContent());
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/'));
    }

    public function test_geoapify_malformed_route_geometry_fails_closed(): void
    {
        config(['tacru.route_provider' => 'geoapify']);
        $body = $this->geoapifyBody();
        $body['results'][0]['geometry'] = [[['lon' => 999, 'lat' => 14.5], ['lon' => 121.0, 'lat' => 14.51]]];
        $this->fakeGeoapify($body);

        $response = $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input());

        $response->assertStatus(502)->assertExactJson([
            'message' => 'Geoapify returned an invalid route. Use external Maps instead.',
        ]);
        Http::assertNotSent(fn ($request) => str_starts_with($request->url(), self::OSRM_BASE.'/'));
    }

    public function test_no_route_returns_safe_client_error_without_provider_details(): void
    {
        $this->fakeServices(['code' => 'NoRoute', 'message' => 'Synthetic internal error details'], 400);
        $response = $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input());
        $response->assertStatus(422)->assertExactJson([
            'message' => 'No route could be calculated. Confirm the destination with dispatch or use external Maps.',
        ]);
        $this->assertStringNotContainsString('Synthetic internal error details', $response->getContent());
    }

    public function test_invalid_and_oversized_upstream_responses_fail_closed(): void
    {
        $oversized = $this->osrmBody();
        $oversized['routes'][0]['geometry']['coordinates'] = array_fill(0, 5001, [121.0, 14.5]);
        foreach ([['code' => 'Ok', 'routes' => []], $oversized] as $upstreamBody) {
            Cache::flush();
            $this->fakeServices($upstreamBody);
            $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input())->assertStatus(502)
                ->assertExactJson(['message' => 'Private route service returned an invalid route.']);
        }
    }

    public function test_upstream_rate_limit_is_sanitized_as_service_unavailable(): void
    {
        $this->fakeServices(['message' => 'synthetic private router diagnostics'], 429);
        $response = $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input());
        $response->assertStatus(503);
        $this->assertStringNotContainsString('synthetic private router diagnostics', $response->getContent());
    }

    public function test_other_upstream_errors_are_sanitized_as_bad_gateway(): void
    {
        $this->fakeServices(['message' => 'synthetic private router diagnostics'], 503);
        $response = $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input());
        $response->assertStatus(502);
        $this->assertStringNotContainsString('synthetic private router diagnostics', $response->getContent());
    }

    public function test_route_calculation_is_limited_per_authenticated_user(): void
    {
        $this->fakeServices($this->osrmBody());
        $response = null;
        for ($i = 0; $i < 11; $i++) {
            $response = $this->withToken(self::TOKEN)->postJson('/api/route-preview', $this->input());
        }
        $response->assertTooManyRequests();
    }

    private function input(array $overrides = []): array
    {
        return array_merge([
            'incident_id' => self::INCIDENT_ID,
            'latitude' => 14.25,
            'longitude' => 121.25,
        ], $overrides);
    }

    private function incident(array $overrides = []): array
    {
        return array_merge([
            'id' => self::INCIDENT_ID,
            'status' => 'assigned',
            'description' => 'synthetic incident narrative must not be forwarded',
            'latitude' => 14.75,
            'longitude' => 120.75,
            'assigned_user_ids' => [self::PROFILE_ID],
            'assigned_user_statuses' => [['user_id' => self::PROFILE_ID, 'status' => 'assigned']],
        ], $overrides);
    }

    private function fakeSupabase(array $profileOverrides = [], ?array $incidents = null): void
    {
        Http::fake($this->supabaseFakes($profileOverrides, $incidents));
    }

    private function fakeServices(array $osrmBody, int $osrmStatus = 200): void
    {
        Http::fake(array_merge($this->supabaseFakes(), [
            self::OSRM_BASE.'/*' => Http::response($osrmBody, $osrmStatus),
        ]));
    }

    private function fakeGeoapify(array $geoapifyBody, int $geoapifyStatus = 200): void
    {
        Http::fake(array_merge($this->supabaseFakes(), [
            'https://api-eu.geoapify.com/v1/routing*' => Http::response($geoapifyBody, $geoapifyStatus),
        ]));
    }

    private function supabaseFakes(array $profileOverrides = [], ?array $incidents = null): array
    {
        return [
            self::BASE.'/auth/v1/user' => Http::response(['id' => 'synthetic-auth-user'], 200),
            self::BASE.'/rest/v1/rpc/api_me' => Http::response(array_merge(['id' => self::PROFILE_ID, 'role' => 'responder'], $profileOverrides), 200),
            self::BASE.'/rest/v1/rpc/api_my_incidents' => Http::response($incidents ?? [$this->incident()], 200),
        ];
    }

    private function osrmBody(): array
    {
        return ['code' => 'Ok', 'routes' => [[
            'geometry' => ['type' => 'LineString', 'coordinates' => [[121.0, 14.5], [121.01, 14.51]]],
            'distance' => 1400.5,
            'duration' => 125.25,
            'legs' => [['steps' => [[
                'distance' => 15.5,
                'duration' => 3.2,
                'name' => 'Synthetic Road',
                'maneuver' => ['type' => 'turn', 'modifier' => 'right'],
            ]]]],
        ]]];
    }

    private function geoapifyBody(): array
    {
        return ['results' => [[
            'geometry' => [[
                ['lon' => 121.0, 'lat' => 14.5],
                ['lon' => 121.01, 'lat' => 14.51],
            ]],
            'distance' => 1400.5,
            'time' => 125.25,
            'legs' => [['steps' => [[
                'distance' => 15.5,
                'time' => 3.2,
                'instruction' => ['text' => 'Turn right onto Synthetic Road'],
            ]]],
        ]]]];
    }
}
