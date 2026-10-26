package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import rikka.shizuku.Shizuku
import kotlinx.coroutines.CancellationException

class EnforcementWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val store = GuardSettingsStore(applicationContext)
        val compatibility = XiaomiCompatibility.check(applicationContext)
        AppLog.i("Worker/Enforcement", "start id=$id attempt=$runAttemptCount supported=${compatibility.supported}")
        if (!compatibility.supported) {
            AppLog.e("Worker/Enforcement", "unsupported: ${compatibility.reason}")
            store.saveLastRun(false, "Unsupported device: ${compatibility.reason}")
            return Result.failure()
        }
        val settings = store.loadSettings()
        if (!GuardSettingsStore.isPeriodicEnforcementEnabled(settings.intervalMinutes)) {
            AppLog.i("Worker/Enforcement", "periodic enforcement disabled; skipping queued work")
            return Result.success()
        }
        val targetUserIds = store.loadEnabledAndroidUserIds()
        AppLog.i(
            "Worker/Enforcement",
            "settings aurogon=${settings.aurogonEnabledPackages.size} policies=${settings.enabledAppPolicies.size} users=${targetUserIds.joinToString()}",
        )

        if (!isShizukuAvailable()) {
            BootStartup.checkRuntimeDependency(applicationContext)
            AppLog.w("Worker/Enforcement", "Waiting for Shizuku Binder callback; no dependency retry")
            store.saveLastRun(false, "Waiting for Shizuku readiness callback.")
            return Result.success()
        }
        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permissionGranted) {
            BootStartup.checkRuntimeDependency(applicationContext)
            AppLog.w("Worker/Enforcement", "Shizuku permission missing; finishing without retry")
            store.saveLastRun(false, "Shizuku permission has not been granted. Open the app to authorize it.")
            return Result.success()
        }

        val generation = BootStartup.currentGeneration(applicationContext)
        return try {
            val report = PrivilegedServiceClient.enforce(
                settings.aurogonEnabledPackages,
                settings.enabledAppPolicies,
                targetUserIds,
                settings.milletPollingIntervalMillis,
                settings.fcmReconnectEnabled,
                trigger = "background:periodic-enforcement",
            )
            val succeeded = !report.contains("FAILED:") && !report.contains("exit_code=")
            store.saveLastRun(succeeded, report)
            if (succeeded) BootStartup.enforcementCompleted(applicationContext, generation)
            else BootStartup.showEnforcementFailed(applicationContext, generation)
            AppLog.i("Worker/Enforcement", "finish succeeded=$succeeded report=$report")
            Result.success()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            if (!isShizukuAvailable() || runCatching {
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
                }.getOrDefault(true)) {
                BootStartup.checkRuntimeDependency(applicationContext)
                store.saveLastRun(false, "Waiting for Shizuku readiness or permission callback.")
                return Result.success()
            }
            AppLog.e("Worker/Enforcement", "failed; retrying", throwable)
            store.saveLastRun(false, "Enforcement failed: ${throwable.message ?: throwable.javaClass.simpleName}")
            if (runAttemptCount + 1 < 3) Result.retry() else {
                BootStartup.showEnforcementFailed(applicationContext, generation)
                Result.failure()
            }
        }
    }

    private fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    }.getOrDefault(false)
}
