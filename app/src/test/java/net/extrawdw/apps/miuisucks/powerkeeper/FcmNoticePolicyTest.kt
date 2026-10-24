package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Test
import net.extrawdw.apps.miuisucks.powerkeeper.FcmNoticePolicy.Notice.*

class FcmNoticePolicyTest {
    @Test fun bootWithoutShizukuWaitsSilently() {
        assertEquals(NONE, FcmNoticePolicy.select(running = false, granted = false))
    }
    @Test fun dependencyFailureIsOwnedByShizukuEvenAfterRetries() {
        assertEquals(NONE, FcmNoticePolicy.select(running = false, granted = false, finalFailure = true))
    }
    @Test fun authorizedStartupRecoveryAndSuccessAreSilent() {
        assertEquals(NONE, FcmNoticePolicy.select(running = true, granted = true))
    }
    @Test fun missingPermissionRequestsAuthorizationInsteadOfGenericFailure() {
        assertEquals(PERMISSION_REQUIRED, FcmNoticePolicy.select(running = true, granted = false))
        assertEquals(PERMISSION_REQUIRED, FcmNoticePolicy.select(running = true, granted = false, finalFailure = true))
    }
    @Test fun exhaustedExecutionFailuresRemainActionable() {
        assertEquals(FINAL_FAILURE, FcmNoticePolicy.select(running = true, granted = true, finalFailure = true))
    }
}
