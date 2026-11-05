package com.tacresq.app.data.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteProviderDisclosureTest {
    @Test
    fun namesExternalGeoapifyRecipient() {
        assertEquals(
            "Geoapify, an external routing provider",
            RouteProviderDisclosure("geoapify").consentRecipient(),
        )
    }

    @Test
    fun namesPrivatelyOperatedOsrmRecipient() {
        assertEquals(
            "TACResQ's privately operated OSRM routing service",
            RouteProviderDisclosure("private_osrm").consentRecipient(),
        )
    }

    @Test
    fun unknownProviderCannotProduceConsentCopy() {
        assertNull(RouteProviderDisclosure("unrecognized").consentRecipient())
    }
}
