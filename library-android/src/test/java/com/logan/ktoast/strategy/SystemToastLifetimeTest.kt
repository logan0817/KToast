package com.logan.ktoast.strategy

import com.logan.ktoast.runtime.ToastDispatcher
import com.logan.ktoast.runtime.ToastHandle.DismissReason
import org.junit.Assert.*
import org.junit.Test

class SystemToastLifetimeTest {
    @Test fun extendedNativeToastRemainsCancelableAfterLegacyDuration() {
        val f = Fixture(displayMillis = 10_000L)
        f.session.onShown()
        f.advanceTo(3500L)
        assertTrue(f.events.isEmpty())
        f.session.cancel(DismissReason.CANCELED)
        assertEquals(listOf("cancel-native", "dismiss:CANCELED"), f.events)
        f.session.onHidden()
        f.advanceTo(50_000L)
        assertEquals(2, f.events.size)
    }

    @Test fun hiddenCallbackCompletesWithoutCancelingAndIgnoresStaleTimers() {
        val f = Fixture()
        val waitingTimer = f.jobs.single().action
        f.session.onShown()
        f.session.onHidden()
        waitingTimer()
        f.session.onShown()
        f.session.cancel(DismissReason.CANCELED)
        assertEquals(listOf("dismiss:COMPLETED"), f.events)
        assertTrue(f.jobs.all { it.canceled })
    }

    @Test fun nativeQueueDelayStartsDisplayWatchdogAtShownCallback() {
        val f = Fixture()
        val waitingTimer = f.jobs.single().action
        f.advanceTo(10_000L)
        assertTrue(f.events.isEmpty())
        f.session.onShown()
        waitingTimer()
        f.advanceTo(16_999L)
        assertTrue(f.events.isEmpty())
        f.advanceTo(17_000L)
        assertEquals(listOf("cancel-native", "dismiss:CANCELED"), f.events)
    }

    @Test fun missingCallbacksAreBoundedAndCancelNativeBeforeFinishing() {
        val f = Fixture()
        f.advanceTo(32_000L)
        assertEquals(listOf("cancel-native", "dismiss:CANCELED"), f.events)
        f.session.onHidden()
        assertEquals(2, f.events.size)
    }

    @Test fun legacyTimeoutUsesRecommendedDurationAndRemovesNativeToast() {
        val f = Fixture(displayMillis = 10_000L, callbacks = false)
        f.advanceTo(9999L)
        assertTrue(f.events.isEmpty())
        f.advanceTo(10_000L)
        assertEquals(listOf("cancel-native", "dismiss:COMPLETED"), f.events)
    }

    @Test fun cancelReentryAndNativeFailureCannotDuplicateOrLoseTerminalCallback() {
        val f = Fixture()
        f.onCancel = {
            f.session.onHidden()
            error("native cancel failed")
        }
        assertThrows(IllegalStateException::class.java) { f.session.cancel(DismissReason.REPLACED) }
        assertEquals(listOf("cancel-native", "dismiss:REPLACED"), f.events)
        f.advanceTo(50_000L)
        assertEquals(2, f.events.size)
    }

    private class Fixture(displayMillis: Long = 2000L, callbacks: Boolean = true) : ToastDispatcher.Scheduler {
        data class Job(val dueAt: Long, val action: () -> Unit, var canceled: Boolean = false)
        var now = 0L
        val jobs = mutableListOf<Job>()
        val events = mutableListOf<String>()
        var onCancel: () -> Unit = {}
        val session = SystemToastLifetime(this, displayMillis, callbacks,
            cancelToast = { events += "cancel-native"; onCancel() },
            onDismiss = { events += "dismiss:$it" }
        ).also { it.start() }

        override fun schedule(delayMillis: Long, action: () -> Unit): ToastDispatcher.Cancellable {
            val job = Job(now + delayMillis, action)
            jobs += job
            return ToastDispatcher.Cancellable { job.canceled = true }
        }

        fun advanceTo(time: Long) {
            now = time
            jobs.filter { !it.canceled && it.dueAt <= time }.toList().forEach {
                it.canceled = true
                it.action()
            }
        }
    }
}
