package com.tacresq.app.data.remote

import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.IncidentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import java.util.UUID

class FakeTacApiChatTest {
    @Test
    fun imageMessagesArePrivateToIncidentParticipantsAndDownloadable() = runBlocking {
        var currentToken: String? = null
        val api = FakeTacApi(tokenProvider = { currentToken })

        val requester = api.login(LoginRequest("requester@demo.com", "demo123"))
        currentToken = requester.token
        val incident = api.createIncident(
            CreateIncidentRequest(
                type = IncidentType.MEDICAL,
                description = "Synthetic image chat test",
                latitude = 11.247,
                longitude = 125.006,
                accuracyM = 8f,
                clientRequestId = UUID.randomUUID().toString(),
            ),
        )

        val responder = api.login(LoginRequest("responder@demo.com", "demo123"))
        currentToken = responder.token
        api.setDutyStatus(DutyStatusRequest(DutyStatus.AVAILABLE))

        val dispatcher = api.login(LoginRequest("dispatcher@demo.com", "demo123"))
        currentToken = dispatcher.token
        api.assignIncidentTeam(incident.id, AssignIncidentTeamRequest(listOf(responder.user.id)))

        val imageBytes = byteArrayOf(1, 2, 3, 4, 5)
        val messageId = UUID.randomUUID().toString()
        currentToken = responder.token
        val sent = api.sendIncidentImageMessage(
            incident.id, responder.user.id, "Entrance photo", messageId, "image/jpeg", imageBytes,
        )
        assertEquals("image/jpeg", sent.attachmentMimeType)
        assertEquals(imageBytes.size.toLong(), sent.attachmentSizeBytes)
        assertTrue(sent.attachmentPath!!.startsWith("${incident.id}/${responder.user.id}/"))

        currentToken = requester.token
        val visible = api.myIncidentMessages(incident.id, 50, null).single()
        assertEquals(sent.id, visible.id)
        assertArrayEquals(imageBytes, api.downloadIncidentImage(visible.attachmentPath!!))

        val driver = api.login(LoginRequest("driver@demo.com", "demo123"))
        currentToken = driver.token
        val denied = runCatching { api.downloadIncidentImage(sent.attachmentPath!!) }.exceptionOrNull()
        assertTrue(denied is HttpException)
        assertEquals(403, (denied as HttpException).code())
    }

    @Test
    fun incidentChatIsScopedAndClientRetriesAreIdempotent() = runBlocking {
        var currentToken: String? = null
        val api = FakeTacApi(tokenProvider = { currentToken })

        val requester = api.login(LoginRequest("requester@demo.com", "demo123"))
        currentToken = requester.token
        val incident = api.createIncident(
            CreateIncidentRequest(
                type = IncidentType.MEDICAL,
                description = "Synthetic chat authorization test",
                latitude = 11.247,
                longitude = 125.006,
                accuracyM = 8f,
                clientRequestId = UUID.randomUUID().toString(),
            ),
        )

        val responder = api.login(LoginRequest("responder@demo.com", "demo123"))
        currentToken = responder.token
        api.setDutyStatus(DutyStatusRequest(DutyStatus.AVAILABLE))

        val dispatcher = api.login(LoginRequest("dispatcher@demo.com", "demo123"))
        currentToken = dispatcher.token
        api.assignIncidentTeam(incident.id, AssignIncidentTeamRequest(listOf(responder.user.id)))

        currentToken = requester.token
        val clientMessageId = UUID.randomUUID().toString()
        val first = api.sendIncidentMessage(incident.id, "Please confirm the entrance.", clientMessageId)
        val retry = api.sendIncidentMessage(incident.id, "Please confirm the entrance.", clientMessageId)
        assertEquals(first.id, retry.id)

        val conflict = runCatching {
            api.sendIncidentMessage(incident.id, "Different content.", clientMessageId)
        }.exceptionOrNull()
        assertTrue(conflict is HttpException)
        assertEquals(409, (conflict as HttpException).code())

        currentToken = responder.token
        val responderMessages = api.myIncidentMessages(incident.id, 50, null)
        assertEquals(1, responderMessages.size)
        assertEquals(first.id, responderMessages.single().id)
        val reply = api.sendIncidentMessage(incident.id, "Unit is approaching the entrance.", UUID.randomUUID().toString())
        assertNotEquals(first.id, reply.id)

        val driver = api.login(LoginRequest("driver@demo.com", "demo123"))
        currentToken = driver.token
        val denied = runCatching {
            api.myIncidentMessages(incident.id, 50, null)
        }.exceptionOrNull()
        assertTrue(denied is HttpException)
        assertEquals(403, (denied as HttpException).code())

        currentToken = requester.token
        val requesterMessages = api.myIncidentMessages(incident.id, 50, null)
        assertEquals(2, requesterMessages.size)
        assertEquals("Please confirm the entrance.", requesterMessages.first().message)
        assertEquals("Unit is approaching the entrance.", requesterMessages.last().message)
        assertEquals(1, api.myNotifications(50).count { it.eventType == "incident_message" })
    }
}
