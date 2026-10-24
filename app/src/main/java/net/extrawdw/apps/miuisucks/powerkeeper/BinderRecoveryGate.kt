package net.extrawdw.apps.miuisucks.powerkeeper

/** Identity-based deduplication: a restarted Binder must never reuse an old claim. */
internal class BinderRecoveryGate {
    private var claimedBinder: Any? = null

    @Synchronized
    fun claim(binder: Any): Boolean {
        if (claimedBinder === binder) return false
        claimedBinder = binder
        return true
    }

    @Synchronized
    fun reset() {
        claimedBinder = null
    }
}
