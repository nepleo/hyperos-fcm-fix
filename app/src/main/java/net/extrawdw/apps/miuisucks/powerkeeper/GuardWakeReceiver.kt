package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class GuardWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLog.i("Receiver", "received action=${intent.action}")
        if (intent.action !in SUPPORTED_ACTIONS) {
            AppLog.w("Receiver", "ignored unsupported action=${intent.action}")
            return
        }
        val compatibility = XiaomiCompatibility.check(context.applicationContext)
        if (!compatibility.supported) {
            AppLog.e("Receiver", "ignored unsupported device: ${compatibility.reason}")
            return
        }

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (!BootStartup.isEnabled(context)) {
                AppLog.i("Receiver", "boot check disabled")
                return
            }
            BootStartup.onBoot(context)
        }

        val store = GuardSettingsStore(context.applicationContext)
        val settings = store.loadSettings()
        EnforcementScheduler.schedule(context.applicationContext, settings.intervalMinutes)
        // Application already registered Sticky Binder/permission listeners. Do not enqueue
        // a retry loop while the dependency is absent; the ready event submits restoration.
        BootStartup.checkRuntimeDependency(context)
        BootStartup.onShizukuReady(context)

        if (!GuardSettingsStore.isPeriodicEnforcementEnabled(settings.intervalMinutes)) {
            AppLog.i("Receiver", "periodic enforcement disabled; FCM recovery only")
            return
        }

        val lastRun = store.loadLastRun()
        val staleAfterMillis = settings.intervalMinutes * 60_000L
        if (lastRun == null || System.currentTimeMillis() - lastRun.timestampMillis >= staleAfterMillis) {
            AppLog.i("Receiver", "last enforcement stale; enqueueing immediate enforcement")
            EnforcementScheduler.runNow(context.applicationContext)
        } else {
            AppLog.i("Receiver", "last enforcement fresh; FCM recovery only")
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
