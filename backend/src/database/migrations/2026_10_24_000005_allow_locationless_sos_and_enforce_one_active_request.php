<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;
use RuntimeException;

return new class extends Migration {
    private const ACTIVE_STATUSES = ['pending', 'assigned', 'en_route', 'on_scene', 'transporting'];

    public function up(): void
    {
        $duplicateRequester = DB::table('incidents')
            ->select('requester_id')
            ->whereIn('status', self::ACTIVE_STATUSES)
            ->groupBy('requester_id')
            ->havingRaw('COUNT(*) > 1')
            ->value('requester_id');

        if ($duplicateRequester !== null) {
            throw new RuntimeException(
                'Cannot enforce one active incident per requester: existing duplicate active incidents must be reconciled first.'
            );
        }

        Schema::table('incidents', function (Blueprint $table) {
            $table->decimal('latitude', 10, 7)->nullable()->change();
            $table->decimal('longitude', 11, 7)->nullable()->change();
        });

        // PostgreSQL and SQLite support partial unique indexes. This is the final
        // concurrency guard in addition to the account-row lock in the API.
        DB::statement(
            "CREATE UNIQUE INDEX incidents_one_active_per_requester_unique
             ON incidents (requester_id)
             WHERE status IN ('pending', 'assigned', 'en_route', 'on_scene', 'transporting')"
        );
    }

    public function down(): void
    {
        Schema::table('incidents', function (Blueprint $table) {
            // Keep coordinates nullable if historical requests have no location;
            // forcing NOT NULL would destroy support for location-unavailable SOS.
        });

        DB::statement('DROP INDEX IF EXISTS incidents_one_active_per_requester_unique');
    }
};
