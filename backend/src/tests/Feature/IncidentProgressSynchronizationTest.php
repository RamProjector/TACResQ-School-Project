<?php

namespace Tests\Feature;

use App\Models\Incident;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

class IncidentProgressSynchronizationTest extends TestCase
{
    use RefreshDatabase;

    public function test_dispatcher_progress_is_synchronized_to_every_response_team_member(): void
    {
        $requester = $this->user('progress-requester@example.test', 'requester');
        $dispatcher = $this->user('progress-dispatcher@example.test', 'dispatcher');
        $lead = $this->user('progress-lead@example.test', 'responder', 'assigned');
        $teammate = $this->user('progress-teammate@example.test', 'driver', 'assigned');
        $unrelatedLead = $this->user('progress-unrelated-lead@example.test', 'responder', 'en_route');
        $unrelatedRequester = $this->user('progress-unrelated-requester@example.test', 'requester');
        $unrelatedIncident = Incident::create([
            'requester_id' => $unrelatedRequester->id,
            'type' => 'medical',
            'description' => 'Separate active incident must not be synchronized',
            'status' => 'en_route',
            'assigned_to' => $unrelatedLead->id,
        ]);
        DB::table('incident_assignments')->insert([
            'incident_id' => $unrelatedIncident->id,
            'user_id' => $unrelatedLead->id,
            'assigned_by' => $dispatcher->id,
            'assigned_at' => now(),
            'responder_status' => 'en_route',
            'status_updated_at' => now(),
        ]);
        $incident = $this->assignedIncident($requester, $lead, $dispatcher, [$lead, $teammate]);

        Sanctum::actingAs($dispatcher);

        foreach (['en_route', 'on_scene', 'transporting', 'resolved'] as $status) {
            $this->patchJson('/api/incidents/'.$incident->id.'/status', [
                'status' => $status,
            ])->assertOk()->assertJsonPath('status', $status);

            $this->assertDatabaseHas('incidents', [
                'id' => $incident->id,
                'status' => $status,
            ]);
            foreach ([$lead, $teammate] as $unit) {
                $this->assertDatabaseHas('incident_assignments', [
                    'incident_id' => $incident->id,
                    'user_id' => $unit->id,
                    'responder_status' => $status,
                ]);
                $this->assertDatabaseHas('users', [
                    'id' => $unit->id,
                    'duty_status' => $status === 'resolved' ? 'available' : $status,
                ]);
            }
        }

        // Progress changes must never spill over to a different active incident.
        $this->assertDatabaseHas('incidents', [
            'id' => $unrelatedIncident->id,
            'status' => 'en_route',
            'assigned_to' => $unrelatedLead->id,
        ]);
        $this->assertDatabaseHas('users', [
            'id' => $unrelatedLead->id,
            'duty_status' => 'en_route',
        ]);
        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $unrelatedIncident->id,
            'user_id' => $unrelatedLead->id,
            'responder_status' => 'en_route',
        ]);

        $this->assertDatabaseCount('incident_status_logs', 4);
        $this->assertNotNull(DB::table('incident_assignments')
            ->where('incident_id', $incident->id)
            ->where('user_id', $teammate->id)
            ->value('status_updated_at'));
    }

    public function test_legacy_assigned_lead_is_synchronized_even_without_a_pivot_row(): void
    {
        $requester = $this->user('legacy-lead-requester@example.test', 'requester');
        $dispatcher = $this->user('legacy-lead-dispatcher@example.test', 'dispatcher');
        $lead = $this->user('legacy-lead-unit@example.test', 'responder', 'assigned');
        $teammate = $this->user('legacy-lead-teammate@example.test', 'driver', 'assigned');

        // Older assignments may have only incidents.assigned_to and no lead pivot row.
        $incident = $this->assignedIncident($requester, $lead, $dispatcher, [$teammate]);

        Sanctum::actingAs($dispatcher);
        $this->patchJson('/api/incidents/'.$incident->id.'/status', [
            'status' => 'en_route',
        ])->assertOk()->assertJsonPath('status', 'en_route');

        $this->assertDatabaseHas('users', ['id' => $lead->id, 'duty_status' => 'en_route']);
        $this->assertDatabaseHas('users', ['id' => $teammate->id, 'duty_status' => 'en_route']);
        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $incident->id,
            'user_id' => $teammate->id,
            'responder_status' => 'en_route',
        ]);
        $this->assertDatabaseCount('incident_status_logs', 1);
    }

    public function test_lead_and_team_member_cannot_change_duty_while_incident_is_active(): void
    {
        $requester = $this->user('duty-guard-requester@example.test', 'requester');
        $dispatcher = $this->user('duty-guard-dispatcher@example.test', 'dispatcher');
        $lead = $this->user('duty-guard-lead@example.test', 'responder', 'en_route');
        $teammate = $this->user('duty-guard-teammate@example.test', 'driver', 'en_route');
        $incident = $this->assignedIncident($requester, $lead, $dispatcher, [$lead, $teammate]);
        DB::table('incidents')->where('id', $incident->id)->update(['status' => 'en_route']);
        DB::table('incident_assignments')->where('incident_id', $incident->id)->update([
            'responder_status' => 'en_route',
        ]);

        Sanctum::actingAs($lead);
        $this->patchJson('/api/me/duty-status', ['duty_status' => 'off_duty'])
            ->assertUnprocessable();

        Sanctum::actingAs($teammate);
        $this->patchJson('/api/me/duty-status', ['duty_status' => 'off_duty'])
            ->assertUnprocessable();

        $this->assertDatabaseHas('users', ['id' => $lead->id, 'duty_status' => 'en_route']);
        $this->assertDatabaseHas('users', ['id' => $teammate->id, 'duty_status' => 'en_route']);
    }

    public function test_resolved_incident_history_does_not_block_team_members_from_going_off_duty(): void
    {
        $requester = $this->user('resolved-duty-requester@example.test', 'requester');
        $dispatcher = $this->user('resolved-duty-dispatcher@example.test', 'dispatcher');
        $lead = $this->user('resolved-duty-lead@example.test', 'responder', 'available');
        $teammate = $this->user('resolved-duty-teammate@example.test', 'driver', 'available');
        $incident = $this->assignedIncident($requester, $lead, $dispatcher, [$lead, $teammate]);

        // Keep the historical lead and team membership rows while closing the incident.
        DB::table('incidents')->where('id', $incident->id)->update(['status' => 'resolved']);
        DB::table('incident_assignments')->where('incident_id', $incident->id)->update([
            'responder_status' => 'resolved',
        ]);

        Sanctum::actingAs($lead);
        $this->patchJson('/api/me/duty-status', ['duty_status' => 'off_duty'])->assertOk()
            ->assertJsonPath('duty_status', 'off_duty');

        Sanctum::actingAs($teammate);
        $this->patchJson('/api/me/duty-status', ['duty_status' => 'off_duty'])->assertOk()
            ->assertJsonPath('duty_status', 'off_duty');

        $this->assertDatabaseHas('users', ['id' => $lead->id, 'duty_status' => 'off_duty']);
        $this->assertDatabaseHas('users', ['id' => $teammate->id, 'duty_status' => 'off_duty']);
        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $incident->id,
            'user_id' => $teammate->id,
            'responder_status' => 'resolved',
        ]);
    }

    public function test_resolved_incident_is_terminal_and_cannot_be_reopened(): void
    {
        $requester = $this->user('progress-terminal-requester@example.test', 'requester');
        $dispatcher = $this->user('progress-terminal-dispatcher@example.test', 'dispatcher');
        $lead = $this->user('progress-terminal-lead@example.test', 'responder', 'available');
        $teammate = $this->user('progress-terminal-teammate@example.test', 'driver', 'available');
        $incident = $this->assignedIncident($requester, $lead, $dispatcher, [$lead, $teammate]);

        DB::table('incidents')->where('id', $incident->id)->update(['status' => 'resolved']);
        DB::table('incident_assignments')->where('incident_id', $incident->id)->update([
            'responder_status' => 'resolved',
        ]);

        Sanctum::actingAs($dispatcher);
        $this->patchJson('/api/incidents/'.$incident->id.'/status', [
            'status' => 'en_route',
        ])->assertUnprocessable();

        $this->assertDatabaseHas('incidents', ['id' => $incident->id, 'status' => 'resolved']);
        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $incident->id,
            'user_id' => $lead->id,
            'responder_status' => 'resolved',
        ]);
        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $incident->id,
            'user_id' => $teammate->id,
            'responder_status' => 'resolved',
        ]);
        $this->assertDatabaseHas('users', ['id' => $lead->id, 'duty_status' => 'available']);
        $this->assertDatabaseHas('users', ['id' => $teammate->id, 'duty_status' => 'available']);
        $this->assertDatabaseCount('incident_status_logs', 0);
    }

    public function test_dispatcher_cannot_advance_an_incident_without_a_lead_assignment(): void
    {
        $requester = $this->user('progress-unassigned-requester@example.test', 'requester');
        $dispatcher = $this->user('progress-unassigned-dispatcher@example.test', 'dispatcher');
        $incident = Incident::create([
            'requester_id' => $requester->id,
            'type' => 'medical',
            'description' => 'Incident without a response lead',
            'status' => 'pending',
            'assigned_to' => null,
        ]);

        Sanctum::actingAs($dispatcher);
        $this->patchJson('/api/incidents/'.$incident->id.'/status', [
            'status' => 'en_route',
        ])->assertUnprocessable();

        $this->assertDatabaseHas('incidents', [
            'id' => $incident->id,
            'status' => 'pending',
            'assigned_to' => null,
        ]);
        $this->assertDatabaseCount('incident_status_logs', 0);
        $this->assertDatabaseCount('incident_assignments', 0);
    }

    public function test_dispatcher_cannot_advance_an_incident_with_a_non_field_lead(): void
    {
        $requester = $this->user('progress-invalid-lead-requester@example.test', 'requester');
        $dispatcher = $this->user('progress-invalid-lead-dispatcher@example.test', 'dispatcher');
        $incident = Incident::create([
            'requester_id' => $requester->id,
            'type' => 'medical',
            'description' => 'Invalid legacy lead must not receive responder duty updates',
            'status' => 'assigned',
            'assigned_to' => $requester->id,
        ]);

        Sanctum::actingAs($dispatcher);
        $this->patchJson('/api/incidents/'.$incident->id.'/status', [
            'status' => 'en_route',
        ])->assertUnprocessable();

        $this->assertDatabaseHas('incidents', [
            'id' => $incident->id,
            'status' => 'assigned',
            'assigned_to' => $requester->id,
        ]);
        $this->assertDatabaseHas('users', [
            'id' => $requester->id,
            'role' => 'requester',
            'duty_status' => null,
        ]);
        $this->assertDatabaseCount('incident_status_logs', 0);
    }

    public function test_dispatcher_cannot_advance_an_incident_with_a_non_field_team_member(): void
    {
        $requester = $this->user('progress-invalid-team-requester@example.test', 'requester');
        $dispatcher = $this->user('progress-invalid-team-dispatcher@example.test', 'dispatcher');
        $lead = $this->user('progress-invalid-team-lead@example.test', 'responder', 'assigned');
        $incident = $this->assignedIncident($requester, $lead, $dispatcher, [$lead, $requester]);

        Sanctum::actingAs($dispatcher);
        $this->patchJson('/api/incidents/'.$incident->id.'/status', [
            'status' => 'en_route',
        ])->assertUnprocessable();

        $this->assertDatabaseHas('incidents', [
            'id' => $incident->id,
            'status' => 'assigned',
            'assigned_to' => $lead->id,
        ]);
        $this->assertDatabaseHas('users', [
            'id' => $requester->id,
            'role' => 'requester',
            'duty_status' => null,
        ]);
        $this->assertDatabaseHas('users', [
            'id' => $lead->id,
            'duty_status' => 'assigned',
        ]);
        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $incident->id,
            'user_id' => $requester->id,
            'responder_status' => 'assigned',
        ]);
        $this->assertDatabaseCount('incident_status_logs', 0);
    }

    public function test_dispatcher_can_resolve_an_incident_directly_from_on_scene(): void
    {
        $requester = $this->user('progress-direct-resolve-requester@example.test', 'requester');
        $dispatcher = $this->user('progress-direct-resolve-dispatcher@example.test', 'dispatcher');
        $lead = $this->user('progress-direct-resolve-lead@example.test', 'responder', 'on_scene');
        $teammate = $this->user('progress-direct-resolve-teammate@example.test', 'driver', 'on_scene');
        $incident = $this->assignedIncident($requester, $lead, $dispatcher, [$lead, $teammate]);
        DB::table('incidents')->where('id', $incident->id)->update(['status' => 'on_scene']);
        DB::table('incident_assignments')->where('incident_id', $incident->id)->update([
            'responder_status' => 'on_scene',
        ]);

        Sanctum::actingAs($dispatcher);
        $this->patchJson('/api/incidents/'.$incident->id.'/status', [
            'status' => 'resolved',
        ])->assertOk()->assertJsonPath('status', 'resolved');

        $this->assertDatabaseHas('incidents', ['id' => $incident->id, 'status' => 'resolved']);
        foreach ([$lead, $teammate] as $unit) {
            $this->assertDatabaseHas('users', [
                'id' => $unit->id,
                'duty_status' => 'available',
            ]);
            $this->assertDatabaseHas('incident_assignments', [
                'incident_id' => $incident->id,
                'user_id' => $unit->id,
                'responder_status' => 'resolved',
            ]);
        }
        $this->assertDatabaseCount('incident_status_logs', 1);
    }

    public function test_dispatcher_cannot_submit_unsupported_incident_progress_values(): void
    {
        $requester = $this->user('progress-invalid-requester@example.test', 'requester');
        $dispatcher = $this->user('progress-invalid-dispatcher@example.test', 'dispatcher');
        $lead = $this->user('progress-invalid-lead@example.test', 'responder', 'assigned');
        $incident = $this->assignedIncident($requester, $lead, $dispatcher, [$lead]);

        Sanctum::actingAs($dispatcher);
        foreach (['pending', 'assigned', 'cancelled', 'unknown'] as $status) {
            $this->patchJson('/api/incidents/'.$incident->id.'/status', [
                'status' => $status,
            ])->assertUnprocessable();
        }

        $this->assertDatabaseHas('incidents', [
            'id' => $incident->id,
            'status' => 'assigned',
            'assigned_to' => $lead->id,
        ]);
        $this->assertDatabaseHas('users', ['id' => $lead->id, 'duty_status' => 'assigned']);
        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $incident->id,
            'user_id' => $lead->id,
            'responder_status' => 'assigned',
        ]);
        $this->assertDatabaseCount('incident_status_logs', 0);
    }

    public function test_dispatcher_cannot_move_incident_progress_backwards(): void
    {
        $requester = $this->user('progress-backward-requester@example.test', 'requester');
        $dispatcher = $this->user('progress-backward-dispatcher@example.test', 'dispatcher');
        $lead = $this->user('progress-backward-lead@example.test', 'responder', 'on_scene');
        $incident = $this->assignedIncident($requester, $lead, $dispatcher, [$lead]);

        // The incident has already reached the scene; an earlier state is not a valid transition.
        DB::table('incidents')->where('id', $incident->id)->update(['status' => 'on_scene']);
        DB::table('incident_assignments')->where('incident_id', $incident->id)->update([
            'responder_status' => 'on_scene',
        ]);

        Sanctum::actingAs($dispatcher);
        $this->patchJson('/api/incidents/'.$incident->id.'/status', [
            'status' => 'en_route',
        ])->assertUnprocessable();

        $this->assertDatabaseHas('incidents', ['id' => $incident->id, 'status' => 'on_scene']);
        $this->assertDatabaseHas('users', ['id' => $lead->id, 'duty_status' => 'on_scene']);
        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $incident->id,
            'user_id' => $lead->id,
            'responder_status' => 'on_scene',
        ]);
        $this->assertDatabaseCount('incident_status_logs', 0);
    }

    public function test_dispatcher_cannot_skip_incident_progress_states(): void
    {
        $requester = $this->user('progress-skip-requester@example.test', 'requester');
        $dispatcher = $this->user('progress-skip-dispatcher@example.test', 'dispatcher');
        $lead = $this->user('progress-skip-lead@example.test', 'responder', 'assigned');
        $incident = $this->assignedIncident($requester, $lead, $dispatcher, [$lead]);

        Sanctum::actingAs($dispatcher);
        $this->patchJson('/api/incidents/'.$incident->id.'/status', [
            'status' => 'on_scene',
        ])->assertUnprocessable();

        $this->assertDatabaseHas('incidents', ['id' => $incident->id, 'status' => 'assigned']);
        $this->assertDatabaseHas('users', ['id' => $lead->id, 'duty_status' => 'assigned']);
        $this->assertDatabaseHas('incident_assignments', [
            'incident_id' => $incident->id,
            'user_id' => $lead->id,
            'responder_status' => 'assigned',
        ]);
        $this->assertDatabaseCount('incident_status_logs', 0);
    }

    /**
     * @param array<int, User> $team
     */
    private function assignedIncident(User $requester, User $lead, User $dispatcher, array $team): Incident
    {
        $incident = Incident::create([
            'requester_id' => $requester->id,
            'type' => 'medical',
            'description' => 'Incident progress synchronization test',
            'status' => 'assigned',
            'assigned_to' => $lead->id,
        ]);

        foreach ($team as $unit) {
            DB::table('incident_assignments')->insert([
                'incident_id' => $incident->id,
                'user_id' => $unit->id,
                'assigned_by' => $dispatcher->id,
                'assigned_at' => now(),
                'responder_status' => 'assigned',
                'status_updated_at' => now(),
            ]);
        }

        return $incident;
    }

    private function user(string $email, string $role, ?string $dutyStatus = null): User
    {
        return User::create([
            'name' => 'Progress Test '.ucfirst($role),
            'email' => $email,
            'password' => 'not-a-real-password',
            'role' => $role,
            'duty_status' => $dutyStatus,
        ]);
    }
}
