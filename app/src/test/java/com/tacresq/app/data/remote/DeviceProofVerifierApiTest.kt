package com.tacresq.app.data.remote

import com.google.gson.JsonParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException

class DeviceProofVerifierApiTest {
    @Test
    fun registrationChallengeUsesAuthenticatedSupabaseFunctionRequest() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"challenge_id":"$CHALLENGE_ID","challenge":"$CHALLENGE","purpose":"registration","profile_id":"42","device_uid":"-","expires_at":"2026-11-28T05:00:00Z"}""",
            ),
        )
        server.start()
        try {
            val api = DeviceProofVerifierApi.createForTesting(server.url("/").toString(), PUBLISHABLE_KEY)
            val result = api.issueRegistrationChallenge(ACCESS_TOKEN)
            val request = server.takeRequest()
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject

            assertEquals("POST", request.method)
            assertEquals("/functions/v1/tacresq-device-verifier", request.path)
            assertEquals(PUBLISHABLE_KEY, request.getHeader("apikey"))
            assertEquals("Bearer $ACCESS_TOKEN", request.getHeader("Authorization"))
            assertTrue(request.getHeader("traceparent").orEmpty().startsWith("00-"))
            assertEquals("issue", body.get("action").asString)
            assertEquals("registration", body.get("purpose").asString)
            assertFalse(body.has("deviceId"))
            assertEquals(CHALLENGE_ID, result.challengeId)
            assertEquals(CHALLENGE, result.challenge)
            assertEquals("42", result.profileId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun registrationVerificationSendsOnlyTheExpectedProofFields() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"device_id":"$DEVICE_ID","device_uid":"$DEVICE_UID","trust_status":"active","verified_at":"2026-11-28T05:00:00Z"}""",
            ),
        )
        server.start()
        try {
            val api = DeviceProofVerifierApi.createForTesting(server.url("/").toString(), PUBLISHABLE_KEY)
            val challenge = registrationChallenge()
            val result = api.verifyRegistration(
                accessToken = ACCESS_TOKEN,
                challenge = challenge,
                signatureDerBase64Url = SIGNATURE,
                publicKeySpkiBase64 = PUBLIC_KEY,
                deviceName = "  Test phone  ",
                deviceModel = "  Pixel test  ",
                appVersion = "  1.0.0  ",
            )
            val request = server.takeRequest()
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject

            assertEquals("verify", body.get("action").asString)
            assertEquals(CHALLENGE_ID, body.get("challengeId").asString)
            assertEquals(SIGNATURE, body.get("signatureDerBase64Url").asString)
            assertEquals(PUBLIC_KEY, body.get("publicKeySpkiBase64").asString)
            assertEquals("Test phone", body.get("deviceName").asString)
            assertEquals("Pixel test", body.get("deviceModel").asString)
            assertEquals("1.0.0", body.get("appVersion").asString)
            assertEquals(DEVICE_ID, result.deviceId)
            assertEquals(DEVICE_UID, result.deviceUid)
            assertEquals("active", result.trustStatus)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sessionProofChallengeIsBoundToTheRequestedDevice() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"challenge_id":"$CHALLENGE_ID","challenge":"$CHALLENGE","purpose":"session_proof","profile_id":"42","device_uid":"$DEVICE_UID","expires_at":"2026-11-28T05:00:00Z"}""",
            ),
        )
        server.start()
        try {
            val api = DeviceProofVerifierApi.createForTesting(server.url("/").toString(), PUBLISHABLE_KEY)
            val result = api.issueSessionProofChallenge(ACCESS_TOKEN, DEVICE_ID)
            val request = server.takeRequest()
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject

            assertEquals(DEVICE_ID, body.get("deviceId").asString)
            assertEquals("session_proof", body.get("purpose").asString)
            assertEquals(DEVICE_UID, result.deviceUid)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sessionProofNeverSendsAReplacementPublicKey() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"device_id":"$DEVICE_ID","device_uid":"$DEVICE_UID","trust_status":"active","verified_at":"2026-11-28T05:00:00Z"}""",
            ),
        )
        server.start()
        try {
            val api = DeviceProofVerifierApi.createForTesting(server.url("/").toString(), PUBLISHABLE_KEY)
            api.verifySessionProof(
                ACCESS_TOKEN,
                registrationChallenge(purpose = "session_proof", deviceUid = DEVICE_UID),
                SIGNATURE,
            )
            val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals("verify", body.get("action").asString)
            assertEquals(CHALLENGE_ID, body.get("challengeId").asString)
            assertEquals(SIGNATURE, body.get("signatureDerBase64Url").asString)
            assertFalse(body.has("publicKeySpkiBase64"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun malformedInputsAreRejectedBeforeNetworkAndHttpErrorsArePreserved() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Valid authenticated session required"}"""))
        server.start()
        try {
            val api = DeviceProofVerifierApi.createForTesting(server.url("/").toString(), PUBLISHABLE_KEY)
            val invalidToken = runCatching { api.issueRegistrationChallenge("token with spaces") }
            assertTrue(invalidToken.isFailure)
            assertEquals(0, server.requestCount)

            val httpFailure = runCatching { api.issueRegistrationChallenge(ACCESS_TOKEN) }.exceptionOrNull()
            assertTrue(httpFailure is HttpException)
            assertEquals(401, (httpFailure as HttpException).code())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun nonLoopbackHttpOriginsAreRejected() {
        val failure = runCatching {
            DeviceProofVerifierApi.createForTesting("http://example.com/", PUBLISHABLE_KEY)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun registrationChallenge(
        purpose: String = "registration",
        deviceUid: String = "-",
    ) = DeviceProofChallenge(
        challengeId = CHALLENGE_ID,
        challenge = CHALLENGE,
        purpose = purpose,
        profileId = "42",
        deviceUid = deviceUid,
        expiresAt = "2026-11-28T05:00:00Z",
    )

    companion object {
        private const val ACCESS_TOKEN = "synthetic.jwt.access-token"
        private const val PUBLISHABLE_KEY = "sb_publishable_test_key"
        private const val CHALLENGE_ID = "123e4567-e89b-42d3-a456-426614174000"
        private const val CHALLENGE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        private const val SIGNATURE = "AQIDBAUGBwgJCgsM"
        private const val PUBLIC_KEY = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcY"
        private const val DEVICE_ID = "123e4567-e89b-42d3-a456-426614174001"
        private const val DEVICE_UID = "123e4567-e89b-42d3-a456-426614174002"
    }
}
