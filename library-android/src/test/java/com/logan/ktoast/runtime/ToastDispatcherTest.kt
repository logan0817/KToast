package com.logan.ktoast.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToastDispatcherTest {

    @Test
    fun queueMode_keepsSecondRequestUntilFirstCompletes() {
        val runtime = FakeDispatcherRuntime()
        val scheduler = FakeDebounceScheduler()
        val dispatcher = ToastDispatcher(
            runtime = runtime,
            scheduler = scheduler,
            nowProvider = { runtime.now }
        )

        val first = dispatcher.submit(newRequest("first", KToastDisplayMode.QUEUE))
        val second = dispatcher.submit(newRequest("second", KToastDisplayMode.QUEUE))

        assertEquals(listOf("first"), runtime.shownMessages)
        assertEquals(ToastHandle.State.SHOWING, first.state)
        assertEquals(ToastHandle.State.PENDING, second.state)

        runtime.finishCurrent(dispatcher)

        assertEquals(listOf("first", "second"), runtime.shownMessages)
        assertEquals(ToastHandle.State.SHOWING, second.state)
    }

    @Test
    fun ignoreIfSame_skipsRepeatedMessageWithinWindow() {
        val runtime = FakeDispatcherRuntime()
        val scheduler = FakeDebounceScheduler()
        val dispatcher = ToastDispatcher(
            runtime = runtime,
            scheduler = scheduler,
            nowProvider = { runtime.now }
        )

        dispatcher.submit(newRequest("same", KToastDisplayMode.IGNORE_IF_SAME, windowMillis = 1_000L))
        runtime.finishCurrent(dispatcher)
        runtime.now += 200L
        val ignored = dispatcher.submit(newRequest("same", KToastDisplayMode.IGNORE_IF_SAME, windowMillis = 1_000L))

        assertEquals(listOf("same"), runtime.shownMessages)
        assertEquals(ToastHandle.State.DISMISSED, ignored.state)
        assertEquals(ToastHandle.DismissReason.IGNORED, ignored.dismissReason)
    }

    @Test
    fun debounce_replacesPendingRequestAndShowsLatestAfterWindow() {
        val runtime = FakeDispatcherRuntime()
        val scheduler = FakeDebounceScheduler()
        val dispatcher = ToastDispatcher(
            runtime = runtime,
            scheduler = scheduler,
            nowProvider = { runtime.now }
        )

        val first = dispatcher.submit(newRequest("draft-1", KToastDisplayMode.DEBOUNCE, windowMillis = 500L))
        val second = dispatcher.submit(newRequest("draft-2", KToastDisplayMode.DEBOUNCE, windowMillis = 500L))

        assertEquals(ToastHandle.State.DISMISSED, first.state)
        assertEquals(ToastHandle.DismissReason.DEBOUNCED, first.dismissReason)
        assertTrue(runtime.shownMessages.isEmpty())

        scheduler.runLatest()

        assertEquals(listOf("draft-2"), runtime.shownMessages)
        assertEquals(ToastHandle.State.SHOWING, second.state)
    }

    @Test
    fun cancelPendingHandle_removesQueuedRequest() {
        val runtime = FakeDispatcherRuntime()
        val scheduler = FakeDebounceScheduler()
        val dispatcher = ToastDispatcher(
            runtime = runtime,
            scheduler = scheduler,
            nowProvider = { runtime.now }
        )

        dispatcher.submit(newRequest("first", KToastDisplayMode.QUEUE))
        val second = dispatcher.submit(newRequest("second", KToastDisplayMode.QUEUE))

        assertTrue(second.cancel())
        runtime.finishCurrent(dispatcher)

        assertEquals(listOf("first"), runtime.shownMessages)
        assertEquals(ToastHandle.State.CANCELED, second.state)
        assertTrue(second.isFinished)
    }

    @Test
    fun cancelGroup_cancelsActiveAndQueuedRequestsWithSameKey() {
        val runtime = FakeDispatcherRuntime()
        val scheduler = FakeDebounceScheduler()
        val dispatcher = ToastDispatcher(
            runtime = runtime,
            scheduler = scheduler,
            nowProvider = { runtime.now }
        )

        val first = dispatcher.submit(
            newRequest("upload-1", KToastDisplayMode.QUEUE).copy(
                behavior = KToastBehavior(displayMode = KToastDisplayMode.QUEUE, groupKey = "upload")
            )
        )
        val second = dispatcher.submit(
            newRequest("upload-2", KToastDisplayMode.QUEUE).copy(
                behavior = KToastBehavior(displayMode = KToastDisplayMode.QUEUE, groupKey = "upload")
            )
        )

        dispatcher.cancelGroup("upload")

        assertEquals(ToastHandle.State.CANCELED, first.state)
        assertEquals(ToastHandle.State.CANCELED, second.state)
        assertEquals(listOf("upload-1"), runtime.shownMessages)
    }

    @Test
    fun lifecycleCallbacks_fireInExpectedOrder() {
        val runtime = FakeDispatcherRuntime()
        val scheduler = FakeDebounceScheduler()
        val dispatcher = ToastDispatcher(
            runtime = runtime,
            scheduler = scheduler,
            nowProvider = { runtime.now }
        )
        val events = mutableListOf<String>()

        val handle = dispatcher.submit(
            newRequest(
                message = "hello",
                mode = KToastDisplayMode.REPLACE,
                callbacks = KToastCallbacks(
                    onShow = { events += "show" },
                    onFallbackToSystem = { events += "fallback" },
                    onDismiss = { _, reason -> events += "dismiss:$reason" },
                    onCancel = { events += "cancel" }
                )
            )
        )

        runtime.fallbackCurrent(dispatcher)
        runtime.finishCurrent(dispatcher)

        assertEquals(listOf("show", "fallback", "dismiss:COMPLETED"), events)
        assertFalse(handle.isCanceled)
        assertTrue(handle.isFinished)
    }

    private fun newRequest(
        message: String,
        mode: KToastDisplayMode,
        windowMillis: Long = 300L,
        callbacks: KToastCallbacks = KToastCallbacks()
    ): ToastRequest {
        val handle = ToastHandle()
        return ToastRequest(
            message = message,
            duration = 0,
            behavior = KToastBehavior(
                displayMode = mode,
                windowMillis = windowMillis
            ),
            callbacks = callbacks,
            handle = handle
        )
    }
}

private class FakeDispatcherRuntime : ToastDispatcher.Runtime {
    var now: Long = 0L
    val shownMessages = mutableListOf<String>()
    private var current: ToastRequest? = null

    override fun show(request: ToastRequest) {
        current = request
        shownMessages += request.message.toString()
    }

    override fun cancelActive(reason: ToastHandle.DismissReason) {
        current = null
    }

    fun fallbackCurrent(dispatcher: ToastDispatcher) {
        dispatcher.onFallbackToSystem(current?.handle ?: return)
    }

    fun finishCurrent(dispatcher: ToastDispatcher) {
        val request = current ?: return
        current = null
        dispatcher.onPresentationFinished(request.handle, ToastHandle.DismissReason.COMPLETED)
    }
}

private class FakeDebounceScheduler : ToastDispatcher.Scheduler {
    private var latest: (() -> Unit)? = null

    override fun schedule(delayMillis: Long, action: () -> Unit): ToastDispatcher.Cancellable {
        latest = action
        return ToastDispatcher.Cancellable { latest = null }
    }

    fun runLatest() {
        val action = latest
        latest = null
        action?.invoke()
    }
}
