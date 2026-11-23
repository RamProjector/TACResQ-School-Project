<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Support\Facades\DB;

return new class extends Migration {
    public function up(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            return;
        }

        DB::statement(<<<'SQL'
            ALTER TABLE local_account_lifecycle_events
                ALTER COLUMN actor_user_id SET NOT NULL,
                ALTER COLUMN target_user_id SET NOT NULL
        SQL);
    }

    public function down(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            return;
        }

        DB::statement(<<<'SQL'
            ALTER TABLE local_account_lifecycle_events
                ALTER COLUMN actor_user_id DROP NOT NULL,
                ALTER COLUMN target_user_id DROP NOT NULL
        SQL);
    }
};
