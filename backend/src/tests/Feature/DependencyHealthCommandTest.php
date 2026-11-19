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
}
