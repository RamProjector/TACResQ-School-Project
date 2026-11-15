package com.tacresq.app.data.remote

import com.tacresq.app.data.model.DutyStatus
import com.tacresq.app.data.model.IncidentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTacApiClaimIncidentTest {

    @Test
    fun responderCanClaimAvailableIncidentWhenOnDuty() = runTest {
        var currentToken: String? = null
        val api = FakeTacApi(tokenProvider = { currentToken })

        // 1. Requester logs in and creates incident
        val requesterAuth = api.login(LoginRequest("requester@demo.com", "demo123"))
        currentToken = requesterAuth.token

        val created = api.createIncident(
            CreateIncidentRequest(
                type = IncidentType.MEDICAL,
                description = "Medical assistance needed",
                latitude = 11.243,
                longitude = 125.004,
                accuracyM = 10f,
                locationNotes = "Near park",
                locationRecordedAt = "2026-11-15T10:00:00Z",
                locationSource = "gps",
                clientRequestId = java.util.UUID.randomUUID().toString(),
            )
        )
        assertFalse(created.isClaimable)

        // 2. Dispatcher logs in and sets incident claimable
        val dispatcherAuth = api.login(LoginRequest("dispatcher@demo.com", "demo123"))
        currentToken = dispatcherAuth.token

        val updated = api.setIncidentClaimable(created.id, SetClaimableRequest(true))
        assertTrue(updated.isClaimable)

        // 3. Responder logs in, sets duty AVAILABLE, fetches claimable incidents
        val responderAuth = api.login(LoginRequest("responder@demo.com", "demo123"))
        currentToken = responderAuth.token

        api.setDutyStatus(DutyStatusRequest(DutyStatus.AVAILABLE))

        val claimableList = api.claimableIncidents()
        assertTrue(claimableList.any { it.id == created.id })

        // 4. Responder claims the incident
        val result = api.claimIncident(created.id)
        assertEquals("claimed", result.status)

        // 5. Incident appears in responder's myIncidents
        val mine = api.myIncidents()
        assertTrue(mine.any { it.id == created.id })
    }

    @Test
    fun claimFailsWhenOffDutyOrNotClaimable() = runTest {
        var currentToken: String? = null
        val api = FakeTacApi(tokenProvider = { currentToken })

        val requesterAuth = api.login(LoginRequest("requester@demo.com", "demo123"))
        currentToken = requesterAuth.token

        val created = api.createIncident(
            CreateIncidentRequest(
                type = IncidentType.FIRE,
                description = "Grass fire",
                latitude = 11.245,
                longitude = 125.006,
                accuracyM = 5f,
                locationNotes = null,
                locationRecordedAt = "2026-11-15T10:05:00Z",
                locationSource = "gps",
                clientRequestId = java.util.UUID.randomUUID().toString(),
            )
        )

        val responderAuth = api.login(LoginRequest("responder@demo.com", "demo123"))
        currentToken = responderAuth.token

        // Off duty claim attempt -> OFF_DUTY error
        val offDutyResult = api.claimIncident(created.id)
        assertEquals("error", offDutyResult.status)
        assertEquals("OFF_DUTY", offDutyResult.code)

        // Set duty available, but incident is not claimable yet
        api.setDutyStatus(DutyStatusRequest(DutyStatus.AVAILABLE))
        val notClaimableResult = api.claimIncident(created.id)
        assertEquals("error", notClaimableResult.status)
        assertEquals("NOT_CLAIMABLE", notClaimableResult.code)
    }
}
