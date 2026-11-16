<?php

namespace Tests\Feature;

use App\ApiOperationAuditLogger;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class ApiOperationAuditLoggerTest extends TestCase
{
    public function test_writes_only_allowlisted_operation_metadata_to_server_only_supabase_table(): void
    {
        config([
            'tacru.supabase_url' => 'https://tacresq-audit.example.test',
            'tacru.supabase_secret_key' => 'synthetic-server-secret',
        ]);
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
                && ($payload['provider'] ?? null) === 'supabase'
                && ($payload['operation'] ?? null) === 'rpc.api_me'
                && ($payload['source'] ?? null) === 'render_api'
                && ($payload['outcome'] ?? null) === 'success'
                && ($payload['correlation_id'] ?? null) === 'd2d4c7e1-77bb-4d1a-8a5e-c8ed1d8b1010'
                && !array_key_exists('access_token', $payload)
                && !array_key_exists('request_body', $payload)
                && !array_key_exists('latitude', $payload)
                && !array_key_exists('longitude', $payload);
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

    public function test_audit_sink_failure_does_not_break_the_business_request(): void
    {
        config([
            'tacru.supabase_url' => 'https://tacresq-audit.example.test',
            'tacru.supabase_secret_key' => 'synthetic-server-secret',
        ]);
        Http::fake(['*' => Http::response(['message' => 'synthetic failure'], 503)]);

        app(ApiOperationAuditLogger::class)->record('geoapify', 'route.preview', 'failure', 503, 120);

        Http::assertSentCount(1);
        $this->assertTrue(true);
    }

    public function test_api_responses_include_a_valid_correlation_id_and_replace_invalid_input(): void
    {
        $valid = 'e6c3bb0c-04e7-4e69-9d44-b65e0a3c7123';
        $this->withHeader('X-Request-ID', $valid)->getJson('/up')
            ->assertHeader('X-Request-ID', $valid);

        $response = $this->withHeader('X-Request-ID', 'not-a-uuid')->getJson('/up');
        $replacement = $response->headers->get('X-Request-ID');
        $this->assertNotSame('not-a-uuid', $replacement);
        $this->assertTrue(\\Illuminate\\Support\\Str::isUuid($replacement));
    }
}
