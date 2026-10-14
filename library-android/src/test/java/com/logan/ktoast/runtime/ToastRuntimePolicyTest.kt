package com.logan.ktoast.runtime

import org.junit.Assert.*
import org.junit.Test

class ToastRuntimePolicyTest {
    @Test fun capacityExcludesActive_andRejectsReplacementWithoutCancelingAnything() {
        val f = PolicyFixture(KToastRuntimePolicy(maxPendingRequests = 1))
        val first = f.submit("active")
        val second = f.submit("queued")
        val rejected = f.submit("replace", KToastDisplayMode.REPLACE)
        assertEquals(ToastHandle.State.SHOWING, first.state)
        assertEquals(ToastHandle.State.PENDING, second.state)
        assertEquals(ToastDropReason.CAPACITY_EXCEEDED, rejected.dropReason)
        assertEquals(listOf("active"), f.shown)
        assertEquals(1, f.admission.pendingCount())
        f.finish()
        assertEquals(ToastHandle.State.SHOWING, second.state)
        assertEquals(0, f.admission.pendingCount())
        f.dispatcher.cancelAll()
        assertEquals(0, f.admission.pendingCount())
    }

    @Test fun expiryCleansQueueTailBeforeHead_withoutStoppingActivePresentation() {
        val f = PolicyFixture(KToastRuntimePolicy(maxQueueWaitMillis = 10L))
        f.now = 100L
        val active = f.submit("active")
        val head = f.submit("head", eligibleAt = 100L)
        var cleaned = 0
        val tail = f.submit("tail", eligibleAt = 95L, cleanup = { cleaned++ })
        f.advanceTo(105L)
        assertEquals(ToastDropReason.EXPIRED, tail.dropReason)
        assertEquals(1, cleaned)
        assertEquals(ToastHandle.State.PENDING, head.state)
        assertEquals(ToastHandle.State.SHOWING, active.state)
        f.advanceTo(110L)
        assertEquals(ToastDropReason.EXPIRED, head.dropReason)
        assertEquals(0, f.admission.pendingCount())
        assertTrue(f.jobs.none { !it.canceled })
    }

    @Test fun intendedDelayAndDebounceWindow_doNotConsumeWaitingLifetime() {
        val f = PolicyFixture(KToastRuntimePolicy(maxQueueWaitMillis = 10L))
        f.submit("active")
        val delayed = f.watch("delayed", 500L)
        val debounced = f.submit("debounced", KToastDisplayMode.DEBOUNCE, window = 200L)
        f.advanceTo(10L)
        assertFalse(delayed.isFinished)
        assertFalse(debounced.isFinished)
        f.advanceTo(209L)
        assertFalse(debounced.isFinished)
        f.advanceTo(210L)
        assertEquals(ToastDropReason.EXPIRED, debounced.dropReason)
        assertFalse(delayed.isFinished)
        f.advanceTo(510L)
        assertEquals(ToastDropReason.EXPIRED, delayed.dropReason)
        assertEquals(0, f.admission.pendingCount())
    }

    @Test fun lateDeliveryDoesNotResetDeadline_orAllowExpiredReplacement() {
        val f = PolicyFixture(KToastRuntimePolicy(maxQueueWaitMillis = 10L))
        val active = f.submit("active")
        val replacement = f.submit("old replacement", KToastDisplayMode.REPLACE, eligibleAt = 0L,
            beforeSubmit = { f.now = 11L })
        assertEquals(ToastDropReason.EXPIRED, replacement.dropReason)
        assertEquals(ToastHandle.State.SHOWING, active.state)
        val debounced = f.submit("late debounce", KToastDisplayMode.DEBOUNCE, window = 100L)
        f.advanceTo(200L, late = true)
        assertEquals(ToastDropReason.EXPIRED, debounced.dropReason)
        assertEquals(listOf("active"), f.shown)
    }

