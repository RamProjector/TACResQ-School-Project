package com.tacresq.app

object AppConfig {
    /**
     * Backend selection is build-specific: debug uses the in-memory demo by default,
     * while release always uses Supabase. Build debug with -PuseSupabase=true to test
     * a configured Supabase project without changing release behavior.
     */
    val USE_FAKE_API = BuildConfig.USE_FAKE_API
    val USE_SUPABASE_API = !USE_FAKE_API

    /** Optional demo behavior. Keep off for the normal dispatcher-controlled workflow. */
    const val FAKE_AUTO_DISPATCH = false

    /**
     * Laravel API root. Retained for the optional Laravel adapter; the app currently
     * uses Supabase directly, so no Render database is required.
     */
    const val BASE_URL = "https://tacresq-school-project.onrender.com/api/"

    /** Supabase project URL and publishable key. Never put a service-role key in an app. */
    const val SUPABASE_URL = "https://kttguamhznoflzagfutr.supabase.co"
    /** Supabase email confirmation returns to the installed Android app, not a local web dev server. */
    const val AUTH_REDIRECT_URL = "tacresq://auth/callback"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_1-_oEKiNRWO0tvEOr5Gr3g_USY07s2g"

    /** How often incident and status screens refresh until FCM push is added. */
    const val POLL_INTERVAL_MS = 8_000L
}
