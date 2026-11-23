<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::create('local_account_lifecycle_events', function (Blueprint $table) {
            $table->id();
            $table->unsignedBigInteger('actor_user_id')->nullable();
            $table->unsignedBigInteger('target_user_id')->nullable();
            $table->string('action', 24);
            $table->string('reason', 500);
            $table->string('previous_state', 16);
            $table->string('new_state', 16);
            $table->timestampTz('created_at')->useCurrent();
            $table->index(['target_user_id', 'created_at'], 'local_lifecycle_target_created_idx');
            $table->index(['actor_user_id', 'created_at'], 'local_lifecycle_actor_created_idx');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('local_account_lifecycle_events');
    }
};
