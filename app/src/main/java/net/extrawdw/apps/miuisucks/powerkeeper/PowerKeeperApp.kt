package net.extrawdw.apps.miuisucks.powerkeeper

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import rikka.shizuku.Shizuku

class PowerKeeperApp : Application() {
    private val dependencyHandler by lazy {
        Handler(HandlerThread("shizuku-dependency-events").apply { start() }.looper)
    }
    private val startupBinderListener = Shizuku.OnBinderReceivedListener {
        BootStartup.onShizukuReady(this, binderEvent = true)
    }
    private val startupPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        BootStartup.onShizukuReady(this)
    }
    private val dependencyDeadListener = Shizuku.OnBinderDeadListener {
        BootStartup.checkRuntimeDependency(this)
    }
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        BootStartup.initializeNotifications(this)
        PrivilegedServiceClient.init(this)
        AppWakeScheduler.schedule(this)
        // FIFO delivery off the UI thread. Register death/permission callbacks before sticky ready.
        Shizuku.addBinderDeadListener(dependencyDeadListener, dependencyHandler)
        Shizuku.addRequestPermissionResultListener(startupPermissionListener, dependencyHandler)
        Shizuku.addBinderReceivedListenerSticky(startupBinderListener, dependencyHandler)
        AppLog.i(
            "App",
            "onCreate version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) pid=${android.os.Process.myPid()}",
        )
    }
}
