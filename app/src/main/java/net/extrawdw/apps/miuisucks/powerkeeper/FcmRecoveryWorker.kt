package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import rikka.shizuku.Shizuku
import kotlinx.coroutines.CancellationException

class FcmRecoveryWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val compatibility = XiaomiCompatibility.check(applicationContext)
        AppLog.i(LOG_TAG, "start id=$id attempt=$runAttemptCount supported=${compatibility.supported}")
        if (!compatibility.supported) {
            AppLog.e(LOG_TAG, "unsupported: ${compatibility.reason}")
            return Result.failure()
        }
        val generation = inputData.getLong(BootStartup.RECOVERY_GENERATION, -1)
            .takeIf { it >= 0 } ?: BootStartup.currentGeneration(applicationContext)
        if (generation != BootStartup.currentGeneration(applicationContext)) {
            AppLog.i(LOG_TAG, "superseded recovery generation=$generation; skipping")
            return Result.success()
        }
        if (!isShizukuAvailable()) {
            BootStartup.checkRuntimeDependency(applicationContext)
            AppLog.w(LOG_TAG, "Waiting for Shizuku Binder callback; no dependency retry")
            return Result.success()
        }

        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permissionGranted) {
            BootStartup.checkRuntimeDependency(applicationContext)
            AppLog.w(LOG_TAG, "Shizuku permission missing; finishing without retry")
            return Result.success()
        }

        val binder = Shizuku.getBinder()
        fun requireCurrentSession() {
            if (binder == null || binder !== Shizuku.getBinder() || !isShizukuAvailable() ||
                generation != BootStartup.currentGeneration(applicationContext)) throw DependencySessionChanged()
        }
        val settingsStore = GuardSettingsStore(applicationContext)
        val settings = settingsStore.loadSettings()
        val targetUserIds = settingsStore.loadEnabledAndroidUserIds()
        return runCatching {
            requireCurrentSession()
            val fcmReport = PrivilegedServiceClient.startFcmProtection(
                settings.aurogonEnabledPackages,
                settings.milletPollingIntervalMillis,
                settings.fcmReconnectEnabled,
                "background:fcm-recovery",
            )
            requireCurrentSession()
            val unstopReport = if (settings.autoUnstopPackages.isEmpty()) {
                "Auto unstop: no packages selected"
            } else {
                PrivilegedServiceClient.unstop(
                    settings.autoUnstopPackages,
                    targetUserIds,
                    "background:auto-unstop",
                )
            }
            requireCurrentSession()
            val reconnectReport = if (settings.fcmReconnectEnabled) {
                PrivilegedServiceClient.forceFcmReconnect("background:fcm-recovery")
            } else {
                "FCM reconnect: disabled"
            }
            requireCurrentSession()
            "$fcmReport\n$unstopReport\n$reconnectReport"
        }
            .fold(
                onSuccess = { report ->
                    val failed = report.contains("FAILED") || report.contains("exit_code=")
                    if (!failed) BootStartup.completed(applicationContext, generation, binder)
                    AppLog.i(LOG_TAG, "finish failed=$failed report=$report")
                    if (failed) executionFailure(generation) else Result.success()
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    if (error is DependencySessionChanged || !isShizukuAvailable() || !hasPermission()) {
                        BootStartup.checkRuntimeDependency(applicationContext)
                        AppLog.w(LOG_TAG, "dependency changed; waiting for the next ready event")
                        Result.success()
                    } else {
                        AppLog.e(LOG_TAG, "execution failed", error)
                        executionFailure(generation)
                    }
                },
            )
    }

    private fun executionFailure(generation: Long): Result {
        if (generation != BootStartup.currentGeneration(applicationContext)) return Result.success()
        return if (runAttemptCount + 1 < MAX_EXECUTION_ATTEMPTS) Result.retry() else {
            BootStartup.showFailed(applicationContext, generation)
            BootStartup.allowManualRetry(applicationContext, generation)
            Result.failure()
        }
    }

    private fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private class DependencySessionChanged : Exception()

    private fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    }.getOrDefault(false)

    private companion object {
        const val MAX_EXECUTION_ATTEMPTS = 3
        const val LOG_TAG = "Worker/FCMRecovery"
    }
}