    @Test fun boundedDedupeEvictsLeastRecentlyAccessed_andPreservesVariableWindows() {
        val f = PolicyFixture(KToastRuntimePolicy(maxDedupeEntries = 2))
        f.submit("a", KToastDisplayMode.IGNORE_IF_SAME); f.finish()
        f.submit("b", KToastDisplayMode.IGNORE_IF_SAME); f.finish()
        assertEquals(ToastHandle.DismissReason.IGNORED, f.submit("a", KToastDisplayMode.IGNORE_IF_SAME).dismissReason)
        f.submit("c", KToastDisplayMode.IGNORE_IF_SAME); f.finish()
        assertEquals(ToastHandle.DismissReason.IGNORED, f.submit("a", KToastDisplayMode.IGNORE_IF_SAME).dismissReason)
        assertEquals(ToastHandle.State.SHOWING, f.submit("b", KToastDisplayMode.IGNORE_IF_SAME).state)
        assertEquals(2, f.dispatcher.dedupeEntryCount())
        f.finish()
        f.now = 500L
        // 后来的长窗口仍使用原时间戳，不按之前的短窗口主动清扫。
        assertEquals(ToastHandle.DismissReason.IGNORED,
            f.submit("b", KToastDisplayMode.IGNORE_IF_SAME, window = 1_000L).dismissReason)
    }

    @Test fun terminalCallbacksSeeReasonAndReleasedCapacity_andMayReenterOrThrow() {
        val f = PolicyFixture(KToastRuntimePolicy(maxPendingRequests = 1, maxQueueWaitMillis = 10L))
        f.submit("active")
        val events = mutableListOf<String>()
        lateinit var next: ToastHandle
        f.submit("expire", callbacks = KToastCallbacks(
            onShow = { fail("Expired request was shown") },
            onCancel = { events += "cancel:${it.dropReason}"; error("business callback") },
            onDismiss = { handle, reason ->
                assertEquals(ToastDropReason.EXPIRED, handle.dropReason)
                assertEquals(ToastHandle.DismissReason.CANCELED, reason)
                events += "dismiss"
                next = f.submit("new from callback")
            }
        ))
        f.advanceTo(10L)
        assertEquals(listOf("cancel:EXPIRED", "dismiss"), events)
        assertEquals(ToastHandle.State.PENDING, next.state)
        assertEquals(1, f.errors.size)
        f.dispatcher.cancelAll()
        assertEquals(0, f.admission.pendingCount())
    }

    @Test fun watchedDelaysAreIncludedInGroupAndGlobalCancellation() {
        val f = PolicyFixture(KToastRuntimePolicy(maxQueueWaitMillis = 10L))
        val one = f.watch("one", 500L, "one")
        val two = f.watch("two", 500L, "two")
        f.dispatcher.cancelGroup("one")
        assertTrue(one.isCanceled)
        assertFalse(two.isFinished)
        assertNull(one.dropReason)
        f.dispatcher.cancelAll()
        assertTrue(two.isCanceled)
        f.advanceTo(1_000L)
        assertNull(two.dropReason)
        assertEquals(0, f.admission.pendingCount())
        assertTrue(f.jobs.none { !it.canceled })
    }

    @Test fun staleExpiryTaskCannotConsumeNewRequest_atTheSameDeadline() {
        val f = PolicyFixture(KToastRuntimePolicy(maxQueueWaitMillis = 10L))
        val old = f.watch("old", 100L)
        val stale = f.jobs.last().action
        old.cancel()
        val newer = f.watch("new", 100L)
        f.now = 200L
        stale()
        assertFalse(newer.isFinished)
        f.advanceTo(200L, late = true)
        assertEquals(ToastDropReason.EXPIRED, newer.dropReason)
    }

    @Test fun renderingAndExpirySchedulerFailure_releaseEveryAdmission() {
        val f = PolicyFixture(KToastRuntimePolicy(maxQueueWaitMillis = 10L))
        f.failShow = true
        assertTrue(f.submit("broken renderer").isCanceled)
        assertEquals(0, f.admission.pendingCount())
        f.failShow = false
        f.submit("active")
        f.failNextSchedule = true
        val queued = f.submit("broken scheduler")
        assertTrue(queued.isCanceled)
        assertNull(queued.dropReason)
        assertEquals(0, f.admission.pendingCount())
        assertEquals(2, f.errors.size)
    }

    @Test fun capacityRejectionDoesNotWriteDedupeHistory() {
        val f = PolicyFixture(KToastRuntimePolicy(maxPendingRequests = 1))
        f.watch("delayed", 100L)
        val rejected = f.submit("same", KToastDisplayMode.IGNORE_IF_SAME)
        assertEquals(ToastDropReason.CAPACITY_EXCEEDED, rejected.dropReason)
        assertEquals(0, f.dispatcher.dedupeEntryCount())
        f.dispatcher.cancelAll()
        assertEquals(ToastHandle.State.SHOWING, f.submit("same", KToastDisplayMode.IGNORE_IF_SAME).state)
    }

