<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->boolean('is_suspended')->default(false)->index();
            $table->timestampTz('suspended_at')->nullable();
            $table->string('suspension_reason', 500)->nullable();
        });
    }

    public function down(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->dropIndex(['is_suspended']);
            $table->dropColumn(['is_suspended', 'suspended_at', 'suspension_reason']);
        });
    }
};
