package com.tacresq.app.data.sync

/** Operations that may be considered by automatic offline reconciliation. */
enum class AutomaticSyncOperation {
    REQUESTER_INCIDENT_READ,
    NOTIFICATION_READ,
    CREATE_SOS,
    RETRY_SOS,
    CANCEL_SOS,
    UPDATE_INCIDENT_STATUS,
    WRITE_RESPONDER_LOCATION,
    SEND_CHAT_MESSAGE,
    MARK_NOTIFICATION_READ,
}

enum class OfflineSyncDecision {
    RUN_READ,
    WAIT_FOR_NETWORK,
    WAIT_FOR_AUTHENTICATED_SESSION,
    WAIT_FOR_BACKOFF,
    REJECT_MUTATION,
}

private const val DEFAULT_MIN_INTERVAL_MILLIS = 30_000L

/**
 * Pure guardrail for automatic synchronization. It intentionally permits only
 * server reads; all emergency and other mutations remain explicit foreground actions.
 */
fun decideAutomaticSync(
    operation: AutomaticSyncOperation,
    hasAuthenticatedSession: Boolean,
    hasValidatedNetwork: Boolean,
    nowEpochMillis: Long,
    lastAttemptEpochMillis: Long?,
    minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
): OfflineSyncDecision {
    require(nowEpochMillis >= 0) { "Current time must not be negative." }
    require(minIntervalMillis >= 0) { "Sync interval must not be negative." }
    require(lastAttemptEpochMillis == null || lastAttemptEpochMillis >= 0) {
        "Last sync time must not be negative."
    }

    if (operation !in setOf(
            AutomaticSyncOperation.REQUESTER_INCIDENT_READ,
            AutomaticSyncOperation.NOTIFICATION_READ,
        )
    ) {
        return OfflineSyncDecision.REJECT_MUTATION
    }
    if (!hasAuthenticatedSession) return OfflineSyncDecision.WAIT_FOR_AUTHENTICATED_SESSION
    if (!hasValidatedNetwork) return OfflineSyncDecision.WAIT_FOR_NETWORK
    if (lastAttemptEpochMillis != null && nowEpochMillis - lastAttemptEpochMillis < minIntervalMillis) {
        return OfflineSyncDecision.WAIT_FOR_BACKOFF
    }
    return OfflineSyncDecision.RUN_READ
}
