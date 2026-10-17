<?php

namespace Tests\Feature;

use Tests\TestCase;

class DispatchConfigTest extends TestCase
{
    public function test_runtime_config_returns_only_public_supabase_settings_without_caching(): void
    {
        config([
            'tacru.supabase_url' => 'https://tacru-ci.example.test',
            'tacru.supabase_publishable_key' => 'sb_publishable_ci_placeholder',
        ]);

        $this->getJson('/dispatch/config.json')
            ->assertOk()
            ->assertHeader('Cache-Control', 'no-store, private')
            ->assertExactJson([
                'supabaseUrl' => 'https://tacru-ci.example.test',
                'supabasePublishableKey' => 'sb_publishable_ci_placeholder',
            ]);
    }
}
