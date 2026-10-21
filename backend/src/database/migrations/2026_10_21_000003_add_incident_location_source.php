<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::table('incidents', function (Blueprint $table) {
            $table->string('location_source', 12)->default('gps');
        });

        // Existing rows without coordinates must not be mislabeled as GPS-derived.
        DB::table('incidents')
            ->whereNull('latitude')
            ->orWhereNull('longitude')
            ->update(['location_source' => 'none']);
    }

    public function down(): void
    {
        Schema::table('incidents', function (Blueprint $table) {
            $table->dropColumn('location_source');
        });
    }
};
