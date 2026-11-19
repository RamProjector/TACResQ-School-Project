<?php

namespace Tests\Feature;

use App\Models\Incident;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Tests\TestCase;

class IncidentResponseTeamRelationsTest extends TestCase
{
    use RefreshDatabase;

    public function test_response_team_and_user_incident_relations_expose_the_same_assignment_metadata(): void
    {
        $requesterId = $this->insertUser('relation-requester@example.test', 'requester');
        $responderId = $this->insertUser('relation-responder@example.test', 'responder');
        $dispatcherId = $this->insertUser('relation-dispatcher@example.test', 'dispatcher');

        $incidentId = DB::table('incidents')->insertGetId([
            'requester_id' => $requesterId,
            'type' => 'medical',
            'description' => 'Relationship test incident',
            'status' => 'assigned',
            'created_at' => now(),
            'updated_at' => now(),
        ]);

        $assignedAt = now()->subMinutes(4)->startOfSecond();
        $statusUpdatedAt = now()->subMinute()->startOfSecond();

        DB::table('incident_assignments')->insert([
            'incident_id' => $incidentId,
            'user_id' => $responderId,
            'assigned_by' => $dispatcherId,
            'assigned_at' => $assignedAt,
            'responder_status' => 'en_route',
            'status_updated_at' => $statusUpdatedAt,
        ]);

        $incident = Incident::with('responseTeam')->findOrFail($incidentId);
        $responder = User::with('responseIncidents')->findOrFail($responderId);

        $this->assertCount(1, $incident->responseTeam);
        $this->assertSame($responderId, $incident->responseTeam->first()->id);
        $this->assertSame('en_route', $incident->responseTeam->first()->pivot->responder_status);
        $this->assertSame($dispatcherId, $incident->responseTeam->first()->pivot->assigned_by);
        $this->assertEquals($assignedAt, $incident->responseTeam->first()->pivot->assigned_at);
        $this->assertEquals($statusUpdatedAt, $incident->responseTeam->first()->pivot->status_updated_at);

        $this->assertCount(1, $responder->responseIncidents);
        $this->assertSame($incidentId, $responder->responseIncidents->first()->id);
        $this->assertSame('en_route', $responder->responseIncidents->first()->pivot->responder_status);
        $this->assertSame($dispatcherId, $responder->responseIncidents->first()->pivot->assigned_by);
        $this->assertEquals($assignedAt, $responder->responseIncidents->first()->pivot->assigned_at);
        $this->assertEquals($statusUpdatedAt, $responder->responseIncidents->first()->pivot->status_updated_at);
    }

    private function insertUser(string $email, string $role): int
    {
        return DB::table('users')->insertGetId([
            'name' => ucfirst($role).' Relationship Test',
            'email' => $email,
            'password' => password_hash('not-a-real-password', PASSWORD_BCRYPT),
            'role' => $role,
            'created_at' => now(),
            'updated_at' => now(),
        ]);
    }
}
