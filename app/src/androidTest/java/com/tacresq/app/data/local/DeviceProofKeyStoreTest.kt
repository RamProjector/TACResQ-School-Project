package com.tacresq.app.data.local

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceProofKeyStoreTest {
    private val store = DeviceProofKeyStore()

    @Before
    fun clearPriorTestKey() {
        store.deleteForRecovery()
    }

    @After
    fun removeTestKey() {
        store.deleteForRecovery()
    }

    @Test
    fun createsStableP256PublicKeyAndSignsWithAndroidDerEcdsa() {
        val message = "TACRESQ-DEVICE-PROOF\nv1\nsynthetic-instrumentation".toByteArray(Charsets.UTF_8)

        val firstPublicKey = store.publicKeySpkiBase64()
        val secondPublicKey = DeviceProofKeyStore().publicKeySpkiBase64()
        assertTrue(store.hasKey())
        assertArrayEquals(firstPublicKey.toByteArray(), secondPublicKey.toByteArray())

        val publicKeyBytes = Base64.decode(firstPublicKey, Base64.NO_WRAP)
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val signatureDer = store.sign(message)
        assertTrue(signatureDer.size in 8..72)
        assertTrue(signatureDer.first() == 0x30.toByte())

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(message)
        assertTrue(verifier.verify(signatureDer))
    }

    @Test
    fun signatureDoesNotVerifyAfterMessageMutation() {
        val message = "TACRESQ-DEVICE-PROOF\nv1\noriginal".toByteArray(Charsets.UTF_8)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(store.publicKeySpkiBase64(), Base64.NO_WRAP))
        )
        val signatureDer = store.sign(message)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(message + "-changed".toByteArray(Charsets.UTF_8))
        assertFalse(verifier.verify(signatureDer))
    }

    @Test
    fun explicitRecoveryDeletesAndRegeneratesASeparateKey() {
        val originalPublicKey = store.publicKeySpkiBase64()
        assertTrue(store.hasKey())

        store.deleteForRecovery()
        assertFalse(store.hasKey())

        val rotatedPublicKey = store.publicKeySpkiBase64()
        assertTrue(store.hasKey())
        assertNotEquals(originalPublicKey, rotatedPublicKey)
    }

    @Test
    fun deviceProofAliasIsNotTheSessionEncryptionAlias() {
        assertNotEquals("tacresq_session_aes_gcm_v1", DeviceProofKeyStore.DEVICE_PROOF_KEY_ALIAS)
    }
}
