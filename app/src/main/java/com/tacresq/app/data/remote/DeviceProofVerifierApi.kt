package com.tacresq.app.data.remote

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.tacresq.app.AppConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Isolated transport for the Supabase device-verifier Edge Function.
 *
 * This client is intentionally not wired into login, SOS, dispatch, chat,
 * notifications, or Admin flows. The hosted verifier role, pooler identity,
 * device-level tests, and recovery controls must be approved before a caller
 * is added to an operational flow.
 */
class DeviceProofVerifierApi private constructor(
    private val wire: DeviceProofVerifierWire,
    private val publishableKey: String,
) {
    suspend fun issueRegistrationChallenge(accessToken: String): DeviceProofChallenge =
        issue(accessToken, DeviceProofPurpose.REGISTRATION, null)

    suspend fun issueSessionProofChallenge(
        accessToken: String,
        deviceId: String,
    ): DeviceProofChallenge {
        val normalizedDeviceId = UUID.fromString(deviceId).toString()
        return issue(accessToken, DeviceProofPurpose.SESSION_PROOF, normalizedDeviceId)
    }

    suspend fun verifyRegistration(
        accessToken: String,
        challenge: DeviceProofChallenge,
        signatureDerBase64Url: String,
        publicKeySpkiBase64: String,
        deviceName: String,
        deviceModel: String?,
        appVersion: String?,
    ): DeviceProofVerification {
        require(challenge.purpose == DeviceProofPurpose.REGISTRATION.wireValue) {
            "A registration challenge is required."
        }
        validateChallenge(challenge)
        validateSignature(signatureDerBase64Url)
        require(publicKeySpkiBase64.matches(BASE64_PATTERN) && publicKeySpkiBase64.length <= 4096) {
            "Device public key encoding is invalid."
        }
        val normalizedName = deviceName.trim()
        require(normalizedName.length in 1..80) { "Device name must contain 1 to 80 characters." }
        val normalizedModel = deviceModel?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedVersion = appVersion?.trim()?.takeIf { it.isNotEmpty() }
        require(normalizedModel == null || normalizedModel.length <= 120) { "Device model is too long." }
        require(normalizedVersion == null || normalizedVersion.length <= 80) { "App version is too long." }

        return verify(
            accessToken,
            DeviceProofVerifyRequest(
                challengeId = challenge.challengeId,
                challenge = challenge.challenge,
                signatureDerBase64Url = signatureDerBase64Url,
                publicKeySpkiBase64 = publicKeySpkiBase64,
                deviceName = normalizedName,
                deviceModel = normalizedModel,
                appVersion = normalizedVersion,
            ),
        )
    }

    suspend fun verifySessionProof(
        accessToken: String,
        challenge: DeviceProofChallenge,
        signatureDerBase64Url: String,
    ): DeviceProofVerification {
        require(challenge.purpose == DeviceProofPurpose.SESSION_PROOF.wireValue) {
            "A session-proof challenge is required."
        }
        validateChallenge(challenge)
        validateSignature(signatureDerBase64Url)
        return verify(
            accessToken,
            DeviceProofVerifyRequest(
                challengeId = challenge.challengeId,
                challenge = challenge.challenge,
                signatureDerBase64Url = signatureDerBase64Url,
            ),
        )
    }

    private suspend fun issue(
        accessToken: String,
        purpose: DeviceProofPurpose,
        deviceId: String?,
    ): DeviceProofChallenge {
        val response = wire.issue(
            publishableKey,
            bearer(accessToken),
            DeviceProofIssueRequest(
                purpose = purpose.wireValue,
                deviceId = deviceId,
            ),
        )
        validateChallenge(response)
        require(response.purpose == purpose.wireValue) {
            "Device verifier returned an unexpected challenge purpose."
        }
        return response
    }

    private suspend fun verify(
        accessToken: String,
        request: DeviceProofVerifyRequest,
    ): DeviceProofVerification {
        val response = wire.verify(publishableKey, bearer(accessToken), request)
        require(
            runCatching { UUID.fromString(response.deviceId) }.isSuccess
                && response.deviceUid.matches(UUID_PATTERN)
                && response.trustStatus == "active"
                && response.verifiedAt.isNotBlank()
        ) {
            "Device verifier returned an invalid verification result."
        }
        return response
    }

    private fun bearer(accessToken: String): String {
        require(accessToken.isNotBlank() && accessToken.length <= MAX_ACCESS_TOKEN_LENGTH
            && accessToken.none(Char::isWhitespace)) {
            "A valid Supabase access token is required."
        }
        return "Bearer $accessToken"
    }

    private fun validateChallenge(challenge: DeviceProofChallenge) {
        require(runCatching { UUID.fromString(challenge.challengeId) }.isSuccess) {
            "Device challenge identifier is invalid."
        }
        require(challenge.challenge.matches(CHALLENGE_PATTERN)) {
            "Device challenge encoding is invalid."
        }
        require(challenge.profileId.matches(Regex("^[1-9][0-9]{0,18}$"))) {
            "Device challenge profile reference is invalid."
        }
        require(challenge.deviceUid == "-" || challenge.deviceUid.matches(UUID_PATTERN)) {
            "Device challenge device reference is invalid."
        }
        require(challenge.expiresAt.isNotBlank()) { "Device challenge expiry is missing." }
    }

    private fun validateSignature(signature: String) {
        require(signature.length in 11..96 && signature.matches(BASE64_URL_PATTERN)) {
            "Device proof signature encoding is invalid."
        }
    }

    companion object {
        private const val MAX_ACCESS_TOKEN_LENGTH = 8192
        private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        private val CHALLENGE_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
        private val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")
        private val BASE64_PATTERN = Regex("^[A-Za-z0-9+/]+={0,2}$")

        fun create(): DeviceProofVerifierApi = createInternal(
            AppConfig.SUPABASE_URL,
            AppConfig.SUPABASE_PUBLISHABLE_KEY,
            OkHttpClient.Builder()
                .addInterceptor(TraceparentInterceptor())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build(),
        )

        internal fun createForTesting(
            baseUrl: String,
            publishableKey: String,
            client: OkHttpClient = OkHttpClient.Builder()
                .addInterceptor(TraceparentInterceptor())
                .build(),
        ): DeviceProofVerifierApi = createInternal(baseUrl, publishableKey, client)

        private fun createInternal(
            baseUrl: String,
            publishableKey: String,
            client: OkHttpClient,
        ): DeviceProofVerifierApi {
            require(publishableKey.isNotBlank()) { "Supabase publishable key is required." }
            val parsed = baseUrl.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Supabase URL is invalid.")
            require(parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
                "Supabase URL must be a project origin."
            }
            require(parsed.scheme == "https" || (parsed.scheme == "http" && parsed.host in setOf("localhost", "127.0.0.1"))) {
                "Device verifier transport requires HTTPS outside loopback tests."
            }
            val retrofit = Retrofit.Builder()
                .baseUrl(parsed.toString().trimEnd('/') + "/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
                .build()
            return DeviceProofVerifierApi(
                retrofit.create(DeviceProofVerifierWire::class.java),
                publishableKey,
            )
        }
    }
}

private interface DeviceProofVerifierWire {
    @POST("functions/v1/tacresq-device-verifier")
    suspend fun issue(
        @Header("apikey") publishableKey: String,
        @Header("Authorization") authorization: String,
        @Body body: DeviceProofIssueRequest,
    ): DeviceProofChallenge

    @POST("functions/v1/tacresq-device-verifier")
    suspend fun verify(
        @Header("apikey") publishableKey: String,
        @Header("Authorization") authorization: String,
        @Body body: DeviceProofVerifyRequest,
    ): DeviceProofVerification
}

private data class DeviceProofIssueRequest(
    val action: String = "issue",
    val purpose: String,
    val deviceId: String?,
)

private data class DeviceProofVerifyRequest(
    val action: String = "verify",
    val challengeId: String,
    val challenge: String,
    val signatureDerBase64Url: String,
    val publicKeySpkiBase64: String? = null,
    val deviceName: String? = null,
    val deviceModel: String? = null,
    val appVersion: String? = null,
)

enum class DeviceProofPurpose(val wireValue: String) {
    REGISTRATION("registration"),
    SESSION_PROOF("session_proof"),
}

data class DeviceProofChallenge(
    @SerializedName("challenge_id") val challengeId: String,
    val challenge: String,
    val purpose: String,
    @SerializedName("profile_id") val profileId: String,
    @SerializedName("device_uid") val deviceUid: String,
    @SerializedName("expires_at") val expiresAt: String,
)

data class DeviceProofVerification(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_uid") val deviceUid: String,
    @SerializedName("trust_status") val trustStatus: String,
    @SerializedName("verified_at") val verifiedAt: String,
)
