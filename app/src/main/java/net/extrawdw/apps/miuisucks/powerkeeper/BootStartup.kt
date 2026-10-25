package net.extrawdw.apps.miuisucks.powerkeeper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import rikka.shizuku.Shizuku

/** Reports boot recovery without starting an activity or claiming protection is already active. */
internal object BootStartup {
    private const val PREFS = "boot_startup"
    private const val ENABLED = "enabled"
    private const val PENDING = "pending"
    private const val LAST_NOTICE = "last_notice"
    private const val PERMISSION_REQUESTED = "notification_permission_requested"
    private const val CHANNEL_ID = "boot_recovery"
    private const val NOTIFICATION_ID = 1001
    private const val RUNTIME_NOTIFICATION_ID = 1002
    private const val RUNTIME_NOTICE = "runtime_notice"
    private const val RECOVERY_PENDING = "recovery_pending"
    internal const val RECOVERY_GENERATION = "recovery_generation"
    private const val NOTICE_KIND = "notice_kind"
    private const val POLICY_VERSION = "notification_policy_version"
    private const val PERMISSION = "permission"
    private const val RECOVERY = "recovery"
    private const val ENFORCEMENT = "enforcement"
    private val recoveryGate = BinderRecoveryGate()

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Remove old progress cards once, without losing pending recovery state. */
    @Synchronized
    fun initializeNotifications(context: Context) {
        if (preferences(context).getInt(POLICY_VERSION, 0) >= 1) return
        val oldRuntimeNotice = preferences(context).contains(RUNTIME_NOTICE)
        clearRuntimeNotice(context)
        val editor = preferences(context).edit().putInt(POLICY_VERSION, 1)
        if (oldRuntimeNotice) editor.putBoolean(RECOVERY_PENDING, true)
        editor.apply()
    }

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply()
        if (!enabled) {
            preferences(context).edit().putBoolean(PENDING, false).apply()
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private fun isPending(context: Context): Boolean =
        isEnabled(context) && preferences(context).getBoolean(PENDING, false)

    fun hasRequestedNotificationPermission(context: Context): Boolean =
        preferences(context).getBoolean(PERMISSION_REQUESTED, false)

    fun markNotificationPermissionRequested(context: Context) {
        preferences(context).edit().putBoolean(PERMISSION_REQUESTED, true).apply()
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun shizukuRunning(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    }.getOrDefault(false)

    private fun shizukuGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    @Synchronized
    fun onBoot(context: Context) {
        recoveryGate.reset()
        clearRuntimeNotice(context)
        preferences(context).edit().putBoolean(PENDING, true)
            .putBoolean(RECOVERY_PENDING, true).remove(LAST_NOTICE).apply()
        showWaiting(context)
    }

    fun showWaiting(context: Context) {
        if (isPending(context)) checkRuntimeDependency(context)
    }

    @Synchronized
    fun onShizukuReady(context: Context, binderEvent: Boolean = false) {
        if (!XiaomiCompatibility.check(context).supported) return
        val bootPending = isPending(context)
        val runtimePending = preferences(context).getBoolean(RECOVERY_PENDING, false) ||
            preferences(context).contains(RUNTIME_NOTICE) // Migrate custom.3 pending notices.
        if (!bootPending && !runtimePending && !binderEvent) return
        if (bootPending) showWaiting(context)
        if (shizukuRunning() && shizukuGranted()) {
            val binder = Shizuku.getBinder() ?: return
            if (!recoveryGate.claim(binder)) return
            val generation = currentGeneration(context) + 1
            if (!preferences(context).edit().putLong(RECOVERY_GENERATION, generation)
                    .putBoolean(RECOVERY_PENDING, true).commit()) {
                recoveryGate.reset()
                AppLog.e("BootStartup", "Could not persist recovery request")
                return
            }
            clearRuntimeNotice(context, RECOVERY)
            try {
                EnforcementScheduler.recoverFcmNow(context.applicationContext, generation)
                AppWakeScheduler.runNow(context.applicationContext)
                val store = GuardSettingsStore(context)
                val settings = store.loadSettings()
                val lastRun = store.loadLastRun()
                if (GuardSettingsStore.isPeriodicEnforcementEnabled(settings.intervalMinutes) &&
                    (lastRun == null || !lastRun.succeeded ||
                        System.currentTimeMillis() - lastRun.timestampMillis >= settings.intervalMinutes * 60_000L)) {
                    EnforcementScheduler.runNow(context.applicationContext, dependencyEvent = true)
                }
            } catch (error: RuntimeException) {
                recoveryGate.reset()
                AppLog.e("BootStartup", "Could not enqueue event recovery", error)
                showFailed(context, generation)
            }
        } else {
            checkRuntimeDependency(context)
        }
    }

    fun currentGeneration(context: Context): Long =
        preferences(context).getLong(RECOVERY_GENERATION, 0)

    @Synchronized
    fun completed(context: Context, generation: Long, binder: IBinder?) {
        if (generation != currentGeneration(context) || binder !== Shizuku.getBinder() ||
            !shizukuRunning() || !shizukuGranted()) return
        preferences(context).edit().putBoolean(RECOVERY_PENDING, false).apply()
        clearRuntimeNotice(context, RECOVERY)
        clearRuntimeNotice(context, PERMISSION)
        preferences(context).edit().putBoolean(PENDING, false).apply()
    }

    @Synchronized
    fun showFailed(context: Context, generation: Long = currentGeneration(context)) {
        if (generation != currentGeneration(context)) return
        showFinalFailure(context, RECOVERY)
    }

    @Synchronized
    fun showEnforcementFailed(context: Context, generation: Long) {
        if (generation == currentGeneration(context)) showFinalFailure(context, ENFORCEMENT)
    }

    private fun showFinalFailure(context: Context, kind: String) {
        when (FcmNoticePolicy.select(shizukuRunning(), shizukuGranted(), finalFailure = true)) {
            FcmNoticePolicy.Notice.NONE -> checkRuntimeDependency(context)
            FcmNoticePolicy.Notice.PERMISSION_REQUIRED -> checkRuntimeDependency(context)
            FcmNoticePolicy.Notice.FINAL_FAILURE -> notify(context, R.string.boot_restore_failed, kind)
        }
    }

    @Synchronized
    fun enforcementCompleted(context: Context, generation: Long) {
        if (generation == currentGeneration(context)) clearRuntimeNotice(context, ENFORCEMENT)
    }

    @Synchronized
    fun allowManualRetry(context: Context, generation: Long) {
        if (generation == currentGeneration(context)) recoveryGate.reset()
    }

    @Synchronized
    fun checkRuntimeDependency(context: Context) {
        val running = shizukuRunning()
        val granted = running && shizukuGranted()
        if (!running || !granted) {
            preferences(context).edit().putBoolean(RECOVERY_PENDING, true).apply()
            recoveryGate.reset()
        }
        when (FcmNoticePolicy.select(running, granted)) {
            FcmNoticePolicy.Notice.PERMISSION_REQUIRED -> notify(context, R.string.runtime_permission_missing, PERMISSION)
            else -> {
                if (!running) clearRuntimeNotice(context) // Shizuku owns its startup/failure alerts.
                else clearRuntimeNotice(context, PERMISSION)
            }
        }
    }

    private fun clearRuntimeNotice(context: Context, kind: String? = null) {
        if (kind != null && preferences(context).getString(NOTICE_KIND, null) != kind) return
        context.getSystemService(NotificationManager::class.java)?.apply {
            cancel(NOTIFICATION_ID)
            cancel(RUNTIME_NOTIFICATION_ID)
        }
        preferences(context).edit().remove(RUNTIME_NOTICE).remove(LAST_NOTICE).remove(NOTICE_KIND).apply()
    }

    @SuppressLint("MissingPermission") // Runtime permission and the channel are checked before notify().
    private fun notify(context: Context, messageRes: Int, kind: String) {
        val noticeKey = RUNTIME_NOTICE
        val message = context.getString(messageRes)
        if (preferences(context).getString(noticeKey, null) == message &&
            preferences(context).getString(NOTICE_KIND, null) == kind) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.boot_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ))
        if (!canNotify(context)) {
            AppLog.w("BootStartup", "notification unavailable; message=${context.getString(messageRes)}")
            return
        }
        val destination = Intent(context, MainActivity::class.java)
        destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, destination,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fcm_notification)
            .setContentTitle(context.getString(R.string.runtime_notification_title))
            .setContentText(context.getString(messageRes))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(messageRes)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        try {
            manager.notify(RUNTIME_NOTIFICATION_ID, notification)
            preferences(context).edit().putString(noticeKey, message).putString(NOTICE_KIND, kind).apply()
        } catch (error: RuntimeException) {
            AppLog.e("BootStartup", "Could not post boot notification", error)
        }
    }
}
