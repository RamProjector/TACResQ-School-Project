package com.tacresq.app.ui.common

import com.tacresq.app.data.model.Incident
import com.tacresq.app.data.model.IncidentStatus
import com.tacresq.app.data.model.IncidentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentLocationTextTest {
    private fun incident(
        latitude: Double? = null,
        longitude: Double? = null,
        source: String? = "none",
        accuracyM: Float? = null,
    ) = Incident(
        id = 1L,
        type = IncidentType.MEDICAL,
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        status = IncidentStatus.PENDING,
        locationSource = source,
    )

    @Test
    fun bothMissingCoordinatesAreReportedAsNoPinWhenNoLocationWasSelected() {
        assertEquals("No location pin", incidentLocationText(incident()))
    }

    @Test
    fun oneMissingCoordinateIsReportedAsIncomplete() {
        assertEquals(
            "Location coordinates incomplete — verify pin",
            incidentLocationText(incident(latitude = 11.2, longitude = null)),
        )
        assertEquals(
            "Location coordinates incomplete — verify pin",
            incidentLocationText(incident(latitude = null, longitude = 125.0)),
        )
    }

    @Test
    fun outOfRangeOrNonFiniteCoordinatesAreRejected() {
        assertTrue(incidentLocationText(incident(latitude = 91.0, longitude = 125.0)).contains("invalid"))
        assertTrue(incidentLocationText(incident(latitude = Double.NaN, longitude = 125.0)).contains("invalid"))
    }

    @Test
    fun malformedGpsAccuracyIsNeverPresentedAsARealMeasurement() {
        val noAccuracy = incidentLocationText(
            incident(latitude = 11.2, longitude = 125.0, source = "gps", accuracyM = Float.NaN),
        )
        val negativeAccuracy = incidentLocationText(
            incident(latitude = 11.2, longitude = 125.0, source = "gps", accuracyM = -1f),
        )
        val validAccuracy = incidentLocationText(
            incident(latitude = 11.2, longitude = 125.0, source = "gps", accuracyM = 12.2f),
        )

        assertTrue(noAccuracy.contains("accuracy unknown"))
        assertTrue(negativeAccuracy.contains("accuracy unknown"))
        assertTrue(validAccuracy.contains("±13 m"))
    }
}
