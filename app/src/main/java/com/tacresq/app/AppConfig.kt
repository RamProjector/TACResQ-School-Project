package com.tacresq.app

object AppConfig {
    /**
     * true  -> in-memory fake backend (works offline).
     * false -> Supabase Auth and Postgres RPC backend.
     */
    const val USE_FAKE_API = false

    /**
     * Supabase project URL and publishable key. Never put a service-role key in an app.
     */
    const val SUPABASE_URL = "https://kttguamhznoflzagfutr.supabase.co"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_1-_oEKiNRWO0tvEOr5Gr3g_USY07s2g"

    /** How often incident and status screens refresh until FCM push is added. */
    const val POLL_INTERVAL_MS = 8_000L
}
