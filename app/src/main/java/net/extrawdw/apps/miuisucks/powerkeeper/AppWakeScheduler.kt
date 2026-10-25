package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal object AppWakeScheduler {
    private const val PERIODIC = "app_wake_periodic"
    private const val IMMEDIATE = "app_wake_immediate"

    fun schedule(context: Context) {
        val interval = GuardSettingsStore(context).loadWakeIntervalMinutes()
        val manager = WorkManager.getInstance(context)
        if (interval == 0L) {
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(IMMEDIATE)
            return
        }
        manager.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AppWakeWorker>(interval, TimeUnit.MINUTES)
                .setInitialDelay(interval, TimeUnit.MINUTES).build(),
        )
    }

    fun runNow(context: Context) {
        if (GuardSettingsStore(context).loadWakeIntervalMinutes() == 0L) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AppWakeWorker>().build(),
        )
    }
}
