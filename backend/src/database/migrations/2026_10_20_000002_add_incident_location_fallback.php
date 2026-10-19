<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::table('incidents', function (Blueprint $table) {
            $table->decimal('latitude', 10, 7)->nullable()->change();
            $table->decimal('longitude', 11, 7)->nullable()->change();
            $table->text('location_notes')->nullable();
            $table->timestampTz('location_recorded_at')->nullable();
        });
    }

    public function down(): void
    {
        if (DB::table('incidents')->whereNull('latitude')->orWhereNull('longitude')->exists()) {
            throw new RuntimeException('Cannot restore required coordinates while no-GPS incidents exist. Export or resolve those incidents first.');
        }

        Schema::table('incidents', function (Blueprint $table) {
            $table->decimal('latitude', 10, 7)->nullable(false)->change();
            $table->decimal('longitude', 11, 7)->nullable(false)->change();
        });
        Schema::table('incidents', function (Blueprint $table) {
            $table->dropColumn(['location_notes', 'location_recorded_at']);
        });
    }
};