    @Test fun mixedBurstRemainsBounded_andEveryHandleEndsExactlyOnce() {
        val f = PolicyFixture(KToastRuntimePolicy(16, 100L, 32))
        val terminals = mutableMapOf<Long, Int>()
        val handles = mutableListOf<ToastHandle>()
        repeat(5_000) { index ->
            handles += f.submit("message-${index % 57}", KToastDisplayMode.values()[index % 4],
                group = "group-${index % 3}", callbacks = KToastCallbacks(onDismiss = { handle, _ ->
                    terminals[handle.id] = (terminals[handle.id] ?: 0) + 1
                }))
            if (index % 17 == 0) f.finish()
            if (index % 19 == 0) f.dispatcher.cancelGroup("group-1")
            if (index % 23 == 0) handles.last().cancel()
            if (index % 53 == 0) f.advanceTo(f.now + 101L)
            assertTrue(f.admission.pendingCount() in 0..16)
            assertTrue(f.dispatcher.dedupeEntryCount() <= 32)
        }
        f.dispatcher.cancelAll()
        f.advanceTo(f.now + 10_000L)
        assertEquals(0, f.admission.pendingCount())
        assertTrue(f.jobs.none { !it.canceled })
        assertTrue(handles.all { it.isFinished })
        assertEquals(5_000, terminals.size)
        assertTrue(terminals.values.all { it == 1 })
        assertTrue(f.errors.isEmpty())
    }
}

private class PolicyFixture(policy: KToastRuntimePolicy) : ToastDispatcher.Runtime, ToastDispatcher.Scheduler {
    data class Job(val dueAt: Long, val action: () -> Unit, var canceled: Boolean = false)
    val admission = ToastAdmission().apply { configure(policy) }
    var now = 0L
    val shown = mutableListOf<String>()
    val errors = mutableListOf<Exception>()
    val jobs = mutableListOf<Job>()
    var failShow = false
    var failNextSchedule = false
    private var active: ToastRequest? = null
    val dispatcher = ToastDispatcher(this, this, { now }, errors::add, admission::policy)

    fun submit(
        message: String,
        mode: KToastDisplayMode = KToastDisplayMode.QUEUE,
        window: Long = 300L,
        group: String? = null,
        eligibleAt: Long? = now,
        callbacks: KToastCallbacks = KToastCallbacks(),
        cleanup: (() -> Unit)? = null,
        beforeSubmit: () -> Unit = {}
    ): ToastHandle {
        val ticket = admission.acquire()
        val request = ToastRequest(message, 0, behavior = KToastBehavior(mode, window, group),
            callbacks = callbacks, admission = ticket,
            eligibleAt = eligibleAt.takeUnless { mode == KToastDisplayMode.DEBOUNCE }, cleanup = cleanup)
        dispatcher.prepare(request)
        if (ticket == null) dispatcher.reject(request, ToastDropReason.CAPACITY_EXCEEDED)
        else {
            ticket.endPreparation()
            beforeSubmit()
            dispatcher.submit(request)
        }
        return request.handle
    }

    fun watch(message: String, eligibleAt: Long, group: String? = null): ToastHandle {
        val ticket = admission.acquire()!!
        val request = ToastRequest(message, 0, behavior = KToastBehavior(groupKey = group),
            admission = ticket, eligibleAt = eligibleAt)
        ticket.endPreparation()
        dispatcher.prepare(request)
        dispatcher.watch(request)
        return request.handle
    }

    override fun show(request: ToastRequest) {
        if (failShow) error("renderer")
        active = request
        shown += request.message.toString()
    }

    override fun cancelActive(reason: ToastHandle.DismissReason) {
        val current = active
        active = null
        current?.let { dispatcher.onPresentationFinished(it.handle, reason) }
    }

    fun finish() {
        val current = active ?: return
        active = null
        dispatcher.onPresentationFinished(current.handle, ToastHandle.DismissReason.COMPLETED)
    }

    override fun schedule(delayMillis: Long, action: () -> Unit): ToastDispatcher.Cancellable {
        if (failNextSchedule) { failNextSchedule = false; error("scheduler") }
        val job = Job(ToastTime.add(now, delayMillis), action)
        jobs += job
        return ToastDispatcher.Cancellable { job.canceled = true }
    }

    fun advanceTo(target: Long, late: Boolean = false) {
        while (true) {
            val job = jobs.filter { !it.canceled && it.dueAt <= target }.minByOrNull { it.dueAt } ?: break
            job.canceled = true
            now = if (late) target else job.dueAt
            job.action()
        }
        now = target
    }
}
