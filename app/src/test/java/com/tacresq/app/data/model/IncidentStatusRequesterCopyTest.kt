package com.tacresq.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class IncidentStatusRequesterCopyTest {
    @Test
    fun requesterLabelsDescribeResponseProgressRatherThanInternalDispatchActions() {
        assertEquals("Request received", IncidentStatus.PENDING.requesterLabel)
        assertEquals("Team assigned", IncidentStatus.ASSIGNED.requesterLabel)
        assertEquals("Team deployed", IncidentStatus.EN_ROUTE.requesterLabel)
        assertEquals("Team arrived", IncidentStatus.ON_SCENE.requesterLabel)
        assertEquals("Transport in progress", IncidentStatus.TRANSPORTING.requesterLabel)
        assertEquals("Response completed", IncidentStatus.RESOLVED.requesterLabel)
    }

    @Test
    fun requesterDescriptionsDoNotTreatGpsOrMessagingAsStatusEvidence() {
        assertEquals(
            "Your emergency request was received. A response team has not yet been assigned.",
            IncidentStatus.PENDING.requesterDescription,
        )
        assertEquals(
            "Dispatch has recorded that the assigned team has been deployed.",
            IncidentStatus.EN_ROUTE.requesterDescription,
        )
        assertEquals(
            "Dispatch has recorded that the team has arrived at the incident location.",
            IncidentStatus.ON_SCENE.requesterDescription,
        )
        assertEquals(
            "Dispatch has marked this response as completed.",
            IncidentStatus.RESOLVED.requesterDescription,
        )
    }
}
