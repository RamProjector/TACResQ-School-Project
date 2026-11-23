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
                IF TG_OP = 'UPDATE'
                    AND (
                        NEW.actor_user_id IS NOT DISTINCT FROM OLD.actor_user_id
                        OR (OLD.actor_user_id IS NOT NULL AND NEW.actor_user_id IS NULL)
                    )
                    AND (
                        NEW.target_user_id IS NOT DISTINCT FROM OLD.target_user_id
                        OR (OLD.target_user_id IS NOT NULL AND NEW.target_user_id IS NULL)
                    )
                    AND (
                        NEW.actor_user_id IS DISTINCT FROM OLD.actor_user_id
                        OR NEW.target_user_id IS DISTINCT FROM OLD.target_user_id
                    )
                    AND (to_jsonb(NEW) - 'actor_user_id' - 'target_user_id')
                        = (to_jsonb(OLD) - 'actor_user_id' - 'target_user_id')
                THEN
                    RETURN NEW;
                END IF;

                RAISE EXCEPTION 'local account lifecycle events are append-only'
                    USING ERRCODE = '55000';
            END;
            $$;

            CREATE TRIGGER local_lifecycle_events_append_only
            BEFORE UPDATE OR DELETE ON local_account_lifecycle_events
            FOR EACH ROW
            EXECUTE FUNCTION tacresq_guard_local_lifecycle_event_mutation();
        SQL);
    }

    public function down(): void
    {
        if (DB::connection()->getDriverName() !== 'pgsql') {
            return;
        }

        DB::unprepared(<<<'SQL'
            DROP TRIGGER IF EXISTS local_lifecycle_events_append_only
                ON local_account_lifecycle_events;
            DROP FUNCTION IF EXISTS tacresq_guard_local_lifecycle_event_mutation();
        SQL);
    }
};
