package com.tacresq.app

import java.net.URI
import java.net.URISyntaxException

/**
 * Recognizes only TACResQ's exact auth-return URI. Deep links can be invoked by
 * other apps, so this never treats a callback as proof of authentication and
 * never reads access tokens from query parameters or fragments.
 */
internal object AuthCallbackMessage {
    const val MESSAGE =
        "TACResQ received the return link. For your safety, sign in with your email and password. If sign-in fails, reopen the confirmation email or request a new confirmation link."

    fun from(rawUri: String?): String? {
        if (rawUri.isNullOrBlank()) return null
        val uri = try {
            URI(rawUri)
        } catch (_: URISyntaxException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }

        val isExpectedCallback =
            uri.scheme.equals("tacresq", ignoreCase = true) &&
                uri.host.equals("auth", ignoreCase = true) &&
                uri.rawPath == "/callback" &&
                uri.rawUserInfo == null &&
                uri.port == -1

        return MESSAGE.takeIf { isExpectedCallback }
    }
}
