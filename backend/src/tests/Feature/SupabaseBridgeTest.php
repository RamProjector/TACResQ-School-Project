<?php

namespace Tests\Feature;

use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class SupabaseBridgeTest extends TestCase
{
    protected function setUp(): void
    {
        parent::setUp();

        config([
            'tacru.supabase_url' => 'https://tacresq-bridge.example.test',
            'tacru.supabase_publishable_key' => 'sb_publishable_test_only',
        ]);
    }

    public function test_bridge_requires_a_bearer_token(): void
    {
        Http::preventStrayRequests();

        $this->getJson('/api/bridge/me')->assertUnauthorized();
        Http::assertNothingSent();
    }

    public function test_invalid_supabase_token_is_rejected_without_rpc_execution(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response(['message' => 'invalid token'], 401),
        ]);

        $this->withToken('expired-or-invalid')
            ->postJson('/api/bridge/rpc/api_create_incident_v4', [])
            ->assertUnauthorized();

        Http::assertSentCount(1);
        Http::assertNotSent(fn ($request) => str_contains($request->url(), '/rest/v1/rpc/'));
    }

    public function test_rpc_uses_validated_callers_token_and_never_a_service_role_key(): void
    {
        Http::fake([
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
            return str_contains($request->url(), '/rest/v1/rpc/api_create_incident_v4')
                && $request->hasHeader('Authorization', 'Bearer valid-supabase-access-token')
                && $request->hasHeader('apikey', 'sb_publishable_test_only')
                && !str_contains($request->header('Authorization')[0] ?? '', 'service_role');
        });
    }

    public function test_unlisted_rpc_is_not_forwarded(): void
    {
        Http::preventStrayRequests();

        $this->withToken('any-token')
            ->postJson('/api/bridge/rpc/arbitrary_function', [])
            ->assertNotFound();

        Http::assertNothingSent();
    }

    public function test_upstream_server_errors_are_sanitized(): void
    {
        Http::fake([
            'https://tacresq-bridge.example.test/auth/v1/user' => Http::response(['id' => 'auth-user-123'], 200),
            'https://tacresq-bridge.example.test/rest/v1/rpc/api_units' => Http::response([
                'message' => 'private database details',
            ], 503),
        ]);

        $this->withToken('valid-supabase-access-token')
            ->postJson('/api/bridge/rpc/api_units', [])
            ->assertStatus(502)
            ->assertExactJson(['message' => 'Supabase is temporarily unavailable. Please retry.']);
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
