package net.extrawdw.apps.miuisucks.powerkeeper

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/** Local presentation preference; never changes Shizuku or protection policy. */
internal object TaskVisibility {
    private const val PREFERENCES_NAME = "local_ui_preferences"
    private const val HIDE_TASK = "hide_task"

    fun isHidden(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(HIDE_TASK, false)

    fun apply(activity: Activity, hidden: Boolean = isHidden(activity)): Boolean {
        return try {
            val manager = activity.getSystemService(ActivityManager::class.java) ?: return false
            val task = manager.appTasks.firstOrNull { it.taskInfo?.taskId == activity.taskId }
                ?: return false
            task.setExcludeFromRecents(hidden)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun setHidden(activity: Activity, hidden: Boolean): Boolean {
        if (!apply(activity, hidden)) return false
        activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(HIDE_TASK, hidden).apply()
        return true
    }
}

@Composable
internal fun HideTaskPreference() {
    val context = LocalContext.current
    val activity = context.findActivity()
    var hidden by remember(context) { mutableStateOf(TaskVisibility.isHidden(context)) }
    var failed by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.hide_task),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = hidden,
                enabled = activity != null,
                onCheckedChange = { requested ->
                    if (activity != null && TaskVisibility.setHidden(activity, requested)) {
                        hidden = requested
                        failed = false
                    } else {
                        failed = true
                    }
                },
            )
        }
        Text(
            text = stringResource(R.string.hide_task_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (failed) {
            Text(
                text = stringResource(R.string.hide_task_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.findActivity()
    else -> null
}
