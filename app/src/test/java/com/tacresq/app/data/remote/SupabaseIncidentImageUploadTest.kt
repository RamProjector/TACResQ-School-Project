package com.tacresq.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class SupabaseIncidentImageUploadTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(): TacChatApi =
        SupabaseTacApi.createForTesting(
            tokenProvider = { "test-session-token" },
            baseUrl = server.url("/").toString(),
        ) as TacChatApi

    private fun reservation(consumed: Boolean, messageId: String) = """
        {
          "reservation_id": "reservation-$messageId",
          "object_path": "1/42/$messageId.jpg",
          "expires_at": "2026-11-22T15:00:00Z",
          "consumed": $consumed
        }
    """.trimIndent()

    private fun finalizedMessage(messageId: String) = """
        {
          "id": 91,
          "incident_id": 1,
          "sender_id": 42,
          "message": "Photo",
          "created_at": "2026-11-22T14:59:00Z",
          "attachment_path": "1/42/$messageId.jpg",
          "attachment_mime_type": "image/jpeg",
          "attachment_size_bytes": 4
        }
    """.trimIndent()

    @Test
    fun consumedReservationSkipsDuplicateStorageUploadAndFinalizesExistingMessage() = runBlocking {
        val messageId = "20000000-0000-4000-8000-000000000101"
        server.enqueue(MockResponse().setResponseCode(200).setBody(reservation(consumed = true, messageId)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(finalizedMessage(messageId)))

        val result = api().sendIncidentImageMessage(
            incidentId = 1,
            senderId = 42,
            message = "Photo",
            clientMessageId = messageId,
            mimeType = "image/jpeg",
            imageBytes = byteArrayOf(1, 2, 3, 4),
        )

        assertEquals("1/42/$messageId.jpg", result.attachmentPath)
        assertNotNull(result.id)
        assertEquals(2, server.requestCount)
        assertEquals("/rest/v1/rpc/api_reserve_incident_image_upload", server.takeRequest().path)
        assertEquals("/rest/v1/rpc/api_send_incident_image_message", server.takeRequest().path)
    }

    @Test
    fun liveReservationUploadsBeforeFinalization() = runBlocking {
        val messageId = "20000000-0000-4000-8000-000000000102"
        server.enqueue(MockResponse().setResponseCode(200).setBody(reservation(consumed = false, messageId)))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(finalizedMessage(messageId)))

        val result = api().sendIncidentImageMessage(
            incidentId = 1,
            senderId = 42,
            message = "Photo",
            clientMessageId = messageId,
            mimeType = "image/jpeg",
            imageBytes = byteArrayOf(1, 2, 3, 4),
        )

        assertEquals("1/42/$messageId.jpg", result.attachmentPath)
        assertEquals(3, server.requestCount)
        assertEquals("/rest/v1/rpc/api_reserve_incident_image_upload", server.takeRequest().path)
        assertEquals(
            "/storage/v1/object/incident-chat-attachments/1/42/$messageId.jpg",
            server.takeRequest().path,
        )
        assertEquals("/rest/v1/rpc/api_send_incident_image_message", server.takeRequest().path)
    }
}
