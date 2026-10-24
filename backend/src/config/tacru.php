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
        'api_cancel_my_incident',
        'api_create_incident_v4',
        'api_record_location_v2',
        'api_units',
        'api_update_my_incident',
    ],
];
