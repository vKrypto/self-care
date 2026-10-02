package com.forma.datasync.sync

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
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val engine = SyncEngine(applicationContext)
        val remaining = engine.sync(background = true)
        val status = engine.status()
        if (status.optBoolean("authRequired") || !status.isNull("lastError")) SyncNotifications.failure(applicationContext)
        else SyncNotifications.clear(applicationContext)
        if (remaining) SyncScheduler.continueCatchup(applicationContext)
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        if (runAttemptCount == 0) SyncNotifications.failure(applicationContext)
        Result.retry()
    }
}

/** Android persists this schedule across process death and reboot; execution is best effort. */
object SyncScheduler {
    private const val TAG = "forma-data-sync"
    private const val PERIODIC = "forma-hourly-sync"
    private const val CATCHUP = "forma-sync-catchup"
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueue(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)
    fun continueCatchup(context: Context) = enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(CATCHUP, policy, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }
}
