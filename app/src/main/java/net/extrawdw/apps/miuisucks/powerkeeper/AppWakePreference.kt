package net.extrawdw.apps.miuisucks.powerkeeper

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@Composable
internal fun AppWakePreference() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val store = remember(context) { GuardSettingsStore(context) }
    val reports = remember(context) { AppWakeReportStore(context) }
    var interval by remember { mutableStateOf(store.loadWakeIntervalMinutes()) }
    var report by remember { mutableStateOf(reports.lastReport()) }
    LaunchedEffect(owner, reports) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                report = reports.lastReport()
                delay(1_500)
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        PeriodicEnforcementFrequencyRow(
            selectedMinutes = interval,
            labelRes = R.string.app_wake_title,
            onIntervalSelected = {
                store.setWakeIntervalMinutes(it)
                interval = it
                AppWakeScheduler.schedule(context)
                if (it > 0L) AppWakeScheduler.runNow(context)
            },
        )
        Text(stringResource(R.string.app_wake_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (interval > 0L && report.isNotBlank()) Text(report, style = MaterialTheme.typography.bodySmall)
    }
}
