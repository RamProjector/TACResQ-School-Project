<?php

namespace Tests\Feature;

use App\Models\Incident;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

class IncidentAssignmentWorkflowTest extends TestCase
{
    use RefreshDatabase;

    public function test_dispatcher_assignment_persists_team_membership_and_marks_unit_busy(): void
    {
        $requester = $this->user('assignment-requester@example.test', 'requester');
        $dispatcher = $this->user('assignment-dispatcher@example.test', 'dispatcher');
        $responder = $this->user('assignment-responder@example.test', 'responder', 'available');
        $incident = $this->incident($requester);

        Sanctum::actingAs($dispatcher);

        $response = $this->postJson('/api/incidents/'.$incident->id.'/assign', [
            'user_id' => $responder->id,
        ]);

        $response->assertOk()
            ->assertJsonPath('id', $incident->id)
            ->assertJsonPath('status', 'assigned')
            ->assertJsonPath('assigned_to', $responder->id);

        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $incident->id,
            'user_id' => $responder->id,
            'assigned_by' => $dispatcher->id,
            'responder_status' => 'assigned',
        ]);
        $this->assertDatabaseHas('users', [
            'id' => $responder->id,
            'duty_status' => 'assigned',
        ]);
        $this->assertDatabaseHas('incident_status_logs', [
            'incident_id' => $incident->id,
            'status' => 'assigned',
            'changed_by' => $dispatcher->id,
        ]);

        $this->assertTrue(
            $incident->fresh()->responseTeam()->whereKey($responder->id)->exists()
        );
        $this->assertTrue(
            $responder->fresh()->responseIncidents()->whereKey($incident->id)->exists()
        );
    }

    public function test_dispatcher_cannot_assign_unavailable_or_non_field_unit_accounts(): void
    {
        $requester = $this->user('assignment-reject-requester@example.test', 'requester');
        $dispatcher = $this->user('assignment-reject-dispatcher@example.test', 'dispatcher');
        $offDutyResponder = $this->user('assignment-off-duty@example.test', 'responder', 'off_duty');
        $requesterAccount = $this->user('assignment-not-unit@example.test', 'requester');
        $incident = $this->incident($requester);

        Sanctum::actingAs($dispatcher);

        $this->postJson('/api/incidents/'.$incident->id.'/assign', [
            'user_id' => $offDutyResponder->id,
        ])->assertUnprocessable();

        $this->postJson('/api/incidents/'.$incident->id.'/assign', [
            'user_id' => $requesterAccount->id,
        ])->assertUnprocessable();

        $this->assertDatabaseCount('incident_assignments', 0);
        $this->assertDatabaseCount('incident_status_logs', 0);
        $this->assertDatabaseHas('incidents', [
            'id' => $incident->id,
            'status' => 'pending',
            'assigned_to' => null,
        ]);
        $this->assertDatabaseHas('users', [
            'id' => $offDutyResponder->id,
            'duty_status' => 'off_duty',
        ]);
    }

    public function test_dispatcher_cannot_assign_a_unit_that_is_already_on_another_active_incident(): void
    {
        $requester = $this->user('assignment-busy-requester@example.test', 'requester');
        $dispatcher = $this->user('assignment-busy-dispatcher@example.test', 'dispatcher');
        // Active incident membership remains authoritative if duty status is stale.
        $responder = $this->user('assignment-busy-unit@example.test', 'responder', 'available');
        $activeIncident = Incident::create([
            'requester_id' => $requester->id,
            'type' => 'medical',
            'description' => 'Existing active assignment',
            'status' => 'en_route',
            'assigned_to' => $responder->id,
        ]);
        DB::table('incident_assignments')->insert([
            'incident_id' => $activeIncident->id,
            'user_id' => $responder->id,
            'assigned_by' => $dispatcher->id,
            'assigned_at' => now(),
            'responder_status' => 'en_route',
            'status_updated_at' => now(),
        ]);
        $newIncident = $this->incident($requester);

        Sanctum::actingAs($dispatcher);
        $this->postJson('/api/incidents/'.$newIncident->id.'/assign', [
            'user_id' => $responder->id,
        ])->assertUnprocessable();

        $this->assertDatabaseHas('incidents', [
            'id' => $newIncident->id,
            'status' => 'pending',
            'assigned_to' => null,
        ]);
        $this->assertDatabaseCount('incident_assignments', 1);
        $this->assertDatabaseHas('users', [
            'id' => $responder->id,
            'duty_status' => 'available',
        ]);
    }

    public function test_dispatcher_cannot_assign_a_unit_that_is_only_a_team_member_on_another_active_incident(): void
    {
        $requester = $this->user('assignment-team-only-requester@example.test', 'requester');
        $dispatcher = $this->user('assignment-team-only-dispatcher@example.test', 'dispatcher');
        $lead = $this->user('assignment-team-only-lead@example.test', 'responder', 'assigned');
        // The member's legacy duty flag is stale; pivot membership is authoritative.
        $teamMember = $this->user('assignment-team-only-member@example.test', 'driver', 'available');
        $activeIncident = Incident::create([
            'requester_id' => $requester->id,
            'type' => 'medical',
            'description' => 'Active team assignment',
            'status' => 'on_scene',
            'assigned_to' => $lead->id,
        ]);
        DB::table('incident_assignments')->insert([
            'incident_id' => $activeIncident->id,
            'user_id' => $lead->id,
            'assigned_by' => $dispatcher->id,
            'assigned_at' => now(),
            'responder_status' => 'on_scene',
            'status_updated_at' => now(),
        ]);
        DB::table('incident_assignments')->insert([
            'incident_id' => $activeIncident->id,
            'user_id' => $teamMember->id,
            'assigned_by' => $dispatcher->id,
            'assigned_at' => now(),
            'responder_status' => 'on_scene',
            'status_updated_at' => now(),
        ]);
        $newIncident = $this->incident($requester);

        Sanctum::actingAs($dispatcher);
        $this->postJson('/api/incidents/'.$newIncident->id.'/assign', [
            'user_id' => $teamMember->id,
        ])->assertUnprocessable();

        $this->assertDatabaseHas('incidents', [
            'id' => $newIncident->id,
            'status' => 'pending',
            'assigned_to' => null,
        ]);
        $this->assertDatabaseCount('incident_assignments', 2);
        $this->assertDatabaseHas('users', [
            'id' => $teamMember->id,
            'duty_status' => 'available',
        ]);
    }

    public function test_resolved_incident_membership_does_not_block_a_new_assignment(): void
    {
        $requester = $this->user('assignment-reuse-requester@example.test', 'requester');
        $dispatcher = $this->user('assignment-reuse-dispatcher@example.test', 'dispatcher');
        $responder = $this->user('assignment-reuse-unit@example.test', 'responder', 'available');
        $resolvedIncident = Incident::create([
            'requester_id' => $requester->id,
            'type' => 'medical',
            'description' => 'Resolved historical assignment',
            'status' => 'resolved',
            'assigned_to' => $responder->id,
        ]);
        DB::table('incident_assignments')->insert([
            'incident_id' => $resolvedIncident->id,
            'user_id' => $responder->id,
            'assigned_by' => $dispatcher->id,
            'assigned_at' => now(),
            'responder_status' => 'resolved',
            'status_updated_at' => now(),
        ]);
        $newIncident = $this->incident($requester);

        Sanctum::actingAs($dispatcher);
        $this->postJson('/api/incidents/'.$newIncident->id.'/assign', [
            'user_id' => $responder->id,
        ])->assertOk()->assertJsonPath('status', 'assigned');

        $this->assertDatabaseCount('incident_assignments', 2);
        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $resolvedIncident->id,
            'user_id' => $responder->id,
            'responder_status' => 'resolved',
        ]);
        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $newIncident->id,
            'user_id' => $responder->id,
            'responder_status' => 'assigned',
        ]);
        $this->assertDatabaseHas('users', [
            'id' => $responder->id,
            'duty_status' => 'assigned',
        ]);
    }

    public function test_dispatcher_cannot_assign_the_same_incident_twice(): void
    {
        $requester = $this->user('assignment-repeat-requester@example.test', 'requester');
        $dispatcher = $this->user('assignment-repeat-dispatcher@example.test', 'dispatcher');
        $responder = $this->user('assignment-repeat-unit@example.test', 'responder', 'available');
        $replacement = $this->user('assignment-repeat-replacement@example.test', 'responder', 'available');
        $incident = $this->incident($requester);

        Sanctum::actingAs($dispatcher);
        $this->postJson('/api/incidents/'.$incident->id.'/assign', [
            'user_id' => $responder->id,
        ])->assertOk();

        // A different available unit must not silently replace the incident lead.
        $this->postJson('/api/incidents/'.$incident->id.'/assign', [
            'user_id' => $replacement->id,
        ])->assertUnprocessable();

        $this->assertDatabaseCount('incident_assignments', 1);
        $this->assertDatabaseCount('incident_status_logs', 1);
        $this->assertDatabaseHas('incidents', [
            'id' => $incident->id,
            'status' => 'assigned',
            'assigned_to' => $responder->id,
        ]);
        $this->assertDatabaseHas('users', [
            'id' => $replacement->id,
            'duty_status' => 'available',
        ]);
        $this->assertFalse(
            $incident->fresh()->responseTeam()->whereKey($replacement->id)->exists()
        );
    }

    private function user(string $email, string $role, ?string $dutyStatus = null): User
    {
        return User::create([
            'name' => 'Workflow Test '.ucfirst($role),
            'email' => $email,
            'password' => 'not-a-real-password',
            'role' => $role,
            'duty_status' => $dutyStatus,
        ]);
    }

    private function incident(User $requester): Incident
    {
        return Incident::create([
            'requester_id' => $requester->id,
            'type' => 'medical',
            'description' => 'Assignment workflow test',
            'status' => 'pending',
        ]);
    }
}
