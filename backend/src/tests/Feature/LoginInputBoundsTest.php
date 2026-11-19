<?php

namespace Tests\Feature;

use Tests\TestCase;

class LoginInputBoundsTest extends TestCase
{
    protected function setUp(): void
    {
        parent::setUp();
        Cache::flush();
    }

    public function test_login_rejects_email_longer_than_supported_limit_before_authentication(): void
    {
        $this->postJson('/api/login', [
            'email' => str_repeat('a', 250).'@example.test',
            'password' => 'not-a-real-password',
        ])->assertUnprocessable()
            ->assertJsonValidationErrors(['email']);
    }

    public function test_login_rejects_oversized_password_before_hash_verification(): void
    {
        $this->postJson('/api/login', [
            'email' => 'user@example.test',
            'password' => str_repeat('x', 73),
        ])->assertUnprocessable()
            ->assertJsonValidationErrors(['password']);
    }

    public function test_login_throttle_returns_bounded_retry_after_and_no_store_headers(): void
    {
        $credentials = [
            'email' => 'throttle-target@example.test',
            'password' => 'synthetic-invalid-password',
        ];

        for ($attempt = 0; $attempt < 5; $attempt++) {
            $this->postJson('/api/login', $credentials)->assertUnprocessable();
        }

        $response = $this->postJson('/api/login', $credentials);

        $response->assertTooManyRequests();
        $retryAfter = (int) $response->headers->get('Retry-After', '0');
        $this->assertGreaterThan(0, $retryAfter);
        $this->assertLessThanOrEqual(900, $retryAfter);
        $this->assertSame('no-store, private', $response->headers->get('Cache-Control'));
        $this->assertStringNotContainsString('throttle-target@example.test', $response->getContent());
    }

    public function test_login_still_validates_required_credentials(): void
    {
        $this->postJson('/api/login', [])
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['email', 'password']);
    }
}
