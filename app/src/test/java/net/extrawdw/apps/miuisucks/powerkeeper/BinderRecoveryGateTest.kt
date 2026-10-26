package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BinderRecoveryGateTest {
    @Test fun stickyReadyPermissionAndResumeDoNotDuplicateRecovery() {
        val gate = BinderRecoveryGate()
        val binder = Any()
        assertTrue(gate.claim(binder))
        assertFalse(gate.claim(binder))
        assertFalse(gate.claim(binder))
    }

    @Test fun aNewBinderQueuesANewSessionEvenBeforeOldWorkFinishes() {
        val gate = BinderRecoveryGate()
        val old = Any()
        val restarted = Any()
        assertTrue(gate.claim(old))
        assertTrue(gate.claim(restarted))
        assertFalse(gate.claim(restarted))
    }

    @Test fun lostPermissionAndBootRequestCanRearmTheSameBinder() {
        val gate = BinderRecoveryGate()
        val binder = Any()
        assertTrue(gate.claim(binder))
        gate.reset()
        assertTrue(gate.claim(binder))
    }

    @Test fun concurrentNotificationsClaimOnlyOnce() {
        val gate = BinderRecoveryGate()
        val binder = Any()
        val start = CountDownLatch(1)
        val done = CountDownLatch(20)
        val claims = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(20) {
                pool.submit {
                    try {
                        start.await()
                        if (gate.claim(binder)) claims.incrementAndGet()
                    } finally { done.countDown() }
                }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, claims.get())
        } finally { pool.shutdownNow() }
    }
}
