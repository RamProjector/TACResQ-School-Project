package com.tacresq.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class DeviceProofMessageTest {
    @Test
    fun registrationMessageMatchesTheServerCanonicalWireFormat() {
        val actual = String(
            DeviceProofMessage.build(registrationChallenge(), SESSION_ID),
            StandardCharsets.UTF_8,
        )
        val expected = listOf(
            "TACRESQ-DEVICE-PROOF",
            "v1",
            CHALLENGE_ID,
            "registration",
            "42",
            SESSION_ID,
            "-",
            CHALLENGE,
        ).joinToString("\n")

        assertEquals(expected, actual)
    }

    @Test
    fun sessionProofMessageBindsTheRegisteredDeviceUid() {
        val actual = String(
            DeviceProofMessage.build(
                registrationChallenge(
                    purpose = "session_proof",
                    deviceUid = DEVICE_UID,
                ),
                SESSION_ID,
            ),
            StandardCharsets.UTF_8,
        )

        assertTrue(actual.endsWith("\n$DEVICE_UID\n$CHALLENGE"))
    }

    @Test
    fun rejectsInvalidSessionPurposeProfileAndDeviceBindings() {
        val invalidSession = runCatching {
            DeviceProofMessage.build(registrationChallenge(), "not-a-uuid")
        }
        val invalidPurpose = runCatching {
            DeviceProofMessage.build(registrationChallenge(purpose = "step_up"), SESSION_ID)
        }
        val invalidProfile = runCatching {
            DeviceProofMessage.build(registrationChallenge(profileId = "0"), SESSION_ID)
        }
        val invalidDevice = runCatching {
            DeviceProofMessage.build(
                registrationChallenge(purpose = "session_proof", deviceUid = "-"),
                SESSION_ID,
            )
        }

        assertTrue(invalidSession.isFailure)
        assertTrue(invalidPurpose.isFailure)
        assertTrue(invalidProfile.isFailure)
        assertTrue(invalidDevice.isFailure)
    }

    @Test
    fun rejectsNonCanonicalOrWrongLengthChallenges() {
        val nonCanonical = runCatching {
            DeviceProofMessage.build(registrationChallenge(challenge = "B".repeat(43)), SESSION_ID)
        }
        val wrongLength = runCatching {
            DeviceProofMessage.build(registrationChallenge(challenge = "A".repeat(42)), SESSION_ID)
        }

        assertTrue(nonCanonical.isFailure)
        assertTrue(wrongLength.isFailure)
    }

    private fun registrationChallenge(
        purpose: String = "registration",
        deviceUid: String = "-",
        profileId: String = "42",
        challenge: String = CHALLENGE,
    ) = DeviceProofChallenge(
        challengeId = CHALLENGE_ID,
        challenge = challenge,
        purpose = purpose,
        profileId = profileId,
        deviceUid = deviceUid,
        expiresAt = "2026-11-28T05:00:00Z",
    )

    companion object {
        private const val CHALLENGE_ID = "123e4567-e89b-42d3-a456-426614174000"
        private const val SESSION_ID = "123e4567-e89b-42d3-a456-426614174003"
        private const val DEVICE_UID = "123e4567-e89b-42d3-a456-426614174002"
        private const val CHALLENGE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
