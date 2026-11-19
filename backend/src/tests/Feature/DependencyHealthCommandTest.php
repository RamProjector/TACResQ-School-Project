<?php

namespace Tests\Feature;

use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class DependencyHealthCommandTest extends TestCase
{
    public function test_dependency_health_command_reports_configuration_without_network_or_mutations(): void
    {
        config([
            'tacru.supabase_url' => 'https://synthetic-project.example.test',
            'tacru.supabase_publishable_key' => 'synthetic-publishable-key',
            'tacru.supabase_secret_key' => 'synthetic-server-secret',
            'tacru.fcm_project_id' => 'synthetic-fcm-project',
            'tacru.route_preview_enabled' => true,
            'tacru.route_provider' => 'geoapify',
            'tacru.geoapify_api_key' => 'synthetic-geoapify-key',
        ]);
        Http::preventStrayRequests();

        $exitCode = Artisan::call('tacru:dependency-health');
        $output = Artisan::output();

        $this->assertSame(0, $exitCode);
        $this->assertStringContainsString('"mode": "configuration_only"', $output);
        $this->assertStringContainsString('"network_requests": false', $output);
        $this->assertStringContainsString('"status": "configuration_present"', $output);
        $this->assertStringNotContainsString('synthetic-publishable-key', $output);
        $this->assertStringNotContainsString('synthetic-server-secret', $output);
        $this->assertStringNotContainsString('synthetic-geoapify-key', $output);
        $this->assertStringNotContainsString('synthetic-project.example.test', $output);
        Http::assertNothingSent();
    }

    public function test_missing_configuration_is_reported_without_claiming_provider_health(): void
    {
        config([
            'tacru.supabase_url' => '',
            'tacru.supabase_publishable_key' => '',
            'tacru.supabase_secret_key' => '',
            'tacru.fcm_project_id' => '',
            'tacru.route_preview_enabled' => false,
        ]);
        Http::preventStrayRequests();

        $exitCode = Artisan::call('tacru:dependency-health');
        $output = Artisan::output();

        $this->assertSame(0, $exitCode);
        $this->assertStringContainsString('"supabase_auth"', $output);
        $this->assertStringContainsString('"status": "not_configured"', $output);
        $this->assertStringContainsString('"status": "disabled"', $output);
        Http::assertNothingSent();
    }

    public function test_live_mode_checks_only_supabase_auth_and_the_data_free_database_rpc(): void
    {
        config([
            'tacru.supabase_url' => 'https://synthetic-project.example.test',
            'tacru.supabase_publishable_key' => 'synthetic-publishable-key',
            'tacru.supabase_secret_key' => '',
            'tacru.fcm_project_id' => '',
            'tacru.route_preview_enabled' => false,
        ]);
        Http::fake([
            'https://synthetic-project.example.test/auth/v1/health' => Http::response(['message' => 'ok'], 200),
            'https://synthetic-project.example.test/rest/v1/rpc/api_dependency_health' => Http::response('"tacresq_database_ok"', 200),
        ]);

        $exitCode = Artisan::call('tacru:dependency-health', ['--live' => true]);
        $output = Artisan::output();
        $report = json_decode($output, true);

        $this->assertSame(0, $exitCode);
        $this->assertIsArray($report);
        $this->assertSame('live_read_only', $report['mode']);
        $this->assertTrue($report['network_requests']);
        $this->assertTrue($report['database_queries']);
        $this->assertFalse($report['mutations']);
        $this->assertSame('healthy', $report['components']['supabase_auth']['status']);
        $this->assertSame('healthy', $report['components']['supabase_rpc']['status']);
        $this->assertSame('not_configured', $report['components']['fcm']['status']);
        $this->assertSame('disabled', $report['components']['routing']['status']);

        Http::assertSentCount(2);
        Http::assertSent(fn ($request) => $request->url() === 'https://synthetic-project.example.test/auth/v1/health'
            && $request->hasHeader('apikey', 'synthetic-publishable-key'));
        Http::assertSent(fn ($request) => $request->url() === 'https://synthetic-project.example.test/rest/v1/rpc/api_dependency_health'
            && $request->hasHeader('apikey', 'synthetic-publishable-key')
            && $request->body() === '{}');
        $this->assertStringNotContainsString('synthetic-publishable-key', $output);
        $this->assertStringNotContainsString('synthetic-project.example.test', $output);
    }

    public function test_live_mode_sanitizes_unavailable_and_invalid_dependency_responses(): void
    {
        config([
            'tacru.supabase_url' => 'https://synthetic-project.example.test',
            'tacru.supabase_publishable_key' => 'synthetic-publishable-key',
            'tacru.route_preview_enabled' => false,
        ]);
        Http::fake([
            'https://synthetic-project.example.test/auth/v1/health' => Http::response(['message' => 'private upstream details'], 503),
            'https://synthetic-project.example.test/rest/v1/rpc/api_dependency_health' => Http::response('"unexpected-provider-body"', 200),
        ]);

        $exitCode = Artisan::call('tacru:dependency-health', ['--live' => true]);
        $output = Artisan::output();
        $report = json_decode($output, true);

        $this->assertSame(0, $exitCode);
        $this->assertSame('unavailable', $report['components']['supabase_auth']['status']);
        $this->assertSame('unavailable', $report['components']['supabase_rpc']['status']);
        $this->assertStringNotContainsString('private upstream details', $output);
        $this->assertStringNotContainsString('unexpected-provider-body', $output);
        Http::assertSentCount(2);
    }

    public function test_live_mode_without_supabase_configuration_makes_no_network_requests(): void
    {
        config([
            'tacru.supabase_url' => '',
            'tacru.supabase_publishable_key' => '',
            'tacru.route_preview_enabled' => false,
        ]);
        Http::preventStrayRequests();

        $exitCode = Artisan::call('tacru:dependency-health', ['--live' => true]);
        $report = json_decode(Artisan::output(), true);

        $this->assertSame(0, $exitCode);
        $this->assertFalse($report['network_requests']);
        $this->assertFalse($report['database_queries']);
        $this->assertSame('not_configured', $report['components']['supabase_auth']['status']);
        $this->assertSame('not_configured', $report['components']['supabase_rpc']['status']);
        Http::assertNothingSent();
    }

}
