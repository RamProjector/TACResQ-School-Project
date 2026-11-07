package com.tacresq.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tacresq.app.TacResqApp
import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.SessionState
import com.tacresq.app.data.repo.ApiException
import java.time.Instant

/**
 * Refreshes only the requester incident read model. It never creates, retries,
 * cancels, or mutates an incident and never marks notifications read.
 */
class RequesterIncidentReadSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? TacResqApp ?: return Result.failure()
        val repository = app.container.repository
        val session = (repository.sessionState.value as? SessionState.LoggedIn)?.session
            ?: return Result.success()
        if (session.user.role != Role.REQUESTER) return Result.success()
        if (!hasValidatedNetwork()) return Result.retry()

        val decision = decideAutomaticSync(
            operation = AutomaticSyncOperation.REQUESTER_INCIDENT_READ,
            hasAuthenticatedSession = true,
            hasValidatedNetwork = true,
            nowEpochMillis = Instant.now().toEpochMilli(),
            lastAttemptEpochMillis = null,
        )
        if (decision != OfflineSyncDecision.RUN_READ) return Result.success()

        val incidents = repository.myIncidents()
        if (incidents.isSuccess) {
            return repository.cacheMyIncidents(incidents.getOrThrow())
                .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
        }

        val error = incidents.exceptionOrNull()
        if (error is ApiException && error.httpCode in setOf(401, 403)) return Result.success()
        return if (runAttemptCount >= MAX_RETRY_ATTEMPTS) Result.success() else Result.retry()
    }

    private fun hasValidatedNetwork(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 5
    }
}
