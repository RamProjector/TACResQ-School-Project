package com.tacresq.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import java.io.File
import java.util.UUID

/**
 * Exercises the real Preferences DataStore + Android Keystore path.
 *
 * Each test injects its own file-backed Preferences DataStore. This avoids
 * touching the production tacresq_session file regardless of Android storage context.
 * Recreating SessionStore with the same injected DataStore simulates rebuilding the
 * data layer; it does not claim to kill/restart the Android process itself.
 */
@RunWith(AndroidJUnit4::class)
class SessionStorePendingIncidentPersistenceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private lateinit var store: SessionStore
    private lateinit var isolatedDataStore: DataStore<Preferences>
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var testStoreFile: File
    private var testRequestId: String? = null

    @Before
    fun prepareIsolatedStore(): Unit {
        testStoreFile = File(context.cacheDir, "tacresq-session-test-${UUID.randomUUID()}.preferences_pb")
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        isolatedDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { testStoreFile },
        )
        runBlocking {
            store = SessionStore(context, dataStore = isolatedDataStore)
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
            dataStoreScope.cancel()
            testStoreFile.delete()
        }
    }

    @Test
    fun exactPendingPayloadAndRequestIdSurviveStoreRecreationAndLogout() = runBlocking {
        val payload = """{"type":"medical","latitude":11.244,"longitude":125.003}"""
        val saved = createPending("medical-1", payload)

        // Logout clears credentials, but must preserve an ambiguous emergency submission.
        store.clear()

        val restored = SessionStore(context, dataStore = isolatedDataStore).getPendingIncidentSubmission()
        assertNotNull("Pending SOS must remain available after logout", restored)
        assertEquals(saved.requestId, restored?.requestId)
        assertEquals(saved.fingerprint, restored?.fingerprint)
        assertEquals(saved.payloadJson, restored?.payloadJson)
    }

    @Test
    fun differentPayloadCannotReplaceAnUnresolvedSubmission() = runBlocking {
        val saved = createPending("first", """{"type":"medical"}""")

        val conflict = runCatching {
            SessionStore(context, dataStore = isolatedDataStore).getOrCreatePendingIncident(
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
        val firstStore = SessionStore(context, dataStore = isolatedDataStore)
        val secondStore = SessionStore(context, dataStore = isolatedDataStore)

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
