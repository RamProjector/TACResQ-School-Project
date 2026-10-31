package com.tacresq.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Preferences DataStore + Android Keystore path.
 *
 * Device-protected storage isolates this test's DataStore file from the app's
 * credential-protected SOS envelope. Recreating SessionStore simulates rebuilding
 * the data layer; it does not claim to kill/restart the Android process itself.
 */
@RunWith(AndroidJUnit4::class)
class SessionStorePendingIncidentPersistenceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext.createDeviceProtectedStorageContext()

    private lateinit var store: SessionStore
    private var testRequestId: String? = null

    @Before
    fun prepareIsolatedStore(): Unit {
        runBlocking {
            store = SessionStore(context)
            // Clean up only an interrupted test's namespaced envelope. Never delete a
            // real/requester SOS while preparing instrumentation tests.
            store.getPendingIncidentSubmission()
                ?.takeIf { it.fingerprint.startsWith(TEST_FINGERPRINT_PREFIX) }
                ?.let { store.clearPendingIncidentRequestId(it.requestId) }
        }
    }

    @After
    fun removeOnlyThisTestsEnvelope(): Unit {
        runBlocking {
            testRequestId?.let { store.clearPendingIncidentRequestId(it) }
        }
    }

    @Test
    fun exactPendingPayloadAndRequestIdSurviveStoreRecreationAndLogout() = runBlocking {
        val payload = """{"type":"medical","latitude":11.244,"longitude":125.003}"""
        val saved = createPending("medical-1", payload)

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
        val saved = createPending("first", """{"type":"medical"}""")

        val conflict = runCatching {
            SessionStore(context).getOrCreatePendingIncident(
                fingerprint("second"),
                """{"type":"fire"}""",
            )
        }.exceptionOrNull()

        assertTrue(
            "A different SOS must fail with PendingIncidentConflictException",
            conflict is SessionStore.PendingIncidentConflictException,
        )
        assertEquals(saved, store.getPendingIncidentSubmission())
    }

    @Test
    fun concurrentSamePayloadSubmissionsShareOneIdempotencyKey() = runBlocking {
        val payload = """{"type":"medical","latitude":11.244,"longitude":125.003}"""
        val fingerprint = fingerprint("concurrent-same")
        val firstStore = SessionStore(context)
        val secondStore = SessionStore(context)

        val results = coroutineScope {
            val first = async { firstStore.getOrCreatePendingIncident(fingerprint, payload) }
            val second = async { secondStore.getOrCreatePendingIncident(fingerprint, payload) }
            first.await() to second.await()
        }

        testRequestId = results.first.requestId
        assertEquals(results.first.requestId, results.second.requestId)
        assertEquals(results.first.payloadJson, results.second.payloadJson)
    }

    @Test
    fun clearingWithAnotherRequestIdDoesNotDeletePendingSubmission() = runBlocking {
        val saved = createPending("safe-clear", """{"type":"medical"}""")

        store.clearPendingIncidentRequestId("not-the-saved-request-id")

        assertEquals(saved, store.getPendingIncidentSubmission())
    }

    @Test
    fun matchingRequestIdClearsOnlyTheSavedEnvelope() = runBlocking {
        val saved = createPending("matching-clear", """{"type":"medical"}""")

        store.clearPendingIncidentRequestId(saved.requestId)
        testRequestId = null

        assertNull(store.getPendingIncidentSubmission())
    }

    private suspend fun createPending(label: String, payload: String): SessionStore.PendingIncidentSubmission {
        val saved = store.getOrCreatePendingIncident(fingerprint(label), payload)
        testRequestId = saved.requestId
        return saved
    }

    private fun fingerprint(label: String) = "$TEST_FINGERPRINT_PREFIX$label"

    private companion object {
        const val TEST_FINGERPRINT_PREFIX = "tacresq-instrumentation-test:"
    }
}
