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

        $this->assertDatabaseCount('incident_status_logs', 4);
        $this->assertNotNull(DB::table('incident_assignments')
            ->where('incident_id', $incident->id)
            ->where('user_id', $teammate->id)
            ->value('status_updated_at'));
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
