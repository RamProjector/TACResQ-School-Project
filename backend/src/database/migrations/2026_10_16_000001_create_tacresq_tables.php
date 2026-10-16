<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::create('users', function (Blueprint $table) {
            $table->id(); $table->string('name', 120); $table->string('email')->unique();
            $table->string('phone', 40)->nullable(); $table->timestamp('email_verified_at')->nullable();
            $table->string('password'); $table->string('role', 20)->default('requester')->index();
            $table->string('duty_status', 20)->nullable()->index(); $table->rememberToken(); $table->timestamps();
        });
        Schema::create('password_reset_tokens', function (Blueprint $table) {
            $table->string('email')->primary(); $table->string('token'); $table->timestamp('created_at')->nullable();
        });
        Schema::create('incidents', function (Blueprint $table) {
            $table->id(); $table->foreignId('requester_id')->constrained('users')->cascadeOnDelete();
            $table->string('type', 20); $table->text('description')->nullable();
            $table->decimal('latitude', 10, 7); $table->decimal('longitude', 11, 7); $table->decimal('accuracy_m', 10, 2)->nullable();
            $table->string('status', 20)->default('pending')->index();
            $table->foreignId('assigned_to')->nullable()->constrained('users')->nullOnDelete();
            $table->timestamps(); $table->index(['assigned_to', 'status']); $table->index(['requester_id', 'created_at']);
        });
        Schema::create('incident_status_logs', function (Blueprint $table) {
            $table->id(); $table->foreignId('incident_id')->constrained()->cascadeOnDelete();
            $table->string('status', 20); $table->foreignId('changed_by')->nullable()->constrained('users')->nullOnDelete();
            $table->timestampTz('created_at')->useCurrent(); $table->index(['incident_id', 'created_at']);
        });
    }
    public function down(): void
    {
        Schema::dropIfExists('incident_status_logs'); Schema::dropIfExists('incidents');
        Schema::dropIfExists('password_reset_tokens'); Schema::dropIfExists('users');
    }
};
