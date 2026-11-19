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

        $this->artisan('tacru:dependency-health')
            ->expectsOutputToContain('"mode": "configuration_only"')
            ->expectsOutputToContain('"network_requests": false')
            ->expectsOutputToContain('"status": "configuration_present"')
            ->assertExitCode(0);

        $output = Artisan::output();
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

        $this->artisan('tacru:dependency-health')
            ->expectsOutputToContain('"supabase_auth"')
            ->expectsOutputToContain('"status": "not_configured"')
            ->expectsOutputToContain('"status": "disabled"')
            ->assertExitCode(0);

        Http::assertNothingSent();
    }
}
