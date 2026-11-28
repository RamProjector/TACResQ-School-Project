package com.tacresq.app.data.remote

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Builds the exact versioned message that the Supabase device verifier expects.
 *
 * The caller must obtain sessionId from the authenticated Supabase session.
 * This helper is intentionally not connected to DeviceProofKeyStore or an
 * operational application flow until hosted verifier and device gates pass.
 */
object DeviceProofMessage {
    private val uuidPattern = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE,
    )
    private val challengePattern = Regex("^[A-Za-z0-9_-]{43}$")
    private val profileIdPattern = Regex("^[1-9][0-9]{0,18}$")

    fun build(challenge: DeviceProofChallenge, sessionId: String): ByteArray {
        require(challenge.challengeId.matches(uuidPattern)) {
            "Device challenge identifier is invalid."
        }
        require(challenge.purpose == DeviceProofPurpose.REGISTRATION.wireValue
            || challenge.purpose == DeviceProofPurpose.SESSION_PROOF.wireValue) {
            "Unsupported device proof purpose."
        }
        require(challenge.profileId.matches(profileIdPattern)) {
            "Device challenge profile reference is invalid."
        }
        require(sessionId.matches(uuidPattern)) {
            "Authenticated Supabase session identifier is invalid."
        }
        val validDeviceReference = when (challenge.purpose) {
            DeviceProofPurpose.REGISTRATION.wireValue -> challenge.deviceUid == "-"
            DeviceProofPurpose.SESSION_PROOF.wireValue -> challenge.deviceUid.matches(uuidPattern)
            else -> false
        }
        require(validDeviceReference) {
            "Device identifier does not match proof purpose."
        }
        require(challenge.challenge.matches(challengePattern)) {
            "Device challenge encoding is invalid."
        }

        val rawChallenge = try {
            Base64.getUrlDecoder().decode(challenge.challenge)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Device challenge encoding is invalid.")
        }
        require(rawChallenge.size == CHALLENGE_BYTES
            && Base64.getUrlEncoder().withoutPadding().encodeToString(rawChallenge) == challenge.challenge) {
            "Device challenge must be canonical Base64URL containing exactly 32 random bytes."
        }

        val message = listOf(
            MESSAGE_DOMAIN,
            MESSAGE_VERSION,
            challenge.challengeId,
            challenge.purpose,
            challenge.profileId,
            sessionId,
            challenge.deviceUid,
            challenge.challenge,
        ).joinToString("\n")
        require(message.toByteArray(StandardCharsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "Device proof message is too large."
        }
        return message.toByteArray(StandardCharsets.UTF_8)
    }

    private const val MESSAGE_DOMAIN = "TACRESQ-DEVICE-PROOF"
    private const val MESSAGE_VERSION = "v1"
    private const val CHALLENGE_BYTES = 32
    private const val MAX_MESSAGE_BYTES = 2048
}
