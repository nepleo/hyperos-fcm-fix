package net.extrawdw.apps.miuisucks.powerkeeper

/** Only a confirmed absent UID permits an automatic activity launch. */
internal object AppWakePlan {
    const val PRESENT = 0
    const val REQUESTED = 1
    const val NOT_INSTALLED = 2
    const val UNKNOWN = 3
    const val FAILED = 4
    const val NO_LAUNCHER = 5

    enum class Presence { PRESENT, ABSENT, UNKNOWN }

    fun presence(output: String): Presence {
        val match = Regex("^\\s*(\\d+) \\(([A-Z_]+)\\)\\s*$").matchEntire(output)
            ?: return Presence.UNKNOWN
        val state = match.groupValues[1].toIntOrNull() ?: return Presence.UNKNOWN
        val name = match.groupValues[2]
        return when {
            state == 20 && name == "NONEXISTENT" -> Presence.ABSENT
            state in 0..19 && name in PRESENT_STATES -> Presence.PRESENT
            else -> Presence.UNKNOWN
        }
    }

    fun launcherComponent(output: String, packageName: String): String? = output.lineSequence()
        .map(String::trim)
        .singleOrNull { it.matches(Regex("${Regex.escape(packageName)}/[A-Za-z0-9_.$]+")) }

    fun launchConfirmed(output: String): Boolean =
        output.lineSequence().any { it.trim() == "Status: ok" } &&
            output.lineSequence().none { it.trimStart().startsWith("Error:") }

    private val PRESENT_STATES = setOf(
        "PERSISTENT", "PERSISTENT_UI", "TOP", "BOUND_TOP", "FOREGROUND_SERVICE",
        "BOUND_FOREGROUND_SERVICE", "IMPORTANT_FOREGROUND", "IMPORTANT_BACKGROUND",
        "TRANSIENT_BACKGROUND", "BACKUP", "SERVICE", "RECEIVER", "TOP_SLEEPING",
        "HEAVY_WEIGHT", "HOME", "LAST_ACTIVITY", "CACHED_ACTIVITY", "CACHED_ACTIVITY_CLIENT",
        "CACHED_RECENT", "CACHED_EMPTY",
    )
}
