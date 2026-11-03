<?php

namespace Tests\Feature;

use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class GeoapifySmokeTest extends TestCase
{
    private const URL = 'https://api-eu.geoapify.com/v1/routing';
    private const SMOKE_TOKEN = 'synthetic-geoapify-smoke-token-0123456789abcdef';
    private const API_KEY = 'synthetic-geoapify-api-key-never-real';
    private const HEADER = 'X-TACResQ-Smoke-Token';

    protected function setUp(): void
    {
        parent::setUp();
        Cache::flush();
        config([
            'tacru.geoapify_api_key' => self::API_KEY,
            'tacru.geoapify_smoke_test_token' => self::SMOKE_TOKEN,
        ]);
    }

    public function test_smoke_test_is_disabled_without_a_strong_server_token(): void
    {
        Http::preventStrayRequests();
        config(['tacru.geoapify_smoke_test_token' => '']);

        $this->post('/api/geoapify-smoke-test')->assertNotFound();
        Http::assertNothingSent();
    }

    public function test_wrong_smoke_token_is_not_disclosed_and_sends_no_request(): void
    {
        Http::preventStrayRequests();

        $this->withHeader(self::HEADER, 'wrong-token')
            ->post('/api/geoapify-smoke-test')
            ->assertNotFound();
        Http::assertNothingSent();
    }

    public function test_caller_cannot_supply_coordinates_or_other_request_data(): void
    {
        Http::preventStrayRequests();

        $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test?latitude=10.0&longitude=20.0')
            ->assertStatus(422)
            ->assertExactJson(['message' => 'This smoke test accepts no caller-supplied data.']);
        $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->postJson('/api/geoapify-smoke-test', ['latitude' => 10.0, 'longitude' => 20.0])
            ->assertStatus(422)
            ->assertExactJson(['message' => 'This smoke test accepts no caller-supplied data.']);
        Http::assertNothingSent();
    }

    public function test_missing_geoapify_key_fails_without_contacting_the_provider(): void
    {
        Http::preventStrayRequests();
        config(['tacru.geoapify_api_key' => '']);

        $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test')
            ->assertStatus(503)
            ->assertExactJson(['message' => 'Geoapify is not configured on this service.']);
        Http::assertNothingSent();
    }

    public function test_success_uses_only_fixed_synthetic_waypoints_and_returns_a_sanitized_summary(): void
    {
        Http::fake([
            self::URL.'*' => Http::response([
                'results' => [['distance' => 1850.5, 'time' => 240.25]],
                'query' => ['waypoints' => 'synthetic response data'],
            ], 200),
        ]);

        $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test');

        $response->assertOk()->assertExactJson([
            'status' => 'passed',
            'provider' => 'Geoapify EU',
            'coordinates' => 'synthetic test points only',
            'distance_meters' => 1850.5,
            'duration_seconds' => 240.25,
        ]);
        $this->assertStringNotContainsString(self::API_KEY, $response->getContent());
        Http::assertSent(fn ($request) => $request->method() === 'GET'
            && parse_url($request->url(), PHP_URL_SCHEME) === 'https'
            && parse_url($request->url(), PHP_URL_HOST) === 'api-eu.geoapify.com'
            && parse_url($request->url(), PHP_URL_PATH) === '/v1/routing'
            && (function () use ($request): bool {
                $query = [];
                parse_str((string) parse_url($request->url(), PHP_URL_QUERY), $query);
                return ($query['waypoints'] ?? null) === '11.2443,125.0030|11.2500,125.0200'
                    && ($query['mode'] ?? null) === 'drive'
                    && ($query['format'] ?? null) === 'json'
                    && ($query['apiKey'] ?? null) === self::API_KEY;
            })()
            && !$request->hasHeader('Authorization'));
        Http::assertSentCount(1);
    }

    public function test_provider_key_rejection_and_upstream_diagnostics_are_sanitized(): void
    {
        Http::fake([
            self::URL.'*' => Http::response([
                'message' => 'synthetic provider diagnostic '.self::API_KEY,
            ], 403),
        ]);

        $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test');

        $response->assertStatus(502)->assertExactJson([
            'message' => 'Geoapify did not accept the configured server key.',
        ]);
        $this->assertStringNotContainsString(self::API_KEY, $response->getContent());
        $this->assertStringNotContainsString('synthetic provider diagnostic', $response->getContent());
    }

    public function test_network_exception_details_are_sanitized(): void
    {
        Http::fake(static function () {
            throw new \Illuminate\Http\Client\ConnectionException('synthetic failing URL '.self::API_KEY);
        });

        $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test');

        $response->assertStatus(502)->assertExactJson(['message' => 'Geoapify test request failed.']);
        $this->assertStringNotContainsString(self::API_KEY, $response->getContent());
        $this->assertStringNotContainsString('synthetic failing URL', $response->getContent());
    }

    public function test_provider_rate_limit_and_malformed_success_are_sanitized(): void
    {
        foreach ([
            [Http::response(['message' => 'synthetic quota detail'], 429), 503, 'Geoapify is rate-limited or out of credits.'],
            [Http::response(['results' => []], 200), 502, 'Geoapify returned an invalid smoke-test route.'],
        ] as [$fakeResponse, $expectedStatus, $expectedMessage]) {
            Cache::flush();
            Http::fake([self::URL.'*' => $fakeResponse]);
            $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
                ->post('/api/geoapify-smoke-test');
            $response->assertStatus($expectedStatus)->assertExactJson(['message' => $expectedMessage]);
            $this->assertStringNotContainsString('synthetic quota detail', $response->getContent());
        }
    }

    public function test_smoke_endpoint_limits_provider_calls_per_minute(): void
    {
        Http::fake([
            self::URL.'*' => Http::response(['results' => [['distance' => 100.0, 'time' => 30.0]]], 200),
        ]);

        $response = null;
        for ($attempt = 0; $attempt < 6; $attempt++) {
            $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
                ->post('/api/geoapify-smoke-test');
        }

        $response->assertTooManyRequests();
        Http::assertSentCount(5);
    }
}
