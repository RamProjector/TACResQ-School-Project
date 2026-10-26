package com.tacresq.app.data.remote

import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.IncidentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class FakeTacApiNotificationTest {
    @Test
    fun assignmentAndStatusNotificationsAreRecipientScopedAndReadIsIdempotent() = runBlocking {
        var currentToken: String? = null
        val api = FakeTacApi(tokenProvider = { currentToken })

        val requesterSession = api.login(LoginRequest("requester@demo.com", "demo123"))
        currentToken = requesterSession.token
        val incident = api.createIncident(
            CreateIncidentRequest(
                type = IncidentType.MEDICAL,
                description = "Synthetic notification test",
                latitude = 11.244,
                longitude = 125.003,
                accuracyM = 8f,
                clientRequestId = UUID.randomUUID().toString(),
            ),
        )

        val responderSession = api.login(LoginRequest("responder@demo.com", "demo123"))
        currentToken = responderSession.token
        api.setDutyStatus(DutyStatusRequest(DutyStatus.AVAILABLE))

        val dispatcherSession = api.login(LoginRequest("dispatcher@demo.com", "demo123"))
        currentToken = dispatcherSession.token
        api.assignIncidentTeam(incident.id, AssignIncidentTeamRequest(listOf(responderSession.user.id)))

        currentToken = requesterSession.token
        val requesterAssignmentAlerts = api.myNotifications(50)
        assertEquals(1, requesterAssignmentAlerts.size)
        assertEquals("incident_assigned", requesterAssignmentAlerts.single().eventType)
        assertEquals("Response unit assigned", requesterAssignmentAlerts.single().title)
        val requesterAlertId = requesterAssignmentAlerts.single().id

        currentToken = responderSession.token
        val responderAlerts = api.myNotifications(50)
        assertEquals(1, responderAlerts.size)
        assertEquals("New incident assignment", responderAlerts.single().title)
        assertFalse(api.markNotificationRead(requesterAlertId))
        assertTrue(api.markNotificationRead(responderAlerts.single().id))
        assertTrue(api.markNotificationRead(responderAlerts.single().id))
        assertNotNull(api.myNotifications(50).single().readAt)

        api.updateMyAssignmentStatus(incident.id, UpdateStatusRequest(IncidentStatus.EN_ROUTE))
        currentToken = requesterSession.token
        val requesterAfterStatusChange = api.myNotifications(50)
        assertEquals(2, requesterAfterStatusChange.size)
        assertTrue(requesterAfterStatusChange.any { it.eventType == "incident_status_changed" })
        assertTrue(requesterAfterStatusChange.any { it.title == "Response unit assigned" && it.readAt == null })
    }
}
