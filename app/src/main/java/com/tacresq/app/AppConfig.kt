package com.tacresq.app

object AppConfig {
    /**
     * Backend selection. The Android app uses the deployed Laravel API.
     * Set USE_FAKE_API true only for the offline demo; set USE_SUPABASE_API true
     * only when intentionally connecting the app directly to Supabase.
     */
    const val USE_FAKE_API = false
    const val USE_SUPABASE_API = false

    /** Optional demo behavior. Keep off for the normal dispatcher-controlled workflow. */
    const val FAKE_AUTO_DISPATCH = false

    /**
     * Laravel API root. Must end with "/". Use the Render service URL in production.
     */
    const val BASE_URL = "https://tacresq-school-project.onrender.com/api/"

    /** Supabase project URL and publishable key. Never put a service-role key in an app. */
    const val SUPABASE_URL = "https://kttguamhznoflzagfutr.supabase.co"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_1-_oEKiNRWO0tvEOr5Gr3g_USY07s2g"

    /** How often incident and status screens refresh until FCM push is added. */
    const val POLL_INTERVAL_MS = 8_000L
}
