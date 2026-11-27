<?php

namespace Tests\Feature;

use App\SupabaseBridge;
use Illuminate\Http\Client\ConnectionException;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class SupabaseBridgeTest extends TestCase
{
    protected function setUp(): void
    {
        parent::setUp();

        // Each test gets a clean rate-limit bucket; the default test client IP is shared.
        Cache::flush();

        config([
            'tacru.supabase_url' => 'https://tacresq-bridge.example.test',
            'tacru.supabase_publishable_key' => 'sb_publishable_test_only',
        ]);
    }

    public function test_bridge_allowlist_matches_the_reviewed_application_rpc_inventory(): void
    {
        $expected = [
            'api_active_responder_locations',
            'api_all_incidents',
            'api_assign_incident',
            'api_assign_incident_team',
            'api_cancel_my_incident',
            'api_create_incident_v4',
            'api_me',
            'api_my_incidents',
            'api_record_location_v2',
            'api_set_duty_status',
            'api_units',
            'api_update_incident_status',
            'api_update_my_incident',
        ];

        $actual = config('tacru.bridge_rpc_allowlist');

        sort($expected);
        sort($actual);

        $this->assertSame($expected, $actual);
        $this->assertCount(count(array_unique($actual)), $actual, 'Bridge allowlist must not contain duplicate RPC names.');
    }

    public function test_bridge_requires_a_bearer_token(): void
    {
        Http::preventStrayRequests();

        $this->getJson('/api/bridge/me')->assertUnauthorized();
        Http::assertNothingSent();
    }

    public function test_bridge_routes_apply_a_coarse_ip_rate_limit(): void
    {
        Http::preventStrayRequests();

        $lastResponse = null;
        for ($attempt = 0; $attempt < 601; $attempt++) {
            $lastResponse = $this->getJson('/api/bridge/me');
        }

        $this->assertNotNull($lastResponse);
        $lastResponse->assertTooManyRequests();
        Http::assertNothingSent();
    }

    public function test_bridge_limits_authenticated_users_independently_of_shared_ip(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_me' => Http::response(['id' => 7], 200),
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response([
                'id' => 'auth-user-rate-limit',
                'email' => 'requester@example.test',
            ], 200),
        ]);

        $lastResponse = null;
        for ($attempt = 0; $attempt < 121; $attempt++) {
            $lastResponse = $this->withToken('valid-supabase-access-token')->getJson('/api/bridge/me');
        }

        $this->assertNotNull($lastResponse);
        $lastResponse->assertTooManyRequests();
        $retryAfter = (int) $lastResponse->headers->get('Retry-After', '0');
        $this->assertGreaterThan(0, $retryAfter);
        $this->assertLessThanOrEqual(60, $retryAfter);
    }

    public function test_rpc_rejects_oversized_payload_before_supabase_authentication(): void
    {
        Http::preventStrayRequests();

        $this->withToken('valid-looking-token')
            ->postJson('/api/bridge/rpc/api_create_incident_v4', [
                'p_description' => str_repeat('x', 33000),
            ])
            ->assertStatus(413);

        Http::assertNothingSent();
    }

    public function test_rpc_rejects_non_object_json_arguments_before_authentication(): void
    {
        Http::preventStrayRequests();

        $this->withToken('valid-looking-token')
            ->postJson('/api/bridge/rpc/api_units', ['unexpected-list-item'])
            ->assertUnprocessable()
            ->assertJsonPath('message', 'RPC arguments must be a JSON object.');

        Http::assertNothingSent();
    }

    public function test_invalid_supabase_token_is_rejected_without_rpc_execution(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response(['message' => 'invalid token'], 401),
        ]);

        $this->postEmptyRpc('api_create_incident_v4', 'expired-or-invalid')->assertUnauthorized();

        Http::assertSentCount(1);
        Http::assertNotSent(fn ($request) => str_contains($request->url(), '/rest/v1/rpc/'));
    }

    public function test_rpc_uses_validated_callers_token_and_never_a_service_role_key(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_me' => Http::response(['id' => 7], 200),
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response([
                'id' => 'auth-user-123',
                'email' => 'requester@example.test',
                'user_metadata' => ['role' => 'dispatcher'],
            ], 200),
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_create_incident_v4' => Http::response([
                'id' => 42,
                'status' => 'pending',
            ], 200),
        ]);

        $this->withToken('valid-supabase-access-token')
            ->postJson('/api/bridge/rpc/api_create_incident_v4', [
                'p_type' => 'medical',
                'p_client_request_id' => '4f0eebcc-3d52-4f0f-986b-9e91ec70ab27',
            ])
            ->assertOk()
            ->assertJsonPath('id', 42);

        Http::assertSent(function ($request) {
            return str_contains($request->url(), '/rest/v1/rpc/api_me')
                && $request->hasHeader('Authorization', 'Bearer valid-supabase-access-token')
                && $request->hasHeader('apikey', 'sb_publishable_test_only');
        });
        Http::assertSent(function ($request) {
            return str_contains($request->url(), '/rest/v1/rpc/api_create_incident_v4')
                && $request->hasHeader('Authorization', 'Bearer valid-supabase-access-token')
                && $request->hasHeader('apikey', 'sb_publishable_test_only')
                && !str_contains($request->header('Authorization')[0] ?? '', 'service_role');
        });
    }

    public function test_rpc_without_arguments_is_forwarded_as_empty_json_object(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_me' => Http::response(['id' => 7], 200),
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response(['id' => 'auth-user-123'], 200),
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_units' => Http::response([], 200),
        ]);

        $this->postEmptyRpc('api_units', 'valid-supabase-access-token')->assertOk();

        Http::assertSent(function ($request) {
            return str_contains($request->url(), '/rest/v1/rpc/api_units')
                && $request->body() === '{}';
        });
    }

    public function test_unlisted_rpc_is_not_forwarded(): void
    {
        Http::preventStrayRequests();

        $this->postEmptyRpc('arbitrary_function', 'any-token')->assertNotFound();

        Http::assertNothingSent();
    }

    public function test_unlisted_rpc_attempt_is_audited_without_forwarding(): void
    {
        config([
            'tacru.supabase_secret_key' => 'synthetic-server-secret',
        ]);
        request()->attributes->set('auth_user_id', 'ab7d4ac2-2e0f-4b88-a1e1-0d8c7d7f1001');
        Http::fake([
            'https://tacresq-bridge.example.test/rest/v1/api_operation_logs' => Http::response(null, 201),
        ]);

        $result = app(SupabaseBridge::class)->callRpc(
            'valid-looking-token',
            'arbitrary_function',
            [],
        );

        $this->assertSame(404, $result['status']);
        $this->assertSame(['message' => 'RPC endpoint not found.'], $result['body']);
        Http::assertSentCount(1);
        Http::assertSent(function ($request): bool {
            $payload = $request->data();
            return str_ends_with($request->url(), '/rest/v1/api_operation_logs')
                && ($payload['provider'] ?? null) === 'supabase'
                && ($payload['operation'] ?? null) === 'rpc.arbitrary_function'
                && ($payload['outcome'] ?? null) === 'rejected'
                && ($payload['error_code'] ?? null) === 'rpc_not_allowlisted'
                && ($payload['actor_auth_user_id'] ?? null) === 'ab7d4ac2-2e0f-4b88-a1e1-0d8c7d7f1001';
        });
        Http::assertNotSent(fn ($request) => str_contains($request->url(), '/rest/v1/rpc/arbitrary_function'));
    }

    public function test_upstream_server_errors_are_sanitized(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_me' => Http::response(['id' => 7], 200),
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response(['id' => 'auth-user-123'], 200),
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_units' => Http::response([
                'message' => 'private database details',
            ], 503),
        ]);

        $this->postEmptyRpc('api_units', 'valid-supabase-access-token')->assertStatus(502)
            ->assertExactJson(['message' => 'Supabase is temporarily unavailable. Please retry.']);
    }

    public function test_rpc_connection_failure_returns_a_sanitized_502(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_me' => Http::response(['id' => 7], 200),
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response(['id' => 'auth-user-123'], 200),
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_units' => function () {
                throw new ConnectionException('private connection detail');
            },
        ]);

        $this->postEmptyRpc('api_units', 'valid-supabase-access-token')
            ->assertStatus(502)
            ->assertExactJson(['message' => 'Supabase is temporarily unavailable. Please retry.']);
    }

    public function test_provider_request_ids_are_captured_from_trusted_upstream_response_headers(): void
    {
        config(['tacru.supabase_secret_key' => 'synthetic-server-secret']);
        request()->attributes->set('correlation_id', '4f0eebcc-3d52-4f0f-986b-9e91ec70ab27');

        Http::fake([
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response(
                ['id' => 'ab7d4ac2-2e0f-4b88-a1e1-0d8c7d7f1001'],
                200,
                ['sb-request-id' => 'auth-gateway-request-123', 'x-request-id' => 'ignored-fallback'],
            ),
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_units' => Http::response(
                [],
                200,
                ['x-request-id' => 'self-hosted-rpc-request-456'],
            ),
            'https://tacresq-bridge.example.test/rest/v1/api_operation_logs' => Http::response(null, 201),
        ]);

        $this->assertNotNull(app(SupabaseBridge::class)->getUser('synthetic-access-token'));
        $result = app(SupabaseBridge::class)->callRpc('synthetic-access-token', 'api_units', []);
        $this->assertSame(200, $result['status']);

        $auditPayloads = [];
        Http::assertSent(function ($request) use (&$auditPayloads): bool {
            if (!str_ends_with($request->url(), '/rest/v1/api_operation_logs')) {
                return false;
            }
            $auditPayloads[] = $request->data();
            return true;
        });

        $this->assertCount(2, $auditPayloads);
        $this->assertSame(['auth.get_user', 'rpc.api_units'], array_column($auditPayloads, 'operation'));
        $this->assertSame(['auth-gateway-request-123', 'self-hosted-rpc-request-456'], array_column($auditPayloads, 'provider_request_id'));
        $this->assertSame([
            '4f0eebcc-3d52-4f0f-986b-9e91ec70ab27',
            '4f0eebcc-3d52-4f0f-986b-9e91ec70ab27',
        ], array_column($auditPayloads, 'correlation_id'));
    }

    private function postEmptyRpc(string $function, string $token)
    {
        // An empty PostgREST argument set is the JSON object {}, not the JSON array [].
        return $this->call('POST', '/api/bridge/rpc/'.$function, [], [], [], [
            'HTTP_AUTHORIZATION' => 'Bearer '.$token,
            'CONTENT_TYPE' => 'application/json',
            'HTTP_ACCEPT' => 'application/json',
        ], '{}');
    }

    public function test_suspended_or_unprovisioned_profile_is_denied_before_bridge_rpc(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response([
                'id' => 'auth-user-suspended',
                'email' => 'suspended@example.test',
            ], 200),
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_me' => Http::response(null, 200),
        ]);

        $this->postEmptyRpc('api_create_incident_v4', 'valid-supabase-access-token')
            ->assertForbidden()
            ->assertExactJson(['message' => 'An active TACResQ profile is required.']);

        Http::assertSentCount(2);
        Http::assertNotSent(fn ($request) => str_contains($request->url(), '/rest/v1/rpc/api_create_incident_v4'));
    }

    public function test_bridge_me_does_not_return_auth_identity_without_active_profile(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response([
                'id' => 'auth-user-suspended',
                'email' => 'suspended@example.test',
            ], 200),
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_me' => Http::response(null, 200),
        ]);

        $this->withToken('valid-supabase-access-token')->getJson('/api/bridge/me')
            ->assertForbidden()
            ->assertExactJson(['message' => 'An active TACResQ profile is required.']);

        Http::assertSentCount(2);
    }

    public function test_active_profile_preflight_outage_fails_closed_as_502(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response(['id' => 'auth-user-123'], 200),
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_me' => Http::response(['message' => 'database unavailable'], 503),
        ]);

        $this->postEmptyRpc('api_units', 'valid-supabase-access-token')
            ->assertStatus(502)
            ->assertExactJson(['message' => 'Supabase is temporarily unavailable. Please retry.']);

        Http::assertSentCount(2);
        Http::assertNotSent(fn ($request) => str_contains($request->url(), '/rest/v1/rpc/api_units'));
    }

    public function test_auth_upstream_outage_returns_502_not_a_false_invalid_session(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response([
                'message' => 'private upstream details',
            ], 503),
        ]);

        $this->withToken('valid-looking-access-token')
            ->getJson('/api/bridge/me')
            ->assertStatus(502)
            ->assertExactJson(['message' => 'Supabase is temporarily unavailable. Please retry.']);

        Http::assertSentCount(1);
    }
}
