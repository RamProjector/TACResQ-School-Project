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
            ADD CONSTRAINT local_lifecycle_actor_positive_check
                CHECK (actor_user_id IS NULL OR actor_user_id > 0),
            ADD CONSTRAINT local_lifecycle_target_positive_check
                CHECK (target_user_id IS NULL OR target_user_id > 0),
            ADD CONSTRAINT local_lifecycle_distinct_parties_check
                CHECK (
                    actor_user_id IS NULL
                    OR target_user_id IS NULL
                    OR actor_user_id <> target_user_id
                ),
            ADD CONSTRAINT local_lifecycle_reason_nonblank_check
                CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
            ADD CONSTRAINT local_lifecycle_transition_check
                CHECK (
                    (action = 'suspended' AND previous_state = 'active' AND new_state = 'suspended')
                    OR
                    (action = 'reactivated' AND previous_state = 'suspended' AND new_state = 'active')
                )
        SQL);
    }

    public function down(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            return;
        }

        DB::statement(<<<'SQL'
            ALTER TABLE local_account_lifecycle_events
            DROP CONSTRAINT IF EXISTS local_lifecycle_actor_positive_check,
            DROP CONSTRAINT IF EXISTS local_lifecycle_target_positive_check,
            DROP CONSTRAINT IF EXISTS local_lifecycle_distinct_parties_check,
            DROP CONSTRAINT IF EXISTS local_lifecycle_reason_nonblank_check,
            DROP CONSTRAINT IF EXISTS local_lifecycle_transition_check
        SQL);
    }
};
