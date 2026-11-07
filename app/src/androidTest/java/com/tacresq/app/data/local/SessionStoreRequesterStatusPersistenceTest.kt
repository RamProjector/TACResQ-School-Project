package com.tacresq.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the requester status snapshot using an isolated Preferences DataStore and Android Keystore. */
@RunWith(AndroidJUnit4::class)
class SessionStoreRequesterStatusPersistenceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private lateinit var store: SessionStore
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var testStoreFile: File

    @Before
    fun prepareIsolatedStore() {
        testStoreFile = File(context.cacheDir, "tacresq-status-cache-test-${UUID.randomUUID()}.preferences_pb")
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { testStoreFile },
        )
        store = SessionStore(context, dataStore = dataStore)
    }

    @After
    fun removeIsolatedStore() {
        dataStoreScope.cancel()
        testStoreFile.delete()
    }

    @Test
    fun encryptedSnapshotSurvivesStoreRecreationWithoutPlaintextAtRest() = runBlocking {
        val privateMarker = "status-only-cache-test"
        val statusesJson = "[{\"incidentId\":42,\"type\":\"MEDICAL\",\"status\":\"PENDING\"}]"
        store.saveRequesterIncidents(TEST_OWNER_ID, statusesJson)

        val encrypted = dataStore.data.first()[CACHE_KEY]
        assertNotNull(encrypted)
        assertFalse(encrypted.orEmpty().contains(statusesJson))
        assertFalse(encrypted.orEmpty().contains(privateMarker))

        val restored = SessionStore(context, dataStore = dataStore).getRequesterIncidents(TEST_OWNER_ID)
        assertNotNull(restored)
        assertEquals(TEST_OWNER_ID, restored?.ownerUserId)
        assertEquals(statusesJson, restored?.statusesJson)
        assertTrue((restored?.cachedAtEpochMillis ?: 0L) > 0L)
    }

    @Test
    fun requesterStatusSnapshotCannotBeReadByAnotherAccount() = runBlocking {
        store.saveRequesterIncidents(TEST_OWNER_ID, "[]")

        val failure = runCatching { store.getRequesterIncidents(OTHER_OWNER_ID) }.exceptionOrNull()

        assertTrue(failure is SessionStore.PendingIncidentConflictException)
        assertEquals("[]", store.getRequesterIncidents(TEST_OWNER_ID)?.statusesJson)
    }

    @Test
    fun logoutDeletesRequesterStatusSnapshot() = runBlocking {
        store.saveRequesterIncidents(TEST_OWNER_ID, "[]")

        store.clear()

        assertNull(dataStore.data.first()[CACHE_KEY])
        assertNull(store.getRequesterIncidents(TEST_OWNER_ID))
    }

    private companion object {
        const val TEST_OWNER_ID = 71L
        const val OTHER_OWNER_ID = 72L
        val CACHE_KEY = stringPreferencesKey("requester_incidents_v1")
    }
}
