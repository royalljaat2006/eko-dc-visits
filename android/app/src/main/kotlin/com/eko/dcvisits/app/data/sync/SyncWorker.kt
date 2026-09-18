package com.eko.dcvisits.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eko.dcvisits.app.di.ServiceLocator
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Drains the outbox whenever the network is available. Kicked immediately after
 * every enqueue ([kick]) and also every 15 min as a backstop ([schedulePeriodic])
 * so a backlog from a multi-day offline stretch clears on its own.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = ServiceLocator.outboxRepository(applicationContext)
        return try {
            val drained = repo.drainOnce()
            if (drained) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_ONESHOT = "sync-outbox-now"
        private const val UNIQUE_PERIODIC = "sync-outbox-periodic"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun kick(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_ONESHOT, ExistingWorkPolicy.REPLACE, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofMinutes(15))
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
