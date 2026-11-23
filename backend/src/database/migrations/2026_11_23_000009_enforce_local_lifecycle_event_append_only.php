<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Support\Facades\DB;

return new class extends Migration {
    public function up(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            return;
        }

        DB::unprepared(<<<'SQL'
            CREATE OR REPLACE FUNCTION tacresq_guard_local_lifecycle_event_mutation()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
                RAISE EXCEPTION 'local account lifecycle events are append-only'
                    USING ERRCODE = '55000';
            END;
            $$;

            CREATE TRIGGER local_lifecycle_events_append_only
            BEFORE UPDATE OR DELETE ON local_account_lifecycle_events
            FOR EACH ROW
            EXECUTE FUNCTION tacresq_guard_local_lifecycle_event_mutation();

            CREATE TRIGGER local_lifecycle_events_no_truncate
            BEFORE TRUNCATE ON local_account_lifecycle_events
            FOR EACH STATEMENT
            EXECUTE FUNCTION tacresq_guard_local_lifecycle_event_mutation();
        SQL);
    }

    public function down(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            return;
        }

        DB::unprepared(<<<'SQL'
            DROP TRIGGER IF EXISTS local_lifecycle_events_no_truncate
                ON local_account_lifecycle_events;
            DROP TRIGGER IF EXISTS local_lifecycle_events_append_only
                ON local_account_lifecycle_events;
            DROP FUNCTION IF EXISTS tacresq_guard_local_lifecycle_event_mutation();
        SQL);
    }
};
