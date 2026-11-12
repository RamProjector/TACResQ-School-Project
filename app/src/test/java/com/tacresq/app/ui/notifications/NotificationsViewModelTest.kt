package com.tacresq.app.ui.notifications

import com.tacresq.app.data.model.UserNotification
import com.tacresq.app.data.repo.NotificationRepository
import com.tacresq.app.data.realtime.NotificationRealtime
import com.tacresq.app.data.realtime.RealtimeConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationsViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pushTapForcesFreshExpandedInboxAndRetainsOnlyTheRequestedFocus() = runTest {
        val repository = FakeNotificationRepository(
            load = { Result.success(listOf(unreadNotification)) },
        )
        val viewModel = NotificationsViewModel(repository)

        viewModel.focusNotification(unreadNotification.id)
        advanceUntilIdle()

        assertEquals(100, repository.lastRequestedLimit)
        assertEquals(unreadNotification.id, viewModel.state.value.focusedNotificationId)
        assertEquals(listOf(unreadNotification), viewModel.state.value.items)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test
    fun invalidPushTapDoesNotStartAnInboxRequest() = runTest {
        val repository = FakeNotificationRepository()
        val viewModel = NotificationsViewModel(repository)

        viewModel.focusNotification(0L)
        advanceUntilIdle()

        assertEquals(0, repository.lastRequestedLimit)
        assertNull(viewModel.state.value.focusedNotificationId)
    }

    @Test
    fun refreshThrownExceptionUsesGenericErrorAndClearsProgress() = runTest {
        val repository = FakeNotificationRepository(
            load = {
                throw IllegalStateException("sensitive backend detail")
            },
        )
        val viewModel = NotificationsViewModel(repository)

        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.refreshing)
        assertEquals("Could not load notifications. Try again.", viewModel.state.value.error)
        assertFalse(viewModel.state.value.error!!.contains("sensitive backend detail"))
    }

    @Test
    fun refreshResultFailurePreservesRepositoryMessageAndClearsProgress() = runTest {
        val repository = FakeNotificationRepository(
            load = { Result.failure(IllegalStateException("Service temporarily unavailable")) },
        )
        val viewModel = NotificationsViewModel(repository)

        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.refreshing)
        assertEquals("Service temporarily unavailable", viewModel.state.value.error)
    }

    @Test
    fun refreshCancellationPropagatesWithoutShowingErrorAndClearsProgress() = runTest {
        val repository = FakeNotificationRepository(
            load = { throw CancellationException("cancel refresh") },
        )
        val viewModel = NotificationsViewModel(repository)

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(repository.lastLoadJob?.isCancelled == true)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.refreshing)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun markReadThrownExceptionUsesGenericErrorAndClearsBusyId() = runTest {
        val repository = FakeNotificationRepository(
            load = { Result.success(listOf(unreadNotification)) },
            markRead = { throw IllegalStateException("sensitive backend detail") },
        )
        val viewModel = NotificationsViewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.markRead(unreadNotification)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.busyIds.isEmpty())
        assertEquals("Could not mark this notification as read.", viewModel.state.value.actionError)
        assertFalse(viewModel.state.value.actionError!!.contains("sensitive backend detail"))
    }

    @Test
    fun markReadResultFailurePreservesRepositoryMessageAndClearsBusyId() = runTest {
        val repository = FakeNotificationRepository(
            load = { Result.success(listOf(unreadNotification)) },
            markRead = { Result.failure(IllegalStateException("Read update rejected")) },
        )
        val viewModel = NotificationsViewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.markRead(unreadNotification)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.busyIds.isEmpty())
        assertEquals("Read update rejected", viewModel.state.value.actionError)
    }

    @Test
    fun markReadCancellationPropagatesWithoutShowingErrorAndClearsBusyId() = runTest {
        val repository = FakeNotificationRepository(
            load = { Result.success(listOf(unreadNotification)) },
            markRead = { throw CancellationException("cancel mark read") },
        )
        val viewModel = NotificationsViewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.markRead(unreadNotification)
        advanceUntilIdle()

        assertTrue(repository.lastMarkReadJob?.isCancelled == true)
        assertTrue(viewModel.state.value.busyIds.isEmpty())
        assertNull(viewModel.state.value.actionError)
    }

    @Test
    fun duplicateAndOutOfOrderHintsAreReconciledByTheAuthoritativeRead() = runTest {
        var reads = 0
        val repository = FakeNotificationRepository(
            load = {
                reads += 1
                Result.success(listOf(unreadNotification))
            },
        )
        val realtime = FakeNotificationRealtime()
        val viewModel = NotificationsViewModel(repository, realtime)

        viewModel.refresh()
        advanceUntilIdle()
        realtime.emitHint()
        realtime.emitHint()
        realtime.emitHint()
        advanceUntilIdle()

        assertEquals(unreadNotification.id, viewModel.state.value.items.single().id)
        assertEquals(4, reads)
    }

    @Test
    fun authoritativeReadRemovesNotificationAfterMembershipRevocation() = runTest {
        var revoked = false
        val repository = FakeNotificationRepository(
            load = {
                Result.success(if (revoked) emptyList() else listOf(unreadNotification))
            },
        )
        val realtime = FakeNotificationRealtime()
        val viewModel = NotificationsViewModel(repository, realtime)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(listOf(unreadNotification), viewModel.state.value.items)

        revoked = true
        realtime.emitHint()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.items.isEmpty())
        assertNull(viewModel.state.value.error)
    }

    private class FakeNotificationRepository(
        private val load: suspend (Int) -> Result<List<UserNotification>> = {
            Result.success(emptyList())
        },
        private val markRead: suspend (Long) -> Result<Boolean> = { Result.success(true) },
    ) : NotificationRepository {
        var lastLoadJob: Job? = null
        var lastMarkReadJob: Job? = null
        var lastRequestedLimit: Int = 0

        override suspend fun myNotifications(limit: Int): Result<List<UserNotification>> {
            lastRequestedLimit = limit
            lastLoadJob = currentCoroutineContext()[Job]
            return load(limit)
        }

        override suspend fun markNotificationRead(notificationId: Long): Result<Boolean> {
            lastMarkReadJob = currentCoroutineContext()[Job]
            return markRead(notificationId)
        }
    }

    private class FakeNotificationRealtime : NotificationRealtime {
        private val mutableState = MutableStateFlow(RealtimeConnectionState.CONNECTED)
        private val mutableHints = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

        override val state: StateFlow<RealtimeConnectionState> = mutableState
        override val notificationHints: SharedFlow<Unit> = mutableHints

        override fun start() = Unit
        override fun stop() = Unit
        fun emitHint() {
            mutableHints.tryEmit(Unit)
        }
    }

    private companion object {
        val unreadNotification = UserNotification(
            id = 42L,
            incidentId = 7L,
            eventType = "status_changed",
            title = "Incident update",
            body = "An incident status changed.",
            createdAt = "2026-10-28T00:00:00Z",
            readAt = null,
        )
    }
}
