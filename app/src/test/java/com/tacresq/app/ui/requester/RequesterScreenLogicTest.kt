package com.tacresq.app.ui.requester

import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.IncidentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequesterScreenLogicTest {
    @Test
    fun requesterNumbersAreScopedToItsHistoryAndKeepGlobalReference() {
        val incidents = listOf(
            incident(42L, "2026-03-05T10:00:00Z"),
            incident(9L, "2026-01-01T10:00:00Z"),
            incident(23L, "2026-02-01T10:00:00Z"),
        )

        assertEquals(1, requesterSequenceNumber(9L, incidents))
        assertEquals(2, requesterSequenceNumber(23L, incidents))
        assertEquals(3, requesterSequenceNumber(42L, incidents))
        assertEquals("Your request #3 · reference #42", requesterRequestLabel(42L, incidents))
        assertEquals("Request reference #100", requesterRequestLabel(100L, incidents))
    }

    @Test
    fun acceptedEditMatchesCanonicalBlankTextAndTinyCoordinateRoundTrip() {
        val returned = incident(8L, "2026-01-01T10:00:00Z").copy(
            description = null,
            locationNotes = null,
            latitude = 11.2443004,
            longitude = 125.0030003,
            locationSource = "manual",
        )

        assertTrue(
            requesterEditResponseMatches(
                updated = returned,
                type = IncidentType.MEDICAL,
                description = "   ",
                locationNotes = "",
                latitude = 11.2443,
                longitude = 125.003,
                locationSource = "manual",
            ),
        )
    }

    @Test
    fun acceptedEditStillFlagsMeaningfulDifferenceOrMissingPin() {
        val returned = incident(8L, "2026-01-01T10:00:00Z").copy(
            latitude = 11.25,
            longitude = 125.003,
            locationSource = "manual",
        )

        assertFalse(
            requesterEditResponseMatches(
                updated = returned,
                type = IncidentType.MEDICAL,
                description = "",
                locationNotes = "",
                latitude = 11.2443,
                longitude = 125.003,
                locationSource = "manual",
            ),
        )
        assertFalse(
            requesterEditResponseMatches(
                updated = returned,
                type = IncidentType.MEDICAL,
                description = "",
                locationNotes = "",
                latitude = null,
                longitude = null,
                locationSource = "none",
            ),
        )
    }

    private fun incident(id: Long, createdAt: String) = Incident(
        id = id,
        type = IncidentType.MEDICAL,
        status = IncidentStatus.PENDING,
        createdAt = createdAt,
    )
}
