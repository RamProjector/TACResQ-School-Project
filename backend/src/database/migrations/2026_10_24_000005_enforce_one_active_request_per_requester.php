<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Support\Facades\DB;

return new class extends Migration {
    public function up(): void
    {
        $duplicateRequester = DB::table('incidents')
            ->select('requester_id')
            ->whereIn('status', ['pending', 'assigned', 'en_route', 'on_scene', 'transporting'])
            ->groupBy('requester_id')
            ->havingRaw('COUNT(*) > 1')
            ->value('requester_id');

        if ($duplicateRequester !== null) {
            throw new \RuntimeException(
                'Cannot enforce one active incident per requester: existing duplicate active incidents must be reconciled first.'
            );
        }

        // PostgreSQL and SQLite support partial unique indexes. This protects the
        // invariant even if a future write path forgets the account-row lock.
        DB::statement(
            "CREATE UNIQUE INDEX incidents_one_active_per_requester_unique
             ON incidents (requester_id)
             WHERE status IN ('pending', 'assigned', 'en_route', 'on_scene', 'transporting')"
        );
    }

    public function down(): void
    {
        DB::statement('DROP INDEX IF EXISTS incidents_one_active_per_requester_unique');
    }
};
