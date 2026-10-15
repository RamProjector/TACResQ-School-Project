package com.tacresq.app

object AppConfig {
    /**
     * true  -> the app runs on an in-memory fake backend (FakeTacApi). No server needed.
     * false -> the app talks to the Laravel REST API at BASE_URL.
     */
    const val USE_FAKE_API = true

    /**
     * Laravel API root. Must end with "/".
     * Emulator   -> your PC's localhost is 10.0.2.2
     * Real phone -> your PC's LAN IP, e.g. http://192.168.1.10:8000/api/
     *               (start Laravel with: php artisan serve --host=0.0.0.0)
     */
    const val BASE_URL = "http://10.0.2.2:8000/api/"

    /** How often incident and status screens refresh until FCM push is added. */
    const val POLL_INTERVAL_MS = 8_000L
}
