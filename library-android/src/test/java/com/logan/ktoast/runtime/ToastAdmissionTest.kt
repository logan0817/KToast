package com.logan.ktoast.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections

class ToastAdmissionTest {
    @Test fun validatesPositiveLimits_andProtectsTimeAddition() {
        listOf<() -> Unit>(
            { KToastRuntimePolicy(maxPendingRequests = 0) },
            { KToastRuntimePolicy(maxQueueWaitMillis = -1L) },
            { KToastRuntimePolicy(maxDedupeEntries = 0) }
        ).forEach { construct ->
            assertThrows(IllegalArgumentException::class.java) { construct() }
        }
        assertEquals(Long.MAX_VALUE, ToastTime.add(Long.MAX_VALUE - 2L, 10L))
        assertEquals(5L, ToastTime.add(5L, -10L))
    }

    @Test fun firstAdmissionFreezesPolicy_evenAfterAllRequestsEnd() {
        val admission = ToastAdmission()
        admission.configure(KToastRuntimePolicy(maxPendingRequests = 2))
        admission.configure(KToastRuntimePolicy(maxPendingRequests = 1))
        val ticket = admission.acquire()!!
        assertNull(admission.acquire())
        ticket.endPreparation()
        ticket.release()
        assertEquals(0, admission.pendingCount())
        assertThrows(IllegalStateException::class.java) { admission.configure(KToastRuntimePolicy()) }
    }

    @Test fun cancellationDuringPreparation_doesNotReuseTheSlotUntilDslExits() {
        val admission = ToastAdmission()
        admission.configure(KToastRuntimePolicy(maxPendingRequests = 1))
        val ticket = admission.acquire()!!
        ticket.release()
        ticket.release()
        assertEquals(1, admission.pendingCount())
        assertNull(admission.acquire())
        ticket.endPreparation()
        assertEquals(0, admission.pendingCount())
        ticket.release()
        assertEquals(0, admission.pendingCount())
        assertNotNull(admission.acquire())
    }

    @Test fun legacyDelayTransfersOneSlot_andReleaseIsIdempotent() {
        val admission = ToastAdmission()
        val ticket = admission.acquire()!!
        ticket.endPreparation()
        ticket.beginPreparation()
        assertEquals(1, admission.pendingCount())
        ticket.release()
        assertEquals(1, admission.pendingCount())
        ticket.endPreparation()
        assertEquals(0, admission.pendingCount())
        assertThrows(IllegalStateException::class.java) { ticket.beginPreparation() }
    }

    @Test fun concurrentAdmissionIsBounded_andConfigurationRaceUsesOneSnapshot() {
        val admission = ToastAdmission()
        admission.configure(KToastRuntimePolicy(maxPendingRequests = 4))
        val start = CountDownLatch(1)
        val done = CountDownLatch(32)
        val tickets = Collections.synchronizedList(mutableListOf<ToastAdmission.Ticket>())
        repeat(32) {
            Thread {
                try {
                    check(start.await(5L, TimeUnit.SECONDS))
                    admission.acquire()?.let { tickets.add(it) }
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5L, TimeUnit.SECONDS))
        assertEquals(4, tickets.size)
        assertEquals(4, admission.pendingCount())
        tickets.forEach { it.endPreparation(); it.release() }
        assertEquals(0, admission.pendingCount())

        val racing = ToastAdmission()
        val gate = CountDownLatch(1)
        var accepted: ToastAdmission.Ticket? = null
        val worker = Thread { gate.await(); accepted = racing.acquire() }
        worker.start()
        gate.countDown()
        val configured = try {
            racing.configure(KToastRuntimePolicy(maxPendingRequests = 7))
            true
        } catch (_: IllegalStateException) { false }
        worker.join(5_000L)
        assertFalse(worker.isAlive)
        assertEquals(if (configured) 7 else null, accepted!!.policy.maxPendingRequests)
        accepted!!.endPreparation()
        accepted!!.release()
    }
}
