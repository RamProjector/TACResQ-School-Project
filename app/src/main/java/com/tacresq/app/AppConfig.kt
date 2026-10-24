package com.tacresq.app

object AppConfig {
    /**
     * Backend selection is build-specific: debug uses the in-memory demo by default.
     * Release uses Supabase Auth and routes all application data RPCs through Render.
     * Build debug with -PuseSupabase=true -PuseRenderBridge=true for real-backend QA.
     */
    val USE_FAKE_API = BuildConfig.USE_FAKE_API
    val USE_SUPABASE_API = !USE_FAKE_API
    /** Required for release data access; opt-in for real-backend debug QA. */
    val USE_RENDER_BRIDGE = BuildConfig.USE_RENDER_BRIDGE

    /** Optional demo behavior. Keep off for the normal dispatcher-controlled workflow. */
    const val FAKE_AUTO_DISPATCH = false

    /**
     * Render API root for the authenticated Supabase RPC bridge and legacy Laravel adapter.
     * The bridge forwards caller-authenticated RPCs; it does not use a Render database.
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
