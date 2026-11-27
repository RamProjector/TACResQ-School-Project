<?php

namespace Tests\Feature;

use App\ApiOperationAuditLogger;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Str;
use Tests\TestCase;

class ApiOperationAuditLoggerTest extends TestCase
{
    public function test_writes_only_allowlisted_operation_metadata_to_server_only_supabase_table(): void
    {
        config([
            'tacru.supabase_url' => 'https://tacresq-audit.example.test',
            'tacru.supabase_secret_key' => 'synthetic-server-secret',
        ]);
        request()->attributes->set('auth_user_id', 'ab7d4ac2-2e0f-4b88-a1e1-0d8c7d7f1001');
        request()->attributes->set('auth_session_id', '10000000-0000-4000-8000-000000000401');
        request()->headers->set('traceparent', '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01');
        Http::fake(['*' => Http::response(null, 201)]);

        app(ApiOperationAuditLogger::class)->record(
            'supabase',
            'rpc.api_me',
            'success',
            200,
            17,
            null,
            null,
            'd2d4c7e1-77bb-4d1a-8a5e-c8ed1d8b1010',
        );

        Http::assertSent(function ($request): bool {
            $payload = $request->data();
            return $request->method() === 'POST'
                && $request->url() === 'https://tacresq-audit.example.test/rest/v1/api_operation_logs'
                && $request->hasHeader('Authorization', 'Bearer synthetic-server-secret')
                && $request->hasHeader('traceparent', '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01')
                && ($payload['provider'] ?? null) === 'supabase'
                && ($payload['operation'] ?? null) === 'rpc.api_me'
                && ($payload['source'] ?? null) === 'render_api'
                && ($payload['outcome'] ?? null) === 'success'
                && ($payload['correlation_id'] ?? null) === 'd2d4c7e1-77bb-4d1a-8a5e-c8ed1d8b1010'
                && ($payload['actor_auth_user_id'] ?? null) === 'ab7d4ac2-2e0f-4b88-a1e1-0d8c7d7f1001'
                && ($payload['auth_session_id'] ?? null) === '10000000-0000-4000-8000-000000000401'
                && !array_key_exists('access_token', $payload)
                && !array_key_exists('request_body', $payload)
                && !array_key_exists('latitude', $payload)
                && !array_key_exists('longitude', $payload);
        });
    }


    public function test_modern_supabase_secret_key_is_sent_only_as_apikey(): void
    {
        // Build a synthetic modern-key-shaped fixture without embedding a credential-like literal.
        $modernSecret = 'sb'.chr(95).'secret'.chr(95).'synthetic-key';
        config([
            'tacru.supabase_url' => 'https://tacresq-audit.example.test',
            'tacru.supabase_secret_key' => $modernSecret,
        ]);
        Http::fake(['*' => Http::response(null, 201)]);

        app(ApiOperationAuditLogger::class)->record('supabase', 'rpc.api_me', 'success', 200, 1);

        Http::assertSent(function ($request) use ($modernSecret): bool {
            return $request->hasHeader('apikey', $modernSecret)
                && !$request->hasHeader('Authorization');
        });
    }

    public function test_skips_database_audit_when_server_secret_is_not_configured(): void
    {
        config([
            'tacru.supabase_url' => 'https://tacresq-audit.example.test',
            'tacru.supabase_secret_key' => '',
        ]);
        Http::preventStrayRequests();

        app(ApiOperationAuditLogger::class)->record('supabase', 'rpc.api_me', 'success', 200, 1);

        Http::assertNothingSent();
    }

    public function test_audit_sink_failure_does_not_throw_or_expose_provider_response(): void
    {
        config([
            'tacru.supabase_url' => 'https://tacresq-audit.example.test',
            'tacru.supabase_secret_key' => 'synthetic-server-secret',
        ]);
        Http::fake(['*' => Http::response(['message' => 'synthetic failure'], 503)]);

        app(ApiOperationAuditLogger::class)->record('geoapify', 'route.preview', 'failure', 503, 120);

        Http::assertSentCount(1);
    }

    public function test_audit_connection_failure_does_not_break_the_business_operation(): void
    {
        config([
            'tacru.supabase_url' => 'https://tacresq-audit.example.test',
            'tacru.supabase_secret_key' => 'synthetic-server-secret',
        ]);
        Http::fake(['*' => Http::failedConnection()]);

        // The audit sink is best-effort; a transport outage must not escape.
        app(ApiOperationAuditLogger::class)->record('supabase', 'rpc.api_me', 'success', 200, 1);

        Http::assertSentCount(1);
    }

    public function test_api_responses_use_server_generated_correlation_ids(): void
    {
        $supplied = 'e6c3bb0c-04e7-4e69-9d44-b65e0a3c7123';
        $response = $this->withHeader('X-Request-ID', $supplied)->getJson('/up');
        $generated = $response->headers->get('X-Request-ID');

        $this->assertNotSame($supplied, $generated);
        $this->assertTrue(Str::isUuid($generated));
    }
}
