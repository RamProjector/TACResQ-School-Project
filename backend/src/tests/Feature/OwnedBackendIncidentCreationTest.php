<?php

namespace Tests\Feature;

use App\Models\Incident;
use App\Models\User;
use Illuminate\Database\QueryException;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
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

        // Simulate later operational state changes. Idempotent retries must
        // return the original submission acknowledgement, not current state.
        Incident::whereKey($incidentId)->update(['status' => 'resolved']);

        $replay = $this->postJson('/api/incidents', $body);
        $replay->assertOk()->assertJsonPath('id', $incidentId);

        // A replay must return the original persisted response, not rebuild a
        // subtly different payload from mutable incident state.
        $this->assertSame($first->json(), $replay->json());
        $this->assertSame('pending', $replay->json('status'));
        $this->assertSame('resolved', Incident::findOrFail($incidentId)->status);

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


    public function test_failed_status_log_write_rolls_back_incident_and_idempotency_key(): void
    {
        $requester = $this->requester('sos-rollback@example.test');
        Sanctum::actingAs($requester);

        // Inject a failure at the final business write after the idempotency
        // key and incident are inserted. The request transaction must undo all.
        DB::unprepared(<<<'SQL'
            CREATE FUNCTION tacresq_test_reject_status_log() RETURNS trigger
            LANGUAGE plpgsql
            AS $tacresq$
            BEGIN
                RAISE EXCEPTION 'synthetic status-log write failure';
            END;
            $tacresq$
        SQL);
        DB::unprepared(<<<'SQL'
            CREATE TRIGGER tacresq_test_reject_status_log
            BEFORE INSERT ON incident_status_logs
            FOR EACH ROW EXECUTE FUNCTION tacresq_test_reject_status_log()
        SQL);

        try {
            $this->withoutExceptionHandling();

            try {
                $this->postJson('/api/incidents', [
                    'client_request_id' => 'fa0f06d0-8ad5-44ab-8b4a-14c3b3f8d8e1',
                    'type' => 'medical',
                    'description' => 'Rollback verification',
                ]);
                $this->fail('The injected status-log failure should abort SOS creation.');
            } catch (QueryException $exception) {
                $this->assertStringContainsString('synthetic status-log write failure', $exception->getMessage());
            }
        } finally {
            DB::unprepared('DROP TRIGGER IF EXISTS tacresq_test_reject_status_log ON incident_status_logs');
            DB::unprepared('DROP FUNCTION IF EXISTS tacresq_test_reject_status_log()');
        }

        $this->assertSame(0, Incident::where('requester_id', $requester->id)->count());
        $this->assertDatabaseCount('incident_status_logs', 0);
        $this->assertDatabaseCount('incident_submission_keys', 0);
    }


    public function test_gps_capture_metadata_is_persisted_and_manual_pin_cannot_claim_accuracy(): void
    {
        $requester = $this->requester('sos-location-provenance@example.test');
        Sanctum::actingAs($requester);

        $this->postJson('/api/incidents', [
            'client_request_id' => 'f90d0c3a-61d0-4d78-bc6d-ec5ac1c9e331',
            'type' => 'medical',
            'latitude' => 10.3157,
            'longitude' => 123.8854,
            'accuracy_m' => 8.5,
            'location_recorded_at' => '2026-11-19T05:00:00Z',
            'location_source' => 'gps',
            'location_notes' => '  Main entrance  ',
        ])->assertCreated();

        $this->assertDatabaseHas('incidents', [
            'requester_id' => $requester->id,
            'latitude' => 10.3157,
            'longitude' => 123.8854,
            'accuracy_m' => 8.5,
            'location_source' => 'gps',
            'location_notes' => 'Main entrance',
        ]);

        $this->assertSame(1, Incident::where('requester_id', $requester->id)->count());

        $manualRequester = $this->requester('sos-manual-provenance@example.test');
        Sanctum::actingAs($manualRequester);

        $this->postJson('/api/incidents', [
            'client_request_id' => '3f8d845c-4c4e-4410-9ed1-5e8e9e3f1a73',
            'type' => 'fire',
            'latitude' => 10.3157,
            'longitude' => 123.8854,
            'accuracy_m' => 8.5,
            'location_recorded_at' => '2026-11-19T05:00:00Z',
            'location_source' => 'manual',
        ])->assertStatus(422);

        $this->assertSame(0, Incident::where('requester_id', $manualRequester->id)->count());
    }


    public function test_manual_pin_is_accepted_without_gps_accuracy_and_requires_capture_time(): void
    {
        $requester = $this->requester('sos-manual-pin@example.test');
        Sanctum::actingAs($requester);

        $this->postJson('/api/incidents', [
            'client_request_id' => '5a0ef76a-0d47-40e8-96a5-3f2d8f1cb777',
            'type' => 'vehicular',
            'latitude' => 10.3157,
            'longitude' => 123.8854,
            'location_source' => 'manual',
            'location_recorded_at' => '2026-11-19T05:00:00Z',
        ])->assertCreated();

        $this->assertDatabaseHas('incidents', [
            'requester_id' => $requester->id,
            'location_source' => 'manual',
            'accuracy_m' => null,
            'latitude' => 10.3157,
            'longitude' => 123.8854,
        ]);

        $secondRequester = $this->requester('sos-missing-capture-time@example.test');
        Sanctum::actingAs($secondRequester);

        $this->postJson('/api/incidents', [
            'client_request_id' => 'a9f0a82d-8e2a-4f5b-9e81-2e1b4f88a2ac',
            'type' => 'vehicular',
            'latitude' => 10.3157,
            'longitude' => 123.8854,
            'location_source' => 'manual',
        ])->assertStatus(422);

        $this->assertSame(0, Incident::where('requester_id', $secondRequester->id)->count());
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
