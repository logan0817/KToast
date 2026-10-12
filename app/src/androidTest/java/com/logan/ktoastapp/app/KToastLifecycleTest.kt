package com.logan.ktoastapp.app

import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoast.KToast
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastContentFactory
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoast.toastDelayed
import com.logan.ktoastapp.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class KToastLifecycleTest {

    @get:Rule
    val rule = activityScenarioRule<MainActivity>()

    @Before
    fun reset() {
        rule.scenario.onActivity { KToast.cancel() }
    }

    @After
    fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { KToast.cancel() }
    }

    @Test
    fun workerCancellationBeforeMainSubmission_deliversOneTerminalCallbackOnMain() {
        lateinit var handle: ToastHandle
        val accepted = AtomicBoolean()
        var terminalCount = 0
        var callbackOnMain = false
        rule.scenario.onActivity {
            val worker = Thread {
                handle = KToast.showHandle("pending", callbacks = KToastCallbacks(
                    onDismiss = { _, _ ->
                        terminalCount++
                        callbackOnMain = Looper.myLooper() == Looper.getMainLooper()
                    }
                ))
                accepted.set(handle.cancel())
            }
            worker.start()
            worker.join()
            assertTrue(accepted.get())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(handle.isCanceled)
        assertTrue(callbackOnMain)
        assertEquals(1, terminalCount)
    }

    @Test
    fun cancelGroupIncludesPendingSubmission_withoutCancelingOtherGroup() {
        lateinit var target: ToastHandle
        lateinit var other: ToastHandle
        rule.scenario.onActivity {
            val worker = Thread {
                target = KToast.showHandle("target", behavior = KToastBehavior(groupKey = "target"))
                other = KToast.showHandle("other", behavior = KToastBehavior(groupKey = "other"))
            }
            worker.start()
            worker.join()
            KToast.cancelGroup("target")
            assertTrue(target.isCanceled)
            assertFalse(other.isFinished)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(ToastHandle.State.SHOWING, other.state)
    }

    @Test
    fun reusedCustomContentDoesNotKeepOldLibraryClickListener() {
        rule.scenario.onActivity { activity ->
            val content = TextView(activity)
            val first = KToast.showHandle("interactive") {
                cancelOnTouch = true
                contentFactory = KToastContentFactory { _, _, _ -> content }
            }
            assertTrue(content.hasOnClickListeners())
            first.cancel()
            assertFalse(content.hasOnClickListeners())
            assertFalse(content.isClickable)
            val next = KToast.showHandle("passive") {
                cancelOnTouch = false
                contentFactory = KToastContentFactory { _, _, _ -> content }
            }
            assertFalse(content.performClick())
            assertEquals(ToastHandle.State.SHOWING, next.state)
        }
    }

    @Test
    fun cancelAll_finishesSubmissionWaitingForMain_withoutLosingCallbacks() {
        lateinit var handle: ToastHandle
        var terminalCount = 0
        rule.scenario.onActivity {
            val worker = Thread {
                handle = KToast.showHandle("pending", callbacks = KToastCallbacks(
                    onDismiss = { _, _ -> terminalCount++ }
                ))
            }
            worker.start()
            worker.join()
            KToast.cancel()
            assertTrue(handle.isCanceled)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(1, terminalCount)
    }

    @Test
    fun contentFactoryFailure_fallsBack_andCanStillBeCanceled() {
        rule.scenario.onActivity {
            var fallbackCount = 0
            var terminalCount = 0
            val handle = KToast.showHandle("fallback", callbacks = KToastCallbacks(
                onFallbackToSystem = { fallbackCount++ },
                onDismiss = { _, _ -> terminalCount++ }
            )) {
                contentFactory = KToastContentFactory { _, _, _ -> error("factory failure") }
            }
            assertEquals(1, fallbackCount)
            assertTrue(handle.cancel())
            assertTrue(handle.isCanceled)
            assertEquals(1, terminalCount)
        }
    }

    @Test
    fun parentedCustomContent_fallsBackWithoutMutatingExistingView() {
        rule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val existing = TextView(activity).apply { alpha = 0.6f }
            parent.addView(existing)
            var fallbackCount = 0
            val handle = KToast.showHandle("invalid content", callbacks = KToastCallbacks(
                onFallbackToSystem = { fallbackCount++ }
            )) {
                contentFactory = KToastContentFactory { _, _, _ -> existing }
            }
            assertEquals(1, fallbackCount)
            assertEquals(parent, existing.parent)
            assertEquals(0.6f, existing.alpha)
            assertTrue(handle.cancel())
        }
    }

    @Test
    fun recreation_cancelsAttachedToastExactlyOnce() {
        lateinit var handle: ToastHandle
        var terminalCount = 0
        var fallbackCount = 0
        rule.scenario.onActivity {
            handle = KToast.showHandle("recreate", Toast.LENGTH_LONG, callbacks = KToastCallbacks(
                onDismiss = { _, _ -> terminalCount++ },
                onFallbackToSystem = { fallbackCount++ }
            )) { animationDuration = 0L }
            assertEquals(0, fallbackCount)
            assertEquals(ToastHandle.State.SHOWING, handle.state)
        }
        rule.scenario.recreate()
        assertTrue(handle.isCanceled)
        assertEquals(1, terminalCount)
    }

    @Test
    fun detachedContent_finishesHandle_withoutWaitingForTimer() {
        lateinit var content: View
        lateinit var handle: ToastHandle
        var terminalCount = 0
        rule.scenario.onActivity {
            handle = KToast.showHandle("detach", Toast.LENGTH_LONG, callbacks = KToastCallbacks(
                onDismiss = { _, _ -> terminalCount++ }
            )) {
                animationDuration = 0L
                contentFactory = KToastContentFactory { context, message, _ ->
                    TextView(context).apply { text = message }.also { content = it }
                }
            }
        }
        // addView 返回时窗口遍历可能尚未完成，先等待实际附着再验证外部移除。
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        rule.scenario.onActivity { activity ->
            assertTrue(content.isAttachedToWindow)
            activity.windowManager.removeViewImmediate(content)
            assertTrue(handle.isCanceled)
            assertEquals(1, terminalCount)
            assertFalse(handle.cancel())
        }
    }

    @Test
    fun immediateCancellation_removesWindowBeforeAttachment_andAllowsContentReuse() {
        lateinit var content: View
        var terminalCount = 0
        rule.scenario.onActivity { activity ->
            content = TextView(activity)
            val handle = KToast.showHandle("immediate", callbacks = KToastCallbacks(
                onDismiss = { _, _ -> terminalCount++ }
            )) {
                contentFactory = KToastContentFactory { _, _, _ -> content }
            }
            assertTrue(handle.cancel())
            assertTrue(handle.isCanceled)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        rule.scenario.onActivity {
            assertFalse(content.isAttachedToWindow)
            assertNull(content.parent)
            assertEquals(1, terminalCount)
            var fallbackCount = 0
            val next = KToast.showHandle("reuse", callbacks = KToastCallbacks(
                onFallbackToSystem = { fallbackCount++ }
            )) {
                contentFactory = KToastContentFactory { _, _, _ -> content }
            }
            assertEquals(0, fallbackCount)
            assertTrue(next.cancel())
        }
    }

    @Test
    fun cancelAll_allowsNewRequestSubmittedByTerminalCallback() {
        rule.scenario.onActivity {
            lateinit var next: ToastHandle
            val first = KToast.showHandle("first", callbacks = KToastCallbacks(
                onDismiss = { _, _ -> next = KToast.showHandle("next") }
            ))
            KToast.cancel()
            assertTrue(first.isCanceled)
            assertEquals(ToastHandle.State.SHOWING, next.state)
            assertTrue(next.cancel())
        }
    }

    @Test
    fun cancelAll_removesDelayedTasks_butKeepsNewDelayedTaskFromCallback() {
        rule.scenario.onActivity {
            var oldContentCount = 0
            var newContentCount = 0
            val oldTask = "old".toastDelayed(60_000L) {
                contentFactory = KToastContentFactory { context, _, _ ->
                    oldContentCount++
                    TextView(context)
                }
            }!!
            lateinit var nextTask: Runnable
            KToast.showHandle("first", callbacks = KToastCallbacks(onDismiss = { _, _ ->
                nextTask = "new".toastDelayed(60_000L) {
                    contentFactory = KToastContentFactory { context, _, _ ->
                        newContentCount++
                        TextView(context)
                    }
                }!!
            }))
            KToast.cancel()
            oldTask.run()
            nextTask.run()
            assertEquals(0, oldContentCount)
            assertEquals(1, newContentCount)
        }
    }

    @Test
    fun replacementDuringExitAnimation_preservesReplacedReason() {
        rule.scenario.onActivity {
            lateinit var content: View
            var terminalCount = 0
            val first = KToast.showHandle("first", Toast.LENGTH_LONG, callbacks = KToastCallbacks(
                onDismiss = { _, _ -> terminalCount++ }
            )) {
                cancelOnTouch = true
                animationDuration = 500L
                contentFactory = KToastContentFactory { context, message, _ ->
                    TextView(context).apply { text = message }.also { content = it }
                }
            }
            assertTrue(content.performClick())
            val next = KToast.showHandle("replacement")
            assertEquals(ToastHandle.DismissReason.REPLACED, first.dismissReason)
            assertEquals(1, terminalCount)
            assertEquals(ToastHandle.State.SHOWING, next.state)
            assertTrue(next.cancel())
        }
    }

    @Test
    fun actualWindowTimeout_completesAndPromotesQueue() {
        val completed = CountDownLatch(2)
        lateinit var first: ToastHandle
        lateinit var next: ToastHandle
        var fallbackCount = 0
        val callbacks = KToastCallbacks(
            onDismiss = { _, _ -> completed.countDown() },
            onFallbackToSystem = { fallbackCount++ }
        )
        rule.scenario.onActivity {
            first = KToast.showHandle("first", behavior = KToastBehavior(KToastDisplayMode.QUEUE), callbacks = callbacks) {
                animationDuration = 0L
            }
            next = KToast.showHandle("next", behavior = KToastBehavior(KToastDisplayMode.QUEUE), callbacks = callbacks) {
                animationDuration = 0L
            }
            assertEquals(ToastHandle.State.SHOWING, first.state)
            assertEquals(ToastHandle.State.PENDING, next.state)
        }
        assertTrue(completed.await(8L, TimeUnit.SECONDS))
        assertEquals(ToastHandle.DismissReason.COMPLETED, first.dismissReason)
        assertEquals(ToastHandle.DismissReason.COMPLETED, next.dismissReason)
        assertEquals(0, fallbackCount)
    }
}
