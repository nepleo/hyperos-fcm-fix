package net.extrawdw.apps.miuisucks.powerkeeper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun BootStartupPreference() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(BootStartup.isEnabled(context)) }
    var notificationsAllowed by remember { mutableStateOf(BootStartup.canNotify(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsAllowed = BootStartup.canNotify(context) }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED &&
            !BootStartup.hasRequestedNotificationPermission(context)) {
            BootStartup.markNotificationPermissionRequested(context)
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            })
        }
    }

    LaunchedEffect(Unit) {
        if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33 &&
            !BootStartup.hasRequestedNotificationPermission(context)) requestNotifications()
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsAllowed = BootStartup.canNotify(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.boot_startup),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                BootStartup.setEnabled(context, it)
                if (it && !notificationsAllowed) requestNotifications()
            })
        }
        Text(
            stringResource(R.string.boot_startup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!notificationsAllowed) {
            Text(
                stringResource(R.string.boot_notifications_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { requestNotifications() }) {
                Text(stringResource(R.string.boot_enable_notifications))
            }
        }
    }
}
