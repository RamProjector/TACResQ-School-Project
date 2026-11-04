<?php

namespace Tests\Feature;

use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class GeoapifySmokeTest extends TestCase
{
    private const URL = 'https://api.geoapify.com/v1/routeplanner';
    private const SMOKE_TOKEN = 'synthetic-geoapify-smoke-token-0123456789abcdef';
    private const API_KEY = 'synthetic-geoapify-api-key-never-real';
    private const HEADER = 'X-TACResQ-Smoke-Token';
    private const PLANNER_PAYLOAD = [
        'mode' => 'drive',
        'agents' => [[
            'id' => 'tacresq-smoke-agent',
            'start_location' => [125.0030, 11.2443],
            'pickup_capacity' => 1,
        ]],
        'jobs' => [[
            'id' => 'tacresq-smoke-job',
            'location' => [125.0200, 11.2500],
            'duration' => 60,
            'pickup_amount' => 1,
        ]],
    ];
    private const SUCCESS_RESPONSE = [
        'type' => 'FeatureCollection',
        'features' => [[
            'type' => 'Feature',
            'properties' => [
                'actions' => [
                    ['type' => 'start'],
                    ['type' => 'job'],
                    ['type' => 'end'],
                ],
            ],
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

    public function test_success_posts_only_the_fixed_synthetic_plan_and_returns_a_sanitized_summary(): void
    {
        Http::fake([
            self::URL.'*' => Http::response(self::SUCCESS_RESPONSE, 200),
        ]);

        $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test');

        $response->assertOk()->assertExactJson([
            'status' => 'passed',
            'provider' => 'Geoapify Route Planner',
            'coordinates' => 'synthetic test points only',
            'agents_requested' => 1,
            'jobs_requested' => 1,
            'routes_returned' => 1,
            'jobs_planned' => 1,
        ]);
        $this->assertStringNotContainsString(self::API_KEY, $response->getContent());
        Http::assertSent(fn ($request) => $request->method() === 'POST'
            && parse_url($request->url(), PHP_URL_SCHEME) === 'https'
            && parse_url($request->url(), PHP_URL_HOST) === 'api.geoapify.com'
            && parse_url($request->url(), PHP_URL_PATH) === '/v1/routeplanner'
            && $request->hasHeader('Content-Type')
            && (function () use ($request): bool {
                $query = [];
                parse_str((string) parse_url($request->url(), PHP_URL_QUERY), $query);
                $body = json_decode($request->body(), true);
                return ($query['apiKey'] ?? null) === self::API_KEY
                    && $body === self::PLANNER_PAYLOAD
                    && !str_contains($request->body(), self::API_KEY);
            })()
            && !$request->hasHeader('Authorization'));
        Http::assertSentCount(1);
    }

    public function test_planner_rejection_and_upstream_diagnostics_are_sanitized(): void
    {
        Http::fake([
            self::URL.'*' => Http::response([
                'message' => 'synthetic provider diagnostic '.self::API_KEY,
            ], 403),
        ]);

        $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test');

        $response->assertStatus(502)->assertExactJson([
            'message' => 'Geoapify rejected the Route Planner request or configured key.',
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
            'message' => 'Geoapify Route Planner test request failed.',
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
            'message' => 'Geoapify Route Planner is rate-limited or out of credits.',
        ]);
        $this->assertStringNotContainsString('synthetic quota detail', $response->getContent());
    }

    public function test_malformed_success_response_is_sanitized(): void
    {
        Http::fake([
            self::URL.'*' => Http::response(['type' => 'FeatureCollection', 'features' => []], 200),
        ]);

        $response = $this->withHeader(self::HEADER, self::SMOKE_TOKEN)
            ->post('/api/geoapify-smoke-test');

        $response->assertStatus(502)->assertExactJson([
            'message' => 'Geoapify returned an invalid Route Planner result.',
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
