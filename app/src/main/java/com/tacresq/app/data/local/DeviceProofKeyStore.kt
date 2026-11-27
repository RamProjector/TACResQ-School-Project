package com.tacresq.app.data.local

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.SecretKeyFactory

/**
 * Holds a per-install P-256 signing key in Android Keystore.
 *
 * This primitive is deliberately isolated: it is not called by login, SOS,
 * dispatch, chat, notification, or Admin flows. The private key is never
 * exported. A missing key may be created explicitly; an existing but broken
 * key is never silently replaced because its public key may already be
 * registered by the server.
 */
class DeviceProofKeyStore {
    @Synchronized
    fun getOrCreatePublicKey(): DeviceProofPublicKey {
        try {
            val pair = loadOrCreateKeyPair()
            return DeviceProofPublicKey(
                spkiBase64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP),
                securityLevel = readSecurityLevel(pair.private),
            )
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            throw DeviceProofKeyInvalidatedException(invalidated)
        }
    }

    /**
     * Signs the exact protocol message bytes using Android's DER-encoded
     * SHA256withECDSA output. The server converts this strict DER form to
     * WebCrypto's P1363 representation before verification.
     */
    @Synchronized
    fun sign(message: ByteArray): ByteArray {
        require(message.isNotEmpty() && message.size <= MAX_MESSAGE_BYTES) {
            "Device proof message must contain 1 to $MAX_MESSAGE_BYTES bytes."
        }
        try {
            val pair = loadExistingKeyPair()
            val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
            signer.initSign(pair.private)
            signer.update(message)
            return signer.sign().also(::requireDerSequence)
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            throw DeviceProofKeyInvalidatedException(invalidated)
        }
    }

    /**
     * Explicit recovery hook. Callers must first revoke the server-side device
     * association; this method never rotates or replaces a registered key.
     */
    @Synchronized
    internal fun deleteForRecovery() {
        keyStore().deleteEntry(KEY_ALIAS)
    }

    private fun loadOrCreateKeyPair(): KeyPair {
        val store = keyStore()
        if (store.containsAlias(KEY_ALIAS)) return readKeyPair(store)

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE_NAME))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKeyPair()
        // Read the persisted Android Keystore entry back instead of returning
        // transient generator state.
        return readKeyPair(keyStore())
    }

    private fun loadExistingKeyPair(): KeyPair {
        val store = keyStore()
        check(store.containsAlias(KEY_ALIAS)) {
            "Device proof key has not been created on this installation."
        }
        return readKeyPair(store)
    }

    private fun readKeyPair(store: KeyStore): KeyPair {
        val privateKey = store.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("Device proof private key is unavailable; explicit recovery is required.")
        val publicKey = store.getCertificate(KEY_ALIAS)?.publicKey
            ?: throw IllegalStateException("Device proof public key is unavailable; explicit recovery is required.")
        return KeyPair(publicKey, privateKey)
    }

    private fun readSecurityLevel(privateKey: PrivateKey): DeviceProofKeySecurityLevel {
        val factory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(privateKey, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> DeviceProofKeySecurityLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> DeviceProofKeySecurityLevel.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> DeviceProofKeySecurityLevel.SOFTWARE
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> DeviceProofKeySecurityLevel.UNKNOWN_SECURE
                else -> DeviceProofKeySecurityLevel.UNKNOWN
            }
        }
        @Suppress("DEPRECATION")
        return if (info.isInsideSecureHardware) {
            DeviceProofKeySecurityLevel.TRUSTED_ENVIRONMENT
        } else {
            DeviceProofKeySecurityLevel.SOFTWARE
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun requireDerSequence(signature: ByteArray) {
        require(signature.size in MIN_DER_SIGNATURE_BYTES..MAX_DER_SIGNATURE_BYTES && signature[0] == 0x30.toByte()) {
            "Android Keystore returned an invalid P-256 ECDSA signature."
        }
        val firstLength = signature[1].toInt() and 0xff
        val (bodyLength, bodyOffset) = if (firstLength < 0x80) {
            firstLength to 2
        } else {
            val count = firstLength and 0x7f
            require(count in 1..2 && 2 + count <= signature.size && signature[2] != 0.toByte()) {
                "Android Keystore returned a non-canonical ECDSA signature."
            }
            var length = 0
            repeat(count) { index ->
                length = (length shl 8) or (signature[2 + index].toInt() and 0xff)
            }
            require(length >= 0x80 && (count == 1 || length > 0xff)) {
                "Android Keystore returned a non-minimal ECDSA signature."
            }
            length to (2 + count)
        }
        require(bodyOffset + bodyLength == signature.size) {
            "Android Keystore returned a truncated or trailing ECDSA signature."
        }
    }

    companion object {
        internal const val KEY_ALIAS = "tacresq_device_proof_p256_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CURVE_NAME = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val MAX_MESSAGE_BYTES = 2048
        private const val MIN_DER_SIGNATURE_BYTES = 8
        private const val MAX_DER_SIGNATURE_BYTES = 72
    }
}

data class DeviceProofPublicKey(
    /** Standard Base64 encoding of the X.509 SubjectPublicKeyInfo DER bytes. */
    val spkiBase64: String,
    val securityLevel: DeviceProofKeySecurityLevel,
)

enum class DeviceProofKeySecurityLevel {
    STRONGBOX,
    TRUSTED_ENVIRONMENT,
    SOFTWARE,
    UNKNOWN_SECURE,
    UNKNOWN,
}

class DeviceProofKeyInvalidatedException(cause: Throwable) :
    IllegalStateException("The device proof key was invalidated. Revoke the registered device before recovery.", cause)
