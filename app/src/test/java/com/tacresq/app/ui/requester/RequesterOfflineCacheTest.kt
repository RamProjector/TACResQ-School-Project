package com.tacresq.app.ui.requester

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.tacresq.app.data.local.REQUESTER_STATUS_CACHE_MAX_AGE_MILLIS
import com.tacresq.app.data.local.isRequesterStatusCacheFresh
import com.tacresq.app.data.model.CachedRequesterIncidentStatus
import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.IncidentType
import com.tacresq.app.data.model.toCachedRequesterIncidentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequesterOfflineCacheTest {
    @Test
    fun cachedSnapshotContainsOnlyAllowlistedStatusFields() {
        val incident = Incident(
            id = 42L,
            type = IncidentType.MEDICAL,
            description = "private narrative",
            latitude = 11.2,
            longitude = 125.1,
            locationNotes = "private landmark",
            status = IncidentStatus.PENDING,
            requesterName = "private name",
            requesterPhone = "private phone",
        )

        val snapshot = incident.toCachedRequesterIncidentStatus()
        assertEquals(CachedRequesterIncidentStatus(42L, IncidentType.MEDICAL, IncidentStatus.PENDING), snapshot)
        val json = Gson().toJson(snapshot)
        val keys = JsonParser.parseString(json).asJsonObject.keySet()
        assertEquals(setOf("incidentId", "type", "status"), keys)
        listOf("private narrative", "private landmark", "private name", "private phone", "11.2", "125.1")
            .forEach { value -> assertFalse("Snapshot leaked $value", json.contains(value)) }
    }

    @Test
    fun cachedStatusExpiresAtTwentyFourHoursAndRejectsFutureTimestamps() {
        val now = 1_800_000_000_000L
        assertTrue(isRequesterStatusCacheFresh(now, now))
        assertTrue(isRequesterStatusCacheFresh(now, now + REQUESTER_STATUS_CACHE_MAX_AGE_MILLIS))
        assertFalse(isRequesterStatusCacheFresh(now, now + REQUESTER_STATUS_CACHE_MAX_AGE_MILLIS + 1L))
        assertFalse(isRequesterStatusCacheFresh(now + 1L, now))
    }

    @Test
    fun incidentActionsRequireCurrentStatusAndNoRefreshInFlight() {
        assertFalse(canUseCurrentRequesterActions(hasCurrentIncidentStatus = false, refreshInProgress = false))
        assertFalse(canUseCurrentRequesterActions(hasCurrentIncidentStatus = true, refreshInProgress = true))
        assertTrue(canUseCurrentRequesterActions(hasCurrentIncidentStatus = true, refreshInProgress = false))
    }
}
