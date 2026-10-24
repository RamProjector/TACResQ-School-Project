<?php

return [
    // These are browser-safe client settings. A Render environment variable can
    // override them, but the dashboard must not depend on the temporary database
    // or on Laravel's runtime environment lookup.
    'supabase_url' => env('SUPABASE_URL', 'https://kttguamhznoflzagfutr.supabase.co'),
    'supabase_publishable_key' => env('SUPABASE_PUBLISHABLE_KEY', 'sb_publishable_1-_oEKiNRWO0tvEOr5Gr3g_USY07s2g'),


    // Explicit RPC allowlist for the additive Render -> Supabase bridge.
    // Every function still enforces identity/role/ownership in PostgreSQL.
    // Never add a function here without reviewing its full SQL definition.
    'bridge_rpc_allowlist' => [
        'api_active_responder_locations',
        'api_all_incidents',
        'api_assign_incident',
        'api_assign_incident_team',
        'api_cancel_my_incident',
        'api_create_incident_v4',
        'api_me',
        'api_my_incidents',
        'api_record_location_v2',
        'api_set_duty_status',
        'api_units',
        'api_update_incident_status',
        'api_update_my_assignment_status',
        'api_update_my_incident',
    ],
];
