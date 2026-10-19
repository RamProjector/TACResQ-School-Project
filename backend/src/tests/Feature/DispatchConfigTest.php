<?php

namespace Tests\Feature;

use Tests\TestCase;

class DispatchConfigTest extends TestCase
{
    public function test_runtime_config_returns_only_public_supabase_settings_without_caching(): void
    {
        $supabaseUrl = getenv('SUPABASE_URL');
        $publishableKey = getenv('SUPABASE_PUBLISHABLE_KEY');

        $this->assertIsString($supabaseUrl);
        $this->assertNotSame('', $supabaseUrl);
        $this->assertIsString($publishableKey);
        $this->assertNotSame('', $publishableKey);

        $this->getJson('/dispatch/config.json')
            ->assertOk()
            ->assertHeader('Cache-Control', 'no-store, private')
            ->assertExactJson([
                'supabaseUrl' => $supabaseUrl,
                'supabasePublishableKey' => $publishableKey,
            ]);
    }
}
