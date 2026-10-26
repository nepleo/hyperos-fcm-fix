package net.extrawdw.apps.miuisucks.powerkeeper

/** Normal dependency waiting belongs to Shizuku; FCM only reports actionable failures. */
internal object FcmNoticePolicy {
    enum class Notice { NONE, PERMISSION_REQUIRED, FINAL_FAILURE }

    fun select(running: Boolean, granted: Boolean, finalFailure: Boolean = false): Notice = when {
        !running -> Notice.NONE
        !granted -> Notice.PERMISSION_REQUIRED
        finalFailure -> Notice.FINAL_FAILURE
        else -> Notice.NONE
    }
}
