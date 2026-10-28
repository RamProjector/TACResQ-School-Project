package com.tacresq.app.data.remote

import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.IncidentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import java.util.UUID

class FakeTacApiChatTest {
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
