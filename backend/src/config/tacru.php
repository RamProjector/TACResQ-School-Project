<?php

return [
    // These are browser-safe client settings. A Render environment variable can
    // override them, but the dashboard must not depend on the temporary database
    // or on Laravel's runtime environment lookup.
    'supabase_url' => env('SUPABASE_URL', 'https://kttguamhznoflzagfutr.supabase.co'),
    'supabase_publishable_key' => env('SUPABASE_PUBLISHABLE_KEY', 'sb_publishable_1-_oEKiNRWO0tvEOr5Gr3g_USY07s2g'),
];
