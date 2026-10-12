package com.logan.ktoast.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class ToastDispatcherRegressionTest {

    @Test
    fun cancelGroup_includesPreparedRequests_andKeepsOtherGroup() {
        val fixture = Fixture()
        val pending = fixture.request("pending", group = "target")
        val other = fixture.request("other", group = "other")
        fixture.dispatcher.prepare(pending)
        fixture.dispatcher.prepare(other)
        assertTrue(fixture.dispatcher.cancelGroup("target", listOf(pending, other)))
        fixture.dispatcher.submit(pending)
        fixture.dispatcher.submit(other)
        assertTrue(pending.handle.isCanceled)
        assertEquals(listOf("other"), fixture.shown)
    }

    @Test
    fun replace_clearsOldQueueWithoutShowingIt_forBothRuntimeCallbackModes() {
        listOf(false, true).forEach { synchronous ->
            val fixture = Fixture(synchronous)
            val first = fixture.submit("first")
            val queued = fixture.submit("queued")
            val replacement = fixture.submit("replacement", KToastDisplayMode.REPLACE)

            assertEquals(listOf("first", "replacement"), fixture.shown)
            assertEquals(ToastHandle.DismissReason.REPLACED, first.dismissReason)
            assertEquals(ToastHandle.DismissReason.REPLACED, queued.dismissReason)
            assertEquals(ToastHandle.State.SHOWING, replacement.state)
            fixture.finish()
            assertTrue(listOf(first, queued, replacement).all { it.isFinished })
        }
    }

    @Test
    fun dismissCallback_canSubmitReplacementWithoutOrphaningEitherHandle() {
        val fixture = Fixture()
        lateinit var fromCallback: ToastHandle
        val first = fixture.submit("first", callbacks = KToastCallbacks(
            onDismiss = { _, _ -> fromCallback = fixture.submit("callback", KToastDisplayMode.REPLACE) }
        ))
        val replacement = fixture.submit("replacement", KToastDisplayMode.REPLACE)

        assertTrue(first.isFinished)
        assertEquals(ToastHandle.DismissReason.REPLACED, replacement.dismissReason)
        assertEquals(ToastHandle.State.SHOWING, fromCallback.state)
        fixture.finish()
        assertTrue(fromCallback.isFinished)
    }

    @Test
    fun showCallback_canCancelItsOwnHandle_andPromoteQueuedRequest() {
        val fixture = Fixture()
        val first = fixture.submit("first", callbacks = KToastCallbacks(onShow = { it.cancel() }))
        val second = fixture.submit("second")

        assertTrue(first.isCanceled)
        assertEquals(ToastHandle.State.SHOWING, second.state)
        assertEquals(listOf("first", "second"), fixture.shown)
    }

    @Test
    fun throwingCallbacks_areReported_andDoNotBlockTerminalCallbacksOrQueue() {
        val fixture = Fixture()
        val events = mutableListOf<String>()
        val first = fixture.submit("first", callbacks = KToastCallbacks(
            onShow = { error("onShow") },
            onCancel = { error("onCancel") },
            onDismiss = { _, _ -> events += "dismiss"; error("onDismiss") }
        ))
        val second = fixture.submit("second")
        assertTrue(first.cancel())

        assertEquals(listOf("onShow", "onCancel", "onDismiss"), fixture.errors.map { it.message })
        assertEquals(listOf("dismiss"), events)
        assertEquals(ToastHandle.State.SHOWING, second.state)
    }

    @Test
    fun throwingFallbackCallback_doesNotPreventCompletion() {
        val fixture = Fixture()
        val handle = fixture.submit("first", callbacks = KToastCallbacks(
            onFallbackToSystem = { error("fallback") }
        ))
        fixture.dispatcher.onFallbackToSystem(handle)
        fixture.finish()
        assertTrue(handle.isFinished)
        assertEquals("fallback", fixture.errors.single().message)
    }

    @Test
    fun synchronousFallback_preservesFallbackBeforeShowCallbackOrder() {
        val fixture = Fixture()
        val events = mutableListOf<String>()
        fixture.onShow = { fixture.dispatcher.onFallbackToSystem(it.handle) }
        fixture.submit("first", callbacks = KToastCallbacks(
            onFallbackToSystem = { events += "fallback" },
            onShow = { events += "show" }
        ))
        assertEquals(listOf("fallback", "show"), events)
    }

    @Test
    fun runtimeFailure_finishesFailedHandle_andContinuesQueuedRequests() {
        val fixture = Fixture()
        val first = fixture.submit("first")
        var failedShown = false
        val failed = fixture.submit("failed", callbacks = KToastCallbacks(onShow = { failedShown = true }))
        val next = fixture.submit("next")
        fixture.onShow = { if (it.message == "failed") error("render failure") }
        fixture.finish()

        assertTrue(first.isFinished)
        assertTrue(failed.isCanceled)
        assertFalse(failedShown)
        assertEquals(ToastHandle.State.SHOWING, next.state)
        assertEquals("render failure", fixture.errors.single().message)
    }

    @Test
    fun runtimeCancelFailure_stillFinishesOldHandleAndShowsReplacement() {
        val fixture = Fixture()
        val first = fixture.submit("first")
        fixture.cancelFailure = IllegalStateException("cancel failure")
        val next = fixture.submit("next", KToastDisplayMode.REPLACE)
        assertTrue(first.isFinished)
        assertEquals(ToastHandle.State.SHOWING, next.state)
        assertEquals("cancel failure", fixture.errors.single().message)
    }

    @Test
    fun cancelAll_doesNotPromoteQueuedRequests_andReentrantSubmissionSurvives() {
        val fixture = Fixture()
        lateinit var fromCallback: ToastHandle
        val first = fixture.submit("first", callbacks = KToastCallbacks(
            onDismiss = { _, _ -> fromCallback = fixture.submit("new") }
        ))
        val queued = fixture.submit("queued")
        val debounced = fixture.submit("debounced", KToastDisplayMode.DEBOUNCE)
        fixture.dispatcher.cancelAll()

        assertTrue(listOf(first, queued, debounced).all { it.isCanceled })
        assertEquals(listOf("first", "new"), fixture.shown)
        assertEquals(ToastHandle.State.SHOWING, fromCallback.state)
    }

    @Test
    fun cancelAll_includesRequestsPreparedBeforeMainThreadSubmission() {
        val fixture = Fixture()
        val pending = fixture.request("pending")
        var dismissCount = 0
        pending.callbacks.onDismiss = { _, _ -> dismissCount++ }
        fixture.dispatcher.prepare(pending)
        fixture.dispatcher.cancelAll(listOf(pending))
        fixture.dispatcher.submit(pending)
        assertTrue(pending.handle.isCanceled)
        assertEquals(1, dismissCount)
        assertTrue(fixture.shown.isEmpty())
    }

    @Test
    fun cancelGroup_finishesOnlyMatchingRequests_withoutFlashingMatchingQueue() {
        val fixture = Fixture()
        val first = fixture.request("first", group = "upload")
        val queued = fixture.request("queued", group = "upload")
        val other = fixture.request("other", group = "other")
        listOf(first, queued, other).forEach { fixture.dispatcher.submit(it) }
        assertTrue(fixture.dispatcher.cancelGroup("upload"))
        assertTrue(first.handle.isCanceled)
        assertTrue(queued.handle.isCanceled)
        assertEquals(ToastHandle.State.SHOWING, other.handle.state)
        assertEquals(listOf("first", "other"), fixture.shown)
    }

    @Test
    fun cancelPreparedHandle_beforeSubmit_neverShowsOrNotifiesTwice() {
        val fixture = Fixture()
        val request = fixture.request("pending")
        var canceled = 0
        var dismissed = 0
        request.callbacks.onCancel = { canceled++ }
        request.callbacks.onDismiss = { _, _ -> dismissed++ }
        fixture.dispatcher.prepare(request)
        assertTrue(request.handle.cancel())
        fixture.dispatcher.submit(request)
        assertFalse(request.handle.cancel())
        assertTrue(request.handle.isCanceled)
        assertEquals(1, canceled)
        assertEquals(1, dismissed)
        assertTrue(fixture.shown.isEmpty())
    }

    @Test
    fun workerCancellation_isAcceptedOnce_andRunsThroughPreparedExecutor() {
        val fixture = Fixture()
        val mainThread = Thread.currentThread()
        val actions = ArrayDeque<() -> Unit>()
        val request = fixture.request("active")
        var callbackThread: Thread? = null
        request.callbacks.onCancel = { callbackThread = Thread.currentThread() }
        fixture.dispatcher.prepare(request) { synchronized(actions) { actions.addLast(it) } }
        fixture.dispatcher.submit(request)
        val accepted = AtomicInteger()
        val workers = (1..8).map {
            Thread { if (request.handle.cancel()) accepted.incrementAndGet() }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(1, accepted.get())
        assertEquals(ToastHandle.State.SHOWING, request.handle.state)
        actions.removeFirst().invoke()
        assertTrue(request.handle.isCanceled)
        assertEquals(mainThread, callbackThread)
    }

    @Test
    fun workerCancellation_beforeSubmission_isHonoredEvenBeforeCancelTaskRuns() {
        val fixture = Fixture()
        val actions = ArrayDeque<() -> Unit>()
        val request = fixture.request("pending")
        fixture.dispatcher.prepare(request) { actions.addLast(it) }
        val worker = Thread { assertTrue(request.handle.cancel()) }
        worker.start()
        worker.join()
        fixture.dispatcher.submit(request)
        actions.removeFirst().invoke()
        assertTrue(request.handle.isCanceled)
        assertTrue(fixture.shown.isEmpty())
    }

    @Test
    fun duplicateCompletion_andStaleRendererCallbacks_doNotFinishNextRequest() {
        val fixture = Fixture()
        var count = 0
        val first = fixture.submit("first", callbacks = KToastCallbacks(onDismiss = { _, _ -> count++ }))
        val next = fixture.submit("next")
        fixture.finish()
        fixture.dispatcher.onPresentationFinished(first, ToastHandle.DismissReason.CANCELED)
        fixture.dispatcher.onFallbackToSystem(first)
        assertEquals(1, count)
        assertEquals(ToastHandle.State.SHOWING, next.state)
        assertEquals(ToastHandle.DismissReason.COMPLETED, first.dismissReason)
    }

    @Test
    fun staleDebounceTask_cannotConsumeNewerRequest() {
        val fixture = Fixture()
        val first = fixture.submit("first", KToastDisplayMode.DEBOUNCE)
        val next = fixture.submit("next", KToastDisplayMode.DEBOUNCE)
        fixture.scheduled[0].invoke()
        assertTrue(fixture.shown.isEmpty())
        assertEquals(ToastHandle.State.PENDING, next.state)
        fixture.scheduled[1].invoke()
        assertEquals(listOf("next"), fixture.shown)
        assertEquals(ToastHandle.DismissReason.DEBOUNCED, first.dismissReason)
    }

    @Test
    fun debounceScheduleFailure_cancelsPendingHandle() {
        val fixture = Fixture()
        fixture.scheduleFailure = IllegalStateException("schedule failure")
        val handle = fixture.submit("failed", KToastDisplayMode.DEBOUNCE)
        assertTrue(handle.isCanceled)
        assertEquals("schedule failure", fixture.errors.single().message)
    }

    @Test
    fun dedupe_doesNotConfuseGroupAndMessageContainingSeparators() {
        val fixture = Fixture()
        val first = fixture.request("c", KToastDisplayMode.IGNORE_IF_SAME, "a::b")
        val different = fixture.request("b::c", KToastDisplayMode.IGNORE_IF_SAME, "a")
        fixture.dispatcher.submit(first)
        fixture.finish()
        fixture.dispatcher.submit(different)
        assertEquals(ToastHandle.State.SHOWING, different.handle.state)
        assertEquals(listOf("c", "b::c"), fixture.shown)
    }

    @Test
    fun finishedHandle_releasesCancellationDelegate() {
        val fixture = Fixture()
        val handle = fixture.submit("first")
        fixture.finish()
        val field = ToastHandle::class.java.getDeclaredField("cancelDelegate")
        field.isAccessible = true
        assertEquals(null, field.get(handle))
        assertFalse(handle.cancel())
    }

    @Test
    fun repeatedRenderFailures_doNotRecurseThroughTheQueue() {
        val fixture = Fixture()
        fixture.submit("first")
        val requests = (1..2_000).map { fixture.submit("failed-$it") }
        val next = fixture.submit("next")
        fixture.onShow = { if (it.message.toString().startsWith("failed-")) error("failed") }
        fixture.finish()
        assertTrue(requests.all { it.isCanceled })
        assertEquals(ToastHandle.State.SHOWING, next.state)
        assertEquals(2_000, fixture.errors.size)
    }

    @Test
    fun mixedStrategiesAndCancellations_leaveEveryHandleInTerminalState() {
        val fixture = Fixture()
        val handles = mutableListOf<ToastHandle>()
        val terminalCounts = mutableMapOf<Long, Int>()
        repeat(500) { index ->
            val request = fixture.request(
                "message-${index % 11}",
                KToastDisplayMode.values()[index % 4],
                "group-${index % 3}",
                KToastCallbacks(onDismiss = { handle, _ ->
                    terminalCounts[handle.id] = (terminalCounts[handle.id] ?: 0) + 1
                })
            )
            handles += fixture.dispatcher.submit(request)
            if (index % 7 == 0) fixture.finish()
            if (index % 9 == 0) fixture.dispatcher.cancelGroup("group-1")
            if (index % 13 == 0) handles.last().cancel()
            if (index % 17 == 0) fixture.scheduled.lastOrNull()?.invoke()
        }
        fixture.dispatcher.cancelAll()
        fixture.scheduled.forEach { it.invoke() }
        assertTrue(handles.all { it.isFinished })
        assertEquals(500, terminalCounts.size)
        assertTrue(terminalCounts.values.all { it == 1 })
        assertTrue(fixture.errors.isEmpty())
    }
}

private class Fixture(private val synchronousCancel: Boolean = true) : ToastDispatcher.Runtime,
    ToastDispatcher.Scheduler {
    val shown = mutableListOf<String>()
    val errors = mutableListOf<Exception>()
    val scheduled = mutableListOf<() -> Unit>()
    var onShow: (ToastRequest) -> Unit = {}
    var cancelFailure: Exception? = null
    var scheduleFailure: Exception? = null
    private var current: ToastRequest? = null
    val dispatcher = ToastDispatcher(this, this, { 0L }, errors::add)

    fun request(
        message: String,
        mode: KToastDisplayMode = KToastDisplayMode.QUEUE,
        group: String? = null,
        callbacks: KToastCallbacks = KToastCallbacks()
    ) = ToastRequest(message, 0, behavior = KToastBehavior(displayMode = mode, groupKey = group), callbacks = callbacks)

    fun submit(
        message: String,
        mode: KToastDisplayMode = KToastDisplayMode.QUEUE,
        callbacks: KToastCallbacks = KToastCallbacks()
    ) = dispatcher.submit(request(message, mode, callbacks = callbacks))

    override fun show(request: ToastRequest) {
        current = request
        onShow(request)
        shown += request.message.toString()
    }

    override fun cancelActive(reason: ToastHandle.DismissReason) {
        val request = current
        current = null
        cancelFailure?.let { throw it }
        if (synchronousCancel && request != null) dispatcher.onPresentationFinished(request.handle, reason)
    }

    override fun schedule(delayMillis: Long, action: () -> Unit): ToastDispatcher.Cancellable {
        scheduleFailure?.let { throw it }
        scheduled += action
        return ToastDispatcher.Cancellable {}
    }

    fun finish() {
        val request = current ?: return
        current = null
        dispatcher.onPresentationFinished(request.handle, ToastHandle.DismissReason.COMPLETED)
    }
}
