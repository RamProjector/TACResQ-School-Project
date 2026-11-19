<?php

namespace Tests\Feature;

use App\Models\Incident;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

class OwnedBackendIncidentCreationTest extends TestCase
{
    use RefreshDatabase;

    public function test_requester_can_submit_locationless_sos_and_replay_is_idempotent(): void
    {
        $requester = $this->requester('sos-replay@example.test');
        Sanctum::actingAs($requester);
        $requestId = '4f0eebcc-3d52-4f0f-986b-9e91ec70ab27';
        $body = [
            'client_request_id' => $requestId,
            'type' => 'medical',
            'description' => 'Urgent assistance needed',
        ];

        $first = $this->postJson('/api/incidents', $body);
        $first->assertCreated()->assertJsonPath('status', 'pending');
        $incidentId = $first->json('id');

        $replay = $this->postJson('/api/incidents', $body);
        $replay->assertOk()->assertJsonPath('id', $incidentId);

        $this->assertSame(1, Incident::where('requester_id', $requester->id)->count());
        $this->assertDatabaseCount('incident_status_logs', 1);
        $this->assertDatabaseCount('incident_submission_keys', 1);
        $this->assertDatabaseHas('incidents', [
            'id' => $incidentId,
            'requester_id' => $requester->id,
            'latitude' => null,
            'longitude' => null,
            'location_source' => 'none',
        ]);
    }

    public function test_requester_cannot_create_a_second_active_sos(): void
    {
        $requester = $this->requester('sos-single-active@example.test');
        Sanctum::actingAs($requester);

        $this->postJson('/api/incidents', [
            'client_request_id' => '1c1b8b8c-9e7a-4c61-bb66-04ed2f6aef11',
            'type' => 'medical',
        ])->assertCreated();

        $this->postJson('/api/incidents', [
            'client_request_id' => 'a3db5d13-c1ac-4e82-9165-8e72b62db2c2',
            'type' => 'fire',
        ])->assertStatus(409);

        $this->assertSame(1, Incident::where('requester_id', $requester->id)->count());
    }

    public function test_idempotency_key_cannot_be_reused_for_different_details(): void
    {
        $requester = $this->requester('sos-key-reuse@example.test');
        Sanctum::actingAs($requester);
        $requestId = 'd82e9fd2-6d6d-4a2e-a72c-9a5e4d7c6d40';

        $this->postJson('/api/incidents', [
            'client_request_id' => $requestId,
            'type' => 'medical',
        ])->assertCreated();

        $this->postJson('/api/incidents', [
            'client_request_id' => $requestId,
            'type' => 'fire',
        ])->assertStatus(409);

        $this->assertSame(1, Incident::where('requester_id', $requester->id)->count());
    }

    private function requester(string $email): User
    {
        return User::create([
            'name' => 'Test Requester',
            'email' => $email,
            'password' => 'test-only-password',
            'role' => 'requester',
        ]);
    }
}
