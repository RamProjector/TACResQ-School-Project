package com.tacresq.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Exercises the encrypted chat outbox with isolated Preferences DataStore files.
 * Recreating SessionStore simulates rebuilding the data layer; it does not claim
 * to kill or restart the Android process itself.
 */
@RunWith(AndroidJUnit4::class)
class SessionStorePendingChatPersistenceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private lateinit var store: SessionStore
    private lateinit var isolatedDataStore: DataStore<Preferences>
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var testStoreFile: File

    @Before
    fun prepareIsolatedStore(): Unit {
        testStoreFile = File(context.cacheDir, "tacresq-chat-outbox-test-${UUID.randomUUID()}.preferences_pb")
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        isolatedDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { testStoreFile },
        )
        store = SessionStore(context, dataStore = isolatedDataStore)
    }

    @After
    fun removeOnlyThisTestsEnvelope(): Unit {
        runBlocking {
            runCatching {
                store.getPendingChatMessage(TEST_OWNER_ID)?.let {
                    store.clearPendingChatMessage(it.clientMessageId, TEST_OWNER_ID)
                }
            }
            dataStoreScope.cancel()
            testStoreFile.delete()
        }
    }

    @Test
    fun exactMessageAndClientIdSurviveStoreRecreationAndLogout() = runBlocking {
        val saved = record()
        store.savePendingChatMessage(saved)

        // An uncertain send must survive logout so the original account can retry it.
        store.clear()

        val restored = SessionStore(context, dataStore = isolatedDataStore)
            .getPendingChatMessage(TEST_OWNER_ID)
        assertEquals(saved, restored)
    }

    @Test
    fun pendingMessageCannotBeReadOrClearedByAnotherAccount() = runBlocking {
        val saved = record()
        store.savePendingChatMessage(saved)

        val readFailure = runCatching {
            store.getPendingChatMessage(OTHER_OWNER_ID)
        }.exceptionOrNull()
        val clearFailure = runCatching {
            store.clearPendingChatMessage(saved.clientMessageId, OTHER_OWNER_ID)
        }.exceptionOrNull()

        assertTrue(readFailure is SessionStore.PendingIncidentConflictException)
        assertTrue(clearFailure is SessionStore.PendingIncidentConflictException)
        assertEquals(saved, store.getPendingChatMessage(TEST_OWNER_ID))
    }

    @Test
    fun differentMessageCannotReplaceAnUnresolvedOutboxRecord() = runBlocking {
        val saved = record()
        store.savePendingChatMessage(saved)

        val replacement = saved.copy(
            message = "A different message",
            clientMessageId = UUID.randomUUID().toString(),
        )
        val conflict = runCatching {
            SessionStore(context, dataStore = isolatedDataStore).savePendingChatMessage(replacement)
        }.exceptionOrNull()

        assertTrue(conflict is SessionStore.PendingIncidentConflictException)
        assertEquals(saved, store.getPendingChatMessage(TEST_OWNER_ID))
    }

    @Test
    fun onlyMatchingClientMessageIdClearsThePendingRecord() = runBlocking {
        val saved = record()
        store.savePendingChatMessage(saved)

        store.clearPendingChatMessage(UUID.randomUUID().toString(), TEST_OWNER_ID)
        assertEquals(saved, store.getPendingChatMessage(TEST_OWNER_ID))

        store.clearPendingChatMessage(saved.clientMessageId, TEST_OWNER_ID)
        assertNull(store.getPendingChatMessage(TEST_OWNER_ID))
    }

    private fun record() = SessionStore.PendingChatMessage(
        incidentId = TEST_INCIDENT_ID,
        message = "Synthetic retry-safe chat test",
        clientMessageId = UUID.randomUUID().toString(),
        ownerUserId = TEST_OWNER_ID,
    )

    private companion object {
        const val TEST_OWNER_ID = 987654331L
        const val OTHER_OWNER_ID = 987654332L
        const val TEST_INCIDENT_ID = 987654333L
    }
}
