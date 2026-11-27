package com.tacresq.app.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Non-exportable P-256 signing key reserved for TACResQ device proofs.
 *
 * This alias is intentionally separate from SessionStore's AES-GCM encryption key.
 * The class is not wired into login, SOS, responder, or Admin flows until the server
 * verifier is deployed and the device-registration UX/recovery policy is approved.
 *
 * Signatures use SHA256withECDSA and Android Keystore's ASN.1 DER output. The server
 * verifier converts DER to WebCrypto's P1363 representation before signature checking.
 */
class DeviceProofKeyStore {
    @Synchronized
    fun publicKeySpkiBase64(): String {
        val encoded = loadOrCreateKeyPair().public.encoded
            ?: throw DeviceProofKeyStoreException("Android Keystore did not return an encoded public key.")
        return Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    /**
     * Signs the exact UTF-8 TACRESQ-DEVICE-PROOF v1 message supplied by the caller.
     * The private key never leaves Android Keystore.
     */
    @Synchronized
    fun sign(message: ByteArray): ByteArray {
        require(message.isNotEmpty() && message.size <= MAX_PROOF_MESSAGE_BYTES) {
            "Device proof message must contain between 1 and $MAX_PROOF_MESSAGE_BYTES bytes."
        }
        val privateKey = loadOrCreateKeyPair().private
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(message)
        val derSignature = signer.sign()
        if (derSignature.size !in MIN_DER_SIGNATURE_BYTES..MAX_DER_SIGNATURE_BYTES
            || derSignature[0] != DER_SEQUENCE_TAG
        ) {
            throw DeviceProofKeyStoreException("Android Keystore returned an invalid ECDSA signature encoding.")
        }
        return derSignature
    }

    /** Returns whether the versioned signing-key alias is present without exporting key material. */
    @Synchronized
    fun hasKey(): Boolean = loadKeyStore().containsAlias(DEVICE_PROOF_KEY_ALIAS)

    /**
     * Explicit recovery hook only. Do not call this as an automatic retry: deleting a
     * registered key requires a server-side device revocation/re-registration workflow.
     */
    @Synchronized
    fun deleteForRecovery() {
        loadKeyStore().deleteEntry(DEVICE_PROOF_KEY_ALIAS)
    }

    private fun loadOrCreateKeyPair(): KeyPair {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(DEVICE_PROOF_KEY_ALIAS)) {
            val privateKey = keyStore.getKey(DEVICE_PROOF_KEY_ALIAS, null) as? PrivateKey
                ?: throw DeviceProofKeyStoreException("The registered device signing key is unavailable.")
            val publicKey = keyStore.getCertificate(DEVICE_PROOF_KEY_ALIAS)?.publicKey
                ?: throw DeviceProofKeyStoreException("The registered device public key is unavailable.")
            validatePublicKey(publicKey)
            return KeyPair(publicKey, privateKey)
        }

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                DEVICE_PROOF_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKeyPair().also { pair -> validatePublicKey(pair.public) }
    }

    private fun validatePublicKey(publicKey: java.security.PublicKey) {
        val ecPublicKey = publicKey as? ECPublicKey
            ?: throw DeviceProofKeyStoreException("The stored device key is not an EC public key.")
        if (ecPublicKey.params.curve.field.fieldSize != P256_FIELD_SIZE
            || ecPublicKey.params.order.bitLength() != P256_FIELD_SIZE
        ) {
            throw DeviceProofKeyStoreException("The stored device key is not a P-256 key.")
        }
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        internal const val DEVICE_PROOF_KEY_ALIAS = "tacresq_device_proof_p256_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val P256_CURVE = "secp256r1"
        private const val P256_FIELD_SIZE = 256
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val MAX_PROOF_MESSAGE_BYTES = 2048
        private const val MIN_DER_SIGNATURE_BYTES = 8
        private const val MAX_DER_SIGNATURE_BYTES = 72
        private val DER_SEQUENCE_TAG = 0x30.toByte()
    }
}

class DeviceProofKeyStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
