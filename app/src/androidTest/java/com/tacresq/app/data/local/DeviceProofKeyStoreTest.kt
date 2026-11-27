package com.tacresq.app.data.local

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun removeOnlyTheDeviceProofAliasBeforeEachTest() {
        store.deleteForRecovery()
    }

    @After
    fun removeOnlyTheDeviceProofAliasAfterEachTest() {
        store.deleteForRecovery()
    }

    @Test
    fun publicKeyIsStableAndUsesP256Spki() {
        val first = store.getOrCreatePublicKey()
        val second = store.getOrCreatePublicKey()

        assertEquals(first.spkiBase64, second.spkiBase64)
        val encoded = Base64.decode(first.spkiBase64, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

        assertEquals("EC", publicKey.algorithm)
        assertEquals(256, (publicKey as java.security.interfaces.ECKey).params.curve.field.fieldSize)
        assertTrue(DeviceProofKeySecurityLevel.values().contains(first.securityLevel))
    }

    @Test
    fun sha256WithEcdsaSignatureVerifiesAndUsesDerSequence() {
        val publicKey = store.getOrCreatePublicKey()
        val message = "TACRESQ-DEVICE-PROOF\nv1\nsynthetic-registration".toByteArray(Charsets.UTF_8)
        val signatureBytes = store.sign(message)

        assertTrue(signatureBytes.size in 8..72)
        assertEquals(0x30, signatureBytes[0].toInt() and 0xff)

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(
            KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.decode(publicKey.spkiBase64, Base64.DEFAULT))
            )
        )
        verifier.update(message)
        assertTrue(verifier.verify(signatureBytes))
    }

    @Test
    fun alteredMessageDoesNotVerify() {
        val publicKey = store.getOrCreatePublicKey()
        val signatureBytes = store.sign("original-device-proof".toByteArray(Charsets.UTF_8))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(
            KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.decode(publicKey.spkiBase64, Base64.DEFAULT))
            )
        )
        verifier.update("altered-device-proof".toByteArray(Charsets.UTF_8))

        assertFalse(verifier.verify(signatureBytes))
    }

    @Test
    fun explicitRecoveryDeletesOnlyTheVersionedProofAliasAndCreatesANewKeyOnRequest() {
        val first = store.getOrCreatePublicKey()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keyStore.containsAlias(DeviceProofKeyStore.KEY_ALIAS))
        assertNotEquals("tacresq_session_aes_gcm_v1", DeviceProofKeyStore.KEY_ALIAS)

        store.deleteForRecovery()
        assertFalse(keyStoreContainsProofAlias())

        val second = store.getOrCreatePublicKey()
        assertNotEquals(first.spkiBase64, second.spkiBase64)
        assertTrue(keyStoreContainsProofAlias())
    }

    @Test
    fun signingRequiresAnExistingKeyAndBoundsTheMessage() {
        val missingKeyFailure = runCatching { store.sign(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(missingKeyFailure is IllegalStateException)

        store.getOrCreatePublicKey()
        val oversized = ByteArray(2049)
        val oversizedFailure = runCatching { store.sign(oversized) }.exceptionOrNull()
        assertTrue(oversizedFailure is IllegalArgumentException)
    }

    private fun keyStoreContainsProofAlias(): Boolean =
        KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            containsAlias(DeviceProofKeyStore.KEY_ALIAS)
        }
}
