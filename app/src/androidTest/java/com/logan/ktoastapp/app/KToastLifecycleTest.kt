package com.logan.ktoastapp.app

import android.os.Looper
import android.animation.TimeInterpolator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoast.KToast
import com.logan.ktoast.KToastScope
import com.logan.ktoast.KToastScopeOptions
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.config.KToastAnimation
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastContentFactory
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.runtime.KToastDiagnosticEvent
import com.logan.ktoast.runtime.KToastDiagnosticListener
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
            activity.windowManager.removeViewImmediate(content.rootView)
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
    fun roundedContentBounceStaysInsideWindowForGlobalAndScopedRequests() {
        val animations = listOf(KToastAnimation(), KToastAnimation(
            enterInterpolator = OvershootInterpolator(8f),
            exitInterpolator = AnticipateOvershootInterpolator(4f), maxScale = 1.2f),
            KToastAnimation(enterInterpolator = OvershootInterpolator(50f), maxScale = 2f))
        for ((scoped, animation) in listOf(false, true).flatMap { scoped -> animations.map { scoped to it } }) {
            val finished = CountDownLatch(1)
            var minScale = Float.POSITIVE_INFINITY
            var maxScale = 0f
            var intermediateFrames = 0
            var clippedFrames = 0
            var terminalCount = 0
            var scope: KToastScope? = null
            var animatedRoot: View? = null
            lateinit var content: View
            lateinit var root: View
            lateinit var observer: ViewTreeObserver.OnPreDrawListener
            lateinit var handle: ToastHandle
            rule.scenario.onActivity { host ->
                val config = KToastConfig().apply {
                    animationDuration = 700L
                    this.animation = animation
                    contentFactory = KToastContentFactory { context, message, _ ->
                        TextView(context).apply {
                            text = if (animation.maxScale == 2f) message.toString().repeat(20) else message
                            maxLines = 4
                            setTextColor(Color.WHITE)
                            setPadding(48, 32, 48, 32)
                            background = GradientDrawable().apply {
                                setColor(Color.DKGRAY)
                                cornerRadius = 32f
                            }
                        }.also { content = it }
                    }
                }
                val callbacks = KToastCallbacks(
                    onShow = {
                        root = content.rootView
                        animatedRoot = root
                        observer = ViewTreeObserver.OnPreDrawListener {
                            minScale = minOf(minScale, root.scaleX, root.scaleY)
                            maxScale = maxOf(maxScale, root.scaleX, root.scaleY)
                            if (root.scaleX > 0.8f && root.scaleX < 1f) intermediateFrames++
                            val left = root.pivotX + (content.left - root.pivotX) * root.scaleX
                            val right = root.pivotX + (content.right - root.pivotX) * root.scaleX
                            val top = root.pivotY + (content.top - root.pivotY) * root.scaleY
                            val bottom = root.pivotY + (content.bottom - root.pivotY) * root.scaleY
                            if (left < 0f || top < 0f || right > root.width || bottom > root.height) clippedFrames++
                            true
                        }
                        root.viewTreeObserver.addOnPreDrawListener(observer)
                    },
                    onDismiss = { _, _ ->
                        terminalCount++
                        animatedRoot?.viewTreeObserver?.takeIf { it.isAlive }?.let {
                            it.removeOnPreDrawListener(observer)
                        }
                        finished.countDown()
                    }
                )
                handle = if (scoped) {
                    KToastScope.create(host).also { scope = it }.show("Rounded scoped content", config, callbacks = callbacks)
                } else {
                    KToast.showHandle("Rounded global content", callbacks = callbacks) {
                        animationDuration = config.animationDuration
                        this.animation = config.animation
                        contentFactory = config.contentFactory
                    }
                }
            }
            try {
                assertTrue("Animation never completed: scoped=$scoped", finished.await(6, TimeUnit.SECONDS))
                rule.scenario.onActivity {
                    assertTrue("No animated frames observed: scoped=$scoped", intermediateFrames > 0)
                    assertTrue("Bounce disappeared: scoped=$scoped max=$maxScale", maxScale > 1f)
                    assertTrue("Invalid animated scale: scoped=$scoped min=$minScale", minScale > 0f)
                    assertTrue("Animation exceeds configured limit: scoped=$scoped max=$maxScale", maxScale <= animation.maxScale + 0.0001f)
                    assertEquals("Rounded content leaves its window: scoped=$scoped", 0, clippedFrames)
                    if (animation.maxScale == 2f) {
                        assertTrue("Text collapsed while reserving animation space: width=${content.width}, window=${root.width}",
                            content.width > root.width / 4)
                        assertTrue("Maximum-scale animation was not exercised", maxScale > 1.95f)
                    }
                    assertEquals(ToastHandle.DismissReason.COMPLETED, handle.dismissReason)
                    assertEquals(1, terminalCount)
                    assertFalse(root.isAttachedToWindow)
                }
            } finally {
                rule.scenario.onActivity { scope?.close(); KToast.cancel() }
            }
        }
    }

    @Test
    fun invalidAnimationCancelsOnceReportsErrorAndPromotesQueue() {
        for (entering in listOf(true, false)) {
            for (failure in listOf("nonFinite", "exception", "lastFrame")) {
                val dismissed = CountDownLatch(1)
                var terminalCount = 0
                var errors = 0
                lateinit var scope: KToastScope
                lateinit var failed: ToastHandle
                lateinit var next: ToastHandle
                lateinit var content: View
                lateinit var failedRoot: View
                val invalid = TimeInterpolator { fraction ->
                    when {
                        failure == "lastFrame" && fraction < 1f -> fraction
                        fraction == 0f -> 0f
                        failure == "exception" -> throw IllegalArgumentException("intentional animation failure")
                        else -> Float.NaN
                    }
                }
                rule.scenario.onActivity { host ->
                    scope = KToastScope.create(host, KToastScopeOptions(diagnostics = KToastDiagnosticListener { _, event, _ ->
                        if (event == KToastDiagnosticEvent.ERROR) errors++
                    }))
                    failed = scope.show("invalid animation", Toast.LENGTH_LONG,
                        KToastBehavior(KToastDisplayMode.QUEUE), KToastCallbacks(onDismiss = { _, _ ->
                            terminalCount++
                            dismissed.countDown()
                        })) {
                        animationDuration = 200L
                        animation = if (entering) KToastAnimation(enterInterpolator = invalid)
                        else KToastAnimation(exitInterpolator = invalid)
                        cancelOnTouch = !entering
                        contentFactory = KToastContentFactory { context, message, _ ->
                            TextView(context).apply { text = message }.also { content = it }
                        }
                    }
                    failedRoot = content.rootView
                    next = scope.show("after invalid animation", Toast.LENGTH_LONG,
                        KToastBehavior(KToastDisplayMode.QUEUE)) { animationDuration = 0L }
                }
                try {
                    if (!entering) {
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        rule.scenario.onActivity { assertTrue(content.performClick()) }
                    }
                    assertTrue("Failed animation did not finish: entering=$entering failure=$failure",
                        dismissed.await(4, TimeUnit.SECONDS))
                    rule.scenario.onActivity {
                        assertTrue(failed.isCanceled)
                        assertEquals(1, terminalCount)
                        assertEquals(1, errors)
                        assertFalse(failedRoot.isAttachedToWindow)
                        assertEquals(ToastHandle.State.SHOWING, next.state)
                    }
                } finally {
                    rule.scenario.onActivity { scope.close() }
                }
            }
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
