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
    private const WAYPOINTS = '11.2443000,125.0030000|11.2500000,125.0200000';
    private const SUCCESS_RESPONSE = [
        'results' => [[
            'distance' => 2310.5,
            'time' => 245.25,
            'geometry' => [[
                ['lon' => 125.0030, 'lat' => 11.2443],
                ['lon' => 125.0120, 'lat' => 11.2470],
                ['lon' => 125.0200, 'lat' => 11.2500],
            ]],
        ]],
    ];

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

    public function test_success_gets_only_the_fixed_synthetic_route_and_returns_a_sanitized_summary(): void
    {
        Http::fake([
            self::URL.'*' => Http::response(self::SUCCESS_RESPONSE, 200),
        ]);

        $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test');

        $response->assertOk()->assertExactJson([
            'status' => 'passed',
            'provider' => 'Geoapify Routing API',
            'coordinates' => 'synthetic test points only',
            'distance_meters' => 2310.5,
            'duration_seconds' => 245.3,
            'geometry_points' => 3,
        ]);
        $this->assertStringNotContainsString(self::API_KEY, $response->getContent());
        $this->assertStringNotContainsString('125.0030', $response->getContent());
        Http::assertSent(fn ($request) => $request->method() === 'GET'
            && parse_url($request->url(), PHP_URL_SCHEME) === 'https'
            && parse_url($request->url(), PHP_URL_HOST) === 'api-eu.geoapify.com'
            && parse_url($request->url(), PHP_URL_PATH) === '/v1/routing'
            && (function () use ($request): bool {
                $query = [];
                parse_str((string) parse_url($request->url(), PHP_URL_QUERY), $query);
                return ($query['apiKey'] ?? null) === self::API_KEY
                    && ($query['waypoints'] ?? null) === self::WAYPOINTS
                    && ($query['mode'] ?? null) === 'drive'
                    && ($query['format'] ?? null) === 'json'
                    && ($query['units'] ?? null) === 'metric'
                    && count($query) === 5;
            })()
            && !$request->hasHeader('Authorization'));
        Http::assertSentCount(1);
    }

    public function test_provider_rejection_is_sanitized(): void
    {
        Http::fake([
            self::URL.'*' => Http::response([
                'message' => 'synthetic provider diagnostic '.self::API_KEY,
            ], 403),
        ]);

        $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test');

        $response->assertStatus(502)->assertExactJson([
            'message' => 'Geoapify rejected the Routing API request or configured key.',
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

        $response->assertStatus(502)->assertExactJson([
            'message' => 'Geoapify Routing API test request failed.',
        ]);
        $this->assertStringNotContainsString(self::API_KEY, $response->getContent());
        $this->assertStringNotContainsString('synthetic failing URL', $response->getContent());
    }

    public function test_provider_rate_limit_is_sanitized(): void
    {
        Http::fake([
            self::URL.'*' => Http::response(['message' => 'synthetic quota detail'], 429),
        ]);

        $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test');

        $response->assertStatus(503)->assertExactJson([
            'message' => 'Geoapify Routing API is rate-limited or out of credits.',
        ]);
        $this->assertStringNotContainsString('synthetic quota detail', $response->getContent());
    }

    public function test_malformed_success_response_is_sanitized(): void
    {
        Http::fake([
            self::URL.'*' => Http::response(['results' => []], 200),
        ]);

        $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test');

        $response->assertStatus(502)->assertExactJson([
            'message' => 'Geoapify returned an invalid Routing API result.',
        ]);
    }

    public function test_smoke_endpoint_limits_provider_calls_per_minute(): void
    {
        Http::fake([
            self::URL.'*' => Http::response(self::SUCCESS_RESPONSE, 200),
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
