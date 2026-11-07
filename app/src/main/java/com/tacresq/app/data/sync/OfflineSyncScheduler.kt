package com.tacresq.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object OfflineSyncScheduler {
    private const val UNIQUE_REQUESTER_READ_SYNC = "tacresq-requester-read-sync"

    /**
     * Schedules deferrable read reconciliation. KEEP prevents duplicate workers;
     * network and WorkManager backoff constraints keep this out of emergency delivery.
     */
    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RequesterIncidentReadSyncWorker>(
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_REQUESTER_READ_SYNC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
