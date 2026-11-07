package com.tacresq.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineSyncPolicyTest {
    @Test
    fun read_waits_until_an_authenticated_session_exists() {
        assertEquals(
            OfflineSyncDecision.WAIT_FOR_AUTHENTICATED_SESSION,
            decideAutomaticSync(
                operation = AutomaticSyncOperation.REQUESTER_INCIDENT_READ,
                hasAuthenticatedSession = false,
                hasValidatedNetwork = true,
                nowEpochMillis = 60_000,
                lastAttemptEpochMillis = null,
            ),
        )
    }

    @Test
    fun authenticated_read_waits_for_validated_network() {
        assertEquals(
            OfflineSyncDecision.WAIT_FOR_NETWORK,
            decideAutomaticSync(
                operation = AutomaticSyncOperation.NOTIFICATION_READ,
                hasAuthenticatedSession = true,
                hasValidatedNetwork = false,
                nowEpochMillis = 60_000,
                lastAttemptEpochMillis = null,
            ),
        )
    }

    @Test
    fun read_runs_after_the_minimum_interval() {
        assertEquals(
            OfflineSyncDecision.RUN_READ,
            decideAutomaticSync(
                operation = AutomaticSyncOperation.REQUESTER_INCIDENT_READ,
                hasAuthenticatedSession = true,
                hasValidatedNetwork = true,
                nowEpochMillis = 90_000,
                lastAttemptEpochMillis = 50_000,
                minIntervalMillis = 30_000,
            ),
        )
    }

    @Test
    fun read_waits_during_backoff_window() {
        assertEquals(
            OfflineSyncDecision.WAIT_FOR_BACKOFF,
            decideAutomaticSync(
                operation = AutomaticSyncOperation.NOTIFICATION_READ,
                hasAuthenticatedSession = true,
                hasValidatedNetwork = true,
                nowEpochMillis = 79_999,
                lastAttemptEpochMillis = 50_000,
                minIntervalMillis = 30_000,
            ),
        )
    }

    @Test
    fun automatic_sync_rejects_every_dispatch_or_mutation_operation() {
        val mutations = listOf(
            AutomaticSyncOperation.CREATE_SOS,
            AutomaticSyncOperation.RETRY_SOS,
            AutomaticSyncOperation.CANCEL_SOS,
            AutomaticSyncOperation.UPDATE_INCIDENT_STATUS,
            AutomaticSyncOperation.WRITE_RESPONDER_LOCATION,
            AutomaticSyncOperation.SEND_CHAT_MESSAGE,
            AutomaticSyncOperation.MARK_NOTIFICATION_READ,
        )
        mutations.forEach { operation ->
            assertEquals(
                operation.name,
                OfflineSyncDecision.REJECT_MUTATION,
                decideAutomaticSync(
                    operation = operation,
                    hasAuthenticatedSession = true,
                    hasValidatedNetwork = true,
                    nowEpochMillis = 60_000,
                    lastAttemptEpochMillis = null,
                ),
            )
        }
    }
}
