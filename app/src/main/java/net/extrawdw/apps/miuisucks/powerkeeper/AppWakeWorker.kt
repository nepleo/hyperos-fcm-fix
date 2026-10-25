package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import rikka.shizuku.Shizuku

internal class AppWakeReportStore(context: Context) {
    private val preferences = context.getSharedPreferences("app_wake_report", Context.MODE_PRIVATE)
    fun lastReport(): String = preferences.getString("report", "").orEmpty()
    fun saveReport(report: String) {
        preferences.edit().putString("report", report.take(8_000)).apply()
    }
}

class AppWakeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val settingsStore = GuardSettingsStore(applicationContext)
        if (settingsStore.loadWakeIntervalMinutes() == 0L ||
            !XiaomiCompatibility.check(applicationContext).supported) return Result.success()
        val reportStore = AppWakeReportStore(applicationContext)
        val ready = runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!ready) {
            record(reportStore, "Shizuku unavailable; waiting for readiness event / 等待 Shizuku 就绪")
            return Result.success()
        }
        var checked = 0
        var started = 0
        var uncertainOrFailed = 0
        try {
            val settings = settingsStore.loadSettings()
            for (userId in settingsStore.loadEnabledAndroidUserIds()) {
                for (packageName in settings.wakeEnabledPackages) {
                    // Read the shared app switches again before each side effect, including after a Binder wait.
                    if (settingsStore.loadWakeIntervalMinutes() == 0L || isStopped) return Result.success()
                    if (packageName == applicationContext.packageName ||
                        packageName !in settingsStore.loadSettings().wakeEnabledPackages ||
                        userId !in settingsStore.loadEnabledAndroidUserIds()) continue
                    val status = PrivilegedServiceClient.wakeApp(packageName, userId, "background:app-wake")
                    val message = "user=$userId $packageName: " + when (status) {
                        AppWakePlan.PRESENT -> "已有进程，未重复打开 / process present"
                        AppWakePlan.REQUESTED -> "启动已完成；未验证推送 / activity started, push unverified"
                        AppWakePlan.NOT_INSTALLED -> "未安装 / not installed"
                        AppWakePlan.NO_LAUNCHER -> "无可启动页面，跳过 / no launcher"
                        AppWakePlan.UNKNOWN -> "状态或启动结果不确定 / unknown result"
                        else -> "执行失败，查看日志 / failed"
                    }
                    checked++
                    if (status == AppWakePlan.REQUESTED) started++
                    if (status == AppWakePlan.UNKNOWN || status == AppWakePlan.FAILED) uncertainOrFailed++
                    AppLog.i("AppWake", message)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A Binder timeout may follow a successful launch. Do not immediately repeat the side effect.
            AppLog.e("AppWake", "result unknown; no immediate retry", error)
            uncertainOrFailed++
        }
        record(reportStore, applicationContext.getString(
            R.string.app_wake_result_summary, checked, started, uncertainOrFailed,
        ))
        return Result.success()
    }

    private fun record(store: AppWakeReportStore, message: String) {
        val report = "${java.util.Date()}\n$message"
        store.saveReport(report)
        AppLog.i("AppWake", report)
    }
}
