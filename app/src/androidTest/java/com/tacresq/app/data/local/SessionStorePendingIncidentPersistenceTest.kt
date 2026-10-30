package com.tacresq.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Preferences DataStore + Android Keystore path.
 *
 * Constructing a fresh SessionStore after writing simulates the app data-layer
 * being rebuilt after process recreation; it deliberately does not claim to
 * kill/restart the Android process itself.
 */
@RunWith(AndroidJUnit4::class)
class SessionStorePendingIncidentPersistenceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private lateinit var store: SessionStore

    @Before
    fun clearOnlyTestPendingEnvelope() = runBlocking {
        store = SessionStore(context)
        store.getPendingIncidentSubmission()?.let {
            store.clearPendingIncidentRequestId(it.requestId)
        }
    }

    @After
    fun removeTestPendingEnvelope() = runBlocking {
        store.getPendingIncidentSubmission()?.let {
            store.clearPendingIncidentRequestId(it.requestId)
        }
    }

    @Test
    fun exactPendingPayloadAndRequestIdSurviveStoreRecreationAndLogout() = runBlocking {
        val payload = """{"type":"medical","latitude":11.244,"longitude":125.003}"""
        val saved = store.getOrCreatePendingIncident("fingerprint-medical-1", payload)

        // Logout clears credentials, but must preserve an ambiguous emergency submission.
        store.clear()

        val restored = SessionStore(context).getPendingIncidentSubmission()
        assertNotNull("Pending SOS must remain available after logout", restored)
        assertEquals(saved.requestId, restored?.requestId)
        assertEquals(saved.fingerprint, restored?.fingerprint)
        assertEquals(saved.payloadJson, restored?.payloadJson)
    }

    @Test
    fun differentPayloadCannotReplaceAnUnresolvedSubmission() = runBlocking {
        val saved = store.getOrCreatePendingIncident("fingerprint-first", """{"type":"medical"}""")

        val conflict = runCatching {
            SessionStore(context).getOrCreatePendingIncident(
                "fingerprint-second",
                """{"type":"fire"}""",
            )
        }.exceptionOrNull()

        assertNotNull("A different SOS must not overwrite the unresolved request", conflict)
        val stillSaved = store.getPendingIncidentSubmission()
        assertEquals(saved, stillSaved)
    }

    @Test
    fun clearingWithAnotherRequestIdDoesNotDeletePendingSubmission() = runBlocking {
        val saved = store.getOrCreatePendingIncident("fingerprint-safe-clear", """{"type":"medical"}""")

        store.clearPendingIncidentRequestId("not-the-saved-request-id")

        assertEquals(saved, store.getPendingIncidentSubmission())
    }

    @Test
    fun matchingRequestIdClearsOnlyTheSavedEnvelope() = runBlocking {
        val saved = store.getOrCreatePendingIncident("fingerprint-clear", """{"type":"medical"}""")

        store.clearPendingIncidentRequestId(saved.requestId)

        assertNull(store.getPendingIncidentSubmission())
    }
}
