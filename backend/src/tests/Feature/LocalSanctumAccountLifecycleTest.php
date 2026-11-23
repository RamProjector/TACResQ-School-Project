<?php

namespace Tests\\Feature;

use App\\Models\\User;
use Illuminate\\Foundation\\Testing\\RefreshDatabase;
use Illuminate\\Support\\Facades\\DB;
use Laravel\\Sanctum\\Sanctum;
use Tests\\TestCase;

class LocalSanctumAccountLifecycleTest extends TestCase
{
    use RefreshDatabase;

    private function createLocalUser(): User
    {
        return User::create([
            'name' => 'Lifecycle Test User',
            'email' => 'lifecycle-test@example.test',
            'password' => 'synthetic-test-password',
            'role' => 'requester',
        ]);
    }

    public function test_existing_local_accounts_remain_active_by_default(): void
    {
        $user = $this->createLocalUser();
        Sanctum::actingAs($user);

        $this->getJson('/api/me')->assertOk()->assertJsonPath('id', $user->id);
        $this->assertFalse($user->fresh()->is_suspended);
    }

    public function test_suspended_local_account_cannot_access_protected_sanctum_routes(): void
    {
        $user = $this->createLocalUser();
        DB::table('users')->where('id', $user->id)->update([
            'is_suspended' => true,
            'suspended_at' => now(),
            'suspension_reason' => 'Synthetic test suspension',
        ]);
        Sanctum::actingAs($user->fresh());

        $this->getJson('/api/me')->assertForbidden()
            ->assertExactJson([
                'message' => 'This local TACResQ account is suspended. Contact an administrator for assistance.',
            ])
            ->assertHeader('Cache-Control', 'no-store, private');
    }

    public function test_suspended_local_account_can_still_revoke_its_current_token(): void
    {
        $user = $this->createLocalUser();
        DB::table('users')->where('id', $user->id)->update(['is_suspended' => true]);
        Sanctum::actingAs($user->fresh());

        $this->postJson('/api/logout')->assertNoContent();
    }
}
