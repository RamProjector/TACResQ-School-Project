<?php

namespace Tests\Feature;

use Tests\TestCase;

class LoginInputBoundsTest extends TestCase
{
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

    public function test_login_still_validates_required_credentials(): void
    {
        $this->postJson('/api/login', [])
            ->assertUnprocessable()
            ->assertJsonValidationErrors(['email', 'password']);
    }
}
