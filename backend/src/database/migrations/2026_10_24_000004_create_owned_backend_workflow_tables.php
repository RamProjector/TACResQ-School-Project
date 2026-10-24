<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::create('incident_submission_keys', function (Blueprint $table) {
            $table->id();
            $table->foreignId('requester_id')->constrained('users')->cascadeOnDelete();
            $table->uuid('client_request_id');
            $table->json('request_payload');
            $table->json('response_payload')->nullable();
            $table->timestampTz('created_at')->useCurrent();
            $table->unique(['requester_id', 'client_request_id'], 'submission_keys_requester_client_unique');
        });

        Schema::create('incident_assignments', function (Blueprint $table) {
            $table->id();
            $table->foreignId('incident_id')->constrained('incidents')->cascadeOnDelete();
            $table->foreignId('user_id')->constrained('users')->restrictOnDelete();
            $table->foreignId('assigned_by')->nullable()->constrained('users')->nullOnDelete();
            $table->timestampTz('assigned_at')->useCurrent();
            $table->string('responder_status', 20)->default('assigned');
            $table->timestampTz('status_updated_at')->useCurrent();
            $table->unique(['incident_id', 'user_id'], 'incident_assignments_incident_user_unique');
            $table->index(['user_id', 'responder_status'], 'incident_assignments_user_status_idx');
            $table->index(['incident_id', 'responder_status'], 'incident_assignments_incident_status_idx');
        });

        Schema::create('incident_request_edit_logs', function (Blueprint $table) {
            $table->id();
            $table->foreignId('incident_id')->constrained('incidents')->cascadeOnDelete();
            $table->foreignId('requester_id')->constrained('users')->restrictOnDelete();
            $table->unsignedInteger('edit_number');
            $table->json('before_values');
            $table->json('after_values');
            $table->boolean('urgent_location_correction')->default(false);
            $table->timestampTz('created_at')->useCurrent();
            $table->unique(['incident_id', 'edit_number'], 'incident_edit_logs_incident_number_unique');
            $table->index(['requester_id', 'created_at'], 'incident_edit_logs_requester_created_idx');
        });

        Schema::create('location_logs', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained('users')->cascadeOnDelete();
            $table->double('latitude');
            $table->double('longitude');
            $table->float('accuracy_m')->nullable();
            $table->timestampTz('recorded_at')->useCurrent();
            $table->foreignId('incident_id')->nullable()->constrained('incidents')->cascadeOnDelete();
            $table->index(['incident_id', 'recorded_at'], 'location_logs_incident_recorded_idx');
            $table->index(['user_id', 'recorded_at'], 'location_logs_user_recorded_idx');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('location_logs');
        Schema::dropIfExists('incident_request_edit_logs');
        Schema::dropIfExists('incident_assignments');
        Schema::dropIfExists('incident_submission_keys');
    }
};
