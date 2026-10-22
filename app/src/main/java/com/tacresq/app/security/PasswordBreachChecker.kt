package com.tacresq.app.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Checks the Have I Been Pwned Pwned Passwords range API.
 *
 * Only the first five hex characters of a SHA-1 password hash are sent.
 * The password and full hash never leave the device. A failed check is
 * fail-closed for live registration: the caller must not continue signup.
 */
object PasswordBreachChecker {
    private const val API_BASE = "https://api.pwnedpasswords.com/range/"
    private const val PREFIX_LENGTH = 5
    private val RANGE_LINE = Regex("^([0-9A-F]{35}):([0-9]+)$", RegexOption.IGNORE_CASE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun isCompromised(password: String): Boolean = withContext(Dispatchers.IO) {
        val hash = sha1(password)
        val prefix = hash.substring(0, PREFIX_LENGTH)
        val suffix = hash.substring(PREFIX_LENGTH)

        val request = Request.Builder()
            .url(API_BASE + prefix)
            .header("Add-Padding", "true")
            .header("User-Agent", "TACResQ-Android-PasswordSecurity")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PasswordCheckUnavailableException()
                }
                val body = response.body?.string() ?: throw PasswordCheckUnavailableException()
                val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                if (lines.isEmpty()) throw PasswordCheckUnavailableException()

                var compromised = false
                for (line in lines) {
                    val match = RANGE_LINE.matchEntire(line) ?: throw PasswordCheckUnavailableException()
                    val candidate = match.groupValues[1]
                    val count = match.groupValues[2].toLongOrNull() ?: throw PasswordCheckUnavailableException()
                    if (candidate.equals(suffix, ignoreCase = true) && count > 0L) {
                        compromised = true
                    }
                }
                compromised
            }
        } catch (failure: PasswordCheckUnavailableException) {
            throw failure
        } catch (failure: IOException) {
            throw PasswordCheckUnavailableException()
        }
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02X".format(byte) }
    }
}

class CompromisedPasswordException : IllegalStateException()

class PasswordCheckUnavailableException : IllegalStateException(
    "We couldn't verify this password's breach status. Check your connection and try again."
)
