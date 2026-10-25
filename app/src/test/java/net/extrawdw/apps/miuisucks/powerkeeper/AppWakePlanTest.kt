package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.*
import org.junit.Test

class AppWakePlanTest {
    private val packageName = "com.larksuite.suite"
    @Test fun onlyConfirmedAbsenceAllowsAutomaticLaunch() {
        assertEquals(AppWakePlan.Presence.ABSENT, AppWakePlan.presence("20 (NONEXISTENT)\n"))
        listOf("2 (TOP)", "10 (SERVICE)", "19 (CACHED_EMPTY)").forEach {
            assertEquals(AppWakePlan.Presence.PRESENT, AppWakePlan.presence(it))
        }
        listOf("", "20", "-1 (UNKNOWN)", "20 (TOP)", "2 (NONEXISTENT)",
            "99 (NEW_STATE)", "Error: permission denied", "20 (NONEXISTENT)\nError: failed").forEach {
            assertEquals(it, AppWakePlan.Presence.UNKNOWN, AppWakePlan.presence(it))
        }
    }

    @Test fun resolvesOnlyAnUnambiguousLarkLauncher() {
        assertEquals("com.larksuite.suite/.MainActivity", AppWakePlan.launcherComponent(
            "priority=0\ncom.larksuite.suite/.MainActivity\n", packageName))
        assertNull(AppWakePlan.launcherComponent("com.other.app/.MainActivity", packageName))
        assertNull(AppWakePlan.launcherComponent("No activity found", packageName))
        assertNull(AppWakePlan.launcherComponent("com.larksuite.suite/.A\ncom.larksuite.suite/.B", packageName))
    }

    @Test fun aSubmittedIntentAloneDoesNotProveLaunchSuccess() {
        assertTrue(AppWakePlan.launchConfirmed("Starting: Intent { ... }\nStatus: ok\nComplete"))
        assertFalse(AppWakePlan.launchConfirmed("Starting: Intent { ... }"))
        assertFalse(AppWakePlan.launchConfirmed("Error: Activity not started"))
        assertFalse(AppWakePlan.launchConfirmed("Status: ok\nError: denied"))
    }
}
