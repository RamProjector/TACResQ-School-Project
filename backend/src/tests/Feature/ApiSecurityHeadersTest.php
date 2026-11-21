<?php

namespace Tests\Feature;

use Tests\TestCase;

class ApiSecurityHeadersTest extends TestCase
{
    public function test_api_responses_are_non_cacheable_and_include_privacy_headers(): void
    {
        $this->getJson('/api/route-preview/provider')
            // The routing rollout kill switch intentionally runs before auth.
            ->assertServiceUnavailable()
            ->assertHeader('Cache-Control', 'no-store, private')
            ->assertHeader('Pragma', 'no-cache')
            ->assertHeader('Expires', '0')
            ->assertHeader('X-Content-Type-Options', 'nosniff')
            ->assertHeader('Referrer-Policy', 'no-referrer');
    }

    public function test_unauthenticated_api_responses_are_non_cacheable(): void
    {
        config(['tacru.route_preview_enabled' => true]);

        $this->getJson('/api/route-preview/provider')
            ->assertUnauthorized()
            ->assertHeader('Cache-Control', 'no-store, private')
            ->assertHeader('Pragma', 'no-cache')
            ->assertHeader('Expires', '0')
            ->assertHeader('X-Content-Type-Options', 'nosniff')
            ->assertHeader('Referrer-Policy', 'no-referrer');
    }

    public function test_non_api_portal_responses_are_not_given_api_cache_policy(): void
    {
        $response = $this->get('/');

        $this->assertNotSame('no-store, private', $response->headers->get('Cache-Control'));
        $response->assertRedirect('/admin/');
    }
}
