package com.tacresq.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatImageFingerprintTest {
    @Test
    fun sameImageBytesProduceStableRetryFingerprint() {
        val original = byteArrayOf(0, 1, 2, 3, 4, 5)
        val reselected = byteArrayOf(0, 1, 2, 3, 4, 5)

        assertEquals(chatImageSha256(original), chatImageSha256(reselected))
        assertEquals(64, chatImageSha256(original).length)
    }

    @Test
    fun differentImageBytesCannotReuseThePendingRetryIdentity() {
        val original = byteArrayOf(1, 2, 3)
        val different = byteArrayOf(1, 2, 4)

        assertNotEquals(chatImageSha256(original), chatImageSha256(different))
    }
}
