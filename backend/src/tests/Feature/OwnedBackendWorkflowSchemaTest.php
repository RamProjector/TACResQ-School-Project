<?php

namespace Tests\Feature;

use Illuminate\Database\QueryException;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;
use Tests\TestCase;

class OwnedBackendWorkflowSchemaTest extends TestCase
{
    use RefreshDatabase;

    public function test_owned_workflow_tables_and_required_columns_exist(): void
    {
        $this->assertTrue(Schema::hasTable('incident_submission_keys'));
        $this->assertTrue(Schema::hasColumns('incident_submission_keys', [
            'requester_id', 'client_request_id', 'request_payload', 'response_payload',
        ]));

        $this->assertTrue(Schema::hasColumns('incident_assignments', [
            'incident_id', 'user_id', 'assigned_by', 'responder_status', 'status_updated_at',
        ]));

        $this->assertTrue(Schema::hasColumns('incident_request_edit_logs', [
            'incident_id', 'requester_id', 'edit_number', 'before_values',
            'after_values', 'urgent_location_correction',
        ]));

        $this->assertTrue(Schema::hasColumns('location_logs', [
            'user_id', 'incident_id', 'latitude', 'longitude', 'accuracy_m', 'recorded_at',
        ]));
    }

    public function test_sos_idempotency_key_is_unique_per_requester(): void
    {
        $requesterId = DB::table('users')->insertGetId([
            'name' => 'Schema Test Requester',
            'email' => 'schema-requester@example.test',
            'password' => password_hash('not-a-real-password', PASSWORD_BCRYPT),
            'role' => 'requester',
            'created_at' => now(),
            'updated_at' => now(),
        ]);

        $record = [
            'requester_id' => $requesterId,
            'client_request_id' => 'd2719c7e-4c4d-4d2d-8e3c-b2d28b01a0af',
            'request_payload' => json_encode(['type' => 'medical'], JSON_THROW_ON_ERROR),
            'created_at' => now(),
        ];
        DB::table('incident_submission_keys')->insert($record);

        try {
            // A nested transaction creates a savepoint on PostgreSQL. The expected
            // uniqueness violation is rolled back to that savepoint so the test's
            // outer RefreshDatabase transaction remains usable for assertions.
            DB::transaction(fn () => DB::table('incident_submission_keys')->insert($record));
            $this->fail('A repeated requester/client request key must be rejected by the database.');
        } catch (QueryException) {
            $this->assertDatabaseCount('incident_submission_keys', 1);
        }
    }

    public function test_assignment_history_is_unique_per_incident_and_responder(): void
    {
        $requesterId = DB::table('users')->insertGetId([
            'name' => 'Schema Test Requester',
            'email' => 'schema-requester-assignment@example.test',
            'password' => password_hash('not-a-real-password', PASSWORD_BCRYPT),
            'role' => 'requester',
            'created_at' => now(),
            'updated_at' => now(),
        ]);
        $responderId = DB::table('users')->insertGetId([
            'name' => 'Schema Test Responder',
            'email' => 'schema-responder@example.test',
            'password' => password_hash('not-a-real-password', PASSWORD_BCRYPT),
            'role' => 'responder',
            'duty_status' => 'available',
            'created_at' => now(),
            'updated_at' => now(),
        ]);
        $incidentId = DB::table('incidents')->insertGetId([
            'requester_id' => $requesterId,
            'type' => 'medical',
            'status' => 'pending',
            'created_at' => now(),
            'updated_at' => now(),
        ]);
        $assignment = [
            'incident_id' => $incidentId,
            'user_id' => $responderId,
            'responder_status' => 'assigned',
            'assigned_at' => now(),
            'status_updated_at' => now(),
        ];
        DB::table('incident_assignments')->insert($assignment);

        try {
            DB::transaction(fn () => DB::table('incident_assignments')->insert($assignment));
            $this->fail('Duplicate assignment rows for the same incident/responder must be rejected.');
        } catch (QueryException) {
            $this->assertDatabaseCount('incident_assignments', 1);
        }
    }
}
