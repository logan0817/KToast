package com.logan.ktoastapp.app

import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoast.KToast
import com.logan.ktoast.KToastScope
import com.logan.ktoast.KToastScopeOptions
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastContentFactory
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.runtime.KToastDiagnosticEvent
import com.logan.ktoast.runtime.KToastDiagnosticListener
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoastapp.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class KToastScopeTest {
    @get:Rule val rule = activityScenarioRule<MainActivity>()
    private val scopes = mutableListOf<KToastScope>()

    private fun scope(options: KToastScopeOptions = KToastScopeOptions()): KToastScope {
        lateinit var result: KToastScope
        rule.scenario.onActivity { result = KToastScope.create(it, options) }
        scopes.add(result)
        return result
    }

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            scopes.forEach { it.close() }
            KToast.cancel()
        }
    }

    @Test fun stopCancelsActiveQueuedDelayed_thenResumeCanShow() {
        val scope = scope()
        lateinit var active: ToastHandle
        lateinit var queued: ToastHandle
        lateinit var delayed: ToastHandle
        var terminalCount = 0
        val callbacks = KToastCallbacks(onDismiss = { _, _ -> terminalCount++ })
        rule.scenario.onActivity {
            active = scope.show("active", Toast.LENGTH_LONG, callbacks = callbacks)
            queued = scope.show("queued", behavior = KToastBehavior(KToastDisplayMode.QUEUE), callbacks = callbacks)
            delayed = scope.showDelayed("delayed", 60_000L, callbacks = callbacks)
            assertEquals(ToastHandle.State.SHOWING, active.state)
        }
        rule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(listOf(active, queued, delayed).all { it.isCanceled })
        assertEquals(3, terminalCount)
        assertFalse(scope.isClosed)
        val stopped = scope.show("stopped", callbacks = callbacks)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(stopped.isCanceled)
        rule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.scenario.onActivity {
            val next = scope.show("resumed")
            assertEquals(ToastHandle.State.SHOWING, next.state)
        }
    }

    @Test fun recreationClosesScope_andCallbackCannotReopenIt() {
        val scope = scope()
        lateinit var active: ToastHandle
        lateinit var fromCallback: ToastHandle
        rule.scenario.onActivity {
            active = scope.show("active", Toast.LENGTH_LONG, callbacks = KToastCallbacks(
                onDismiss = { _, _ -> fromCallback = scope.show("callback") }
            ))
        }
        rule.scenario.recreate()
        assertTrue(active.isCanceled)
        assertTrue(fromCallback.isCanceled)
        assertTrue(scope.isClosed)
        val rejected = scope.showDelayed("after destroy", 60_000L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(rejected.isCanceled)
    }

    @Test fun workerPreparationCannotCrossStopOrReviveStoppedSubmission() {
        val scope = scope()
        for (initiallyStopped in listOf(false, true)) {
            if (initiallyStopped) rule.scenario.moveToState(Lifecycle.State.CREATED)
            assertPreparationCanceled(scope) {
                if (!initiallyStopped) rule.scenario.moveToState(Lifecycle.State.CREATED)
                rule.scenario.moveToState(Lifecycle.State.RESUMED)
            }
        }
    }

    @Test fun cancelIncludesWorkerStillPreparingConfiguration() {
        val scope = scope()
        assertPreparationCanceled(scope) {
            rule.scenario.onActivity { scope.cancel() }
        }
        rule.scenario.onActivity {
            lateinit var next: ToastHandle
            val active = scope.show("active", callbacks = KToastCallbacks(
                onDismiss = { _, _ -> next = scope.show("from callback") }
            ))
            scope.cancel()
            assertTrue(active.isCanceled)
            assertEquals(ToastHandle.State.SHOWING, next.state)
        }
    }

    private fun assertPreparationCanceled(scope: KToastScope, whilePreparing: () -> Unit) {
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        var failure: Throwable? = null
        var handle: ToastHandle? = null
        var shown = 0
        var dismissed = 0
        val worker = Thread {
            try {
                handle = scope.show("preparing", callbacks = KToastCallbacks(
                    onShow = { shown++ },
                    onDismiss = { _, _ -> dismissed++ }
                )) {
                    preparing.countDown()
                    check(release.await(10L, TimeUnit.SECONDS))
                }
            } catch (error: Throwable) {
                failure = error
            } finally {
                returned.countDown()
            }
        }
        worker.start()
        try {
            assertTrue(preparing.await(5L, TimeUnit.SECONDS))
            whilePreparing()
        } finally {
            release.countDown()
        }
        assertTrue(returned.await(5L, TimeUnit.SECONDS))
        failure?.let { throw AssertionError("Worker submission failed", it) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(handle!!.isCanceled)
        assertEquals(0, shown)
        assertEquals(1, dismissed)
    }

    @Test fun cancelAndReplaceAreIsolatedBetweenScopes() {
        val first = scope()
        val second = scope()
        rule.scenario.onActivity {
            val active = first.show("first")
            val oldSecond = second.show("old second")
            val nextSecond = second.show("new second")
            assertEquals(ToastHandle.State.SHOWING, active.state)
            assertEquals(ToastHandle.DismissReason.REPLACED, oldSecond.dismissReason)
            assertEquals(ToastHandle.State.PENDING, nextSecond.state)
            first.cancel()
            assertTrue(active.isCanceled)
            assertEquals(ToastHandle.State.SHOWING, nextSecond.state)
            first.close()
            assertFalse(nextSecond.isFinished)
        }
    }

    @Test fun debounceAndDuplicateHistoryAreIsolatedBetweenScopes() {
        val first = scope()
        val second = scope()
        val shown = CountDownLatch(2)
        lateinit var a: ToastHandle
        lateinit var b: ToastHandle
        rule.scenario.onActivity {
            val behavior = KToastBehavior(KToastDisplayMode.DEBOUNCE, 100L)
            val callbacks = KToastCallbacks(onShow = { shown.countDown(); it.cancel() })
            a = first.show("debounced", behavior = behavior, callbacks = callbacks)
            b = second.show("debounced", behavior = behavior, callbacks = callbacks)
        }
        assertTrue(shown.await(4L, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(a.isCanceled && b.isCanceled)
        rule.scenario.onActivity {
            val behavior = KToastBehavior(KToastDisplayMode.IGNORE_IF_SAME, 60_000L)
            val accepted = first.show("same", behavior = behavior)
            accepted.cancel()
            val ignored = first.show("same", behavior = behavior)
            val other = second.show("same", behavior = behavior)
            assertEquals(ToastHandle.DismissReason.IGNORED, ignored.dismissReason)
            assertEquals(ToastHandle.State.SHOWING, other.state)
        }
    }

    @Test fun delayedHandleCanBeCanceledBeforeMainSubmission() {
        val scope = scope()
        lateinit var handle: ToastHandle
        var count = 0
        rule.scenario.onActivity {
            val worker = Thread {
                handle = scope.showDelayed("delayed", 60_000L, callbacks = KToastCallbacks(
                    onDismiss = { _, _ -> count++ }
                ))
                assertTrue(handle.cancel())
            }
            worker.start()
            worker.join()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(handle.isCanceled)
        assertEquals(1, count)
        assertFalse(handle.cancel())
    }

    @Test fun scopedFactoryFailureCancels_withoutSystemTransfer() {
        val scope = scope()
        rule.scenario.onActivity {
            var fallbacks = 0
            val failed = scope.show("factory", callbacks = KToastCallbacks(
                onFallbackToSystem = { fallbacks++ }
            )) { contentFactory = KToastContentFactory { _, _, _ -> error("factory") } }
            assertTrue(failed.isCanceled)
            assertEquals(0, fallbacks)
        }
    }

    @Test fun customContentIsVisibleConstrainedAndReusable_afterClose() {
        val scope = scope()
        lateinit var content: View
        lateinit var root: View
        rule.scenario.onActivity {
            scope.show("large", Toast.LENGTH_LONG) {
                animationDuration = 0L
                maxWidthRatio = 0.8f
                contentFactory = KToastContentFactory { context, _, _ ->
                    TextView(context).apply {
                        // minimumWidth 会被样式覆盖；显式尺寸确保实际测到超大内容。
                        layoutParams = FrameLayout.LayoutParams(6000, FrameLayout.LayoutParams.WRAP_CONTENT)
                        text = "large"
                    }.also { content = it }
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        rule.scenario.onActivity { host ->
            root = content.rootView
            assertTrue(root.isAttachedToWindow)
            assertEquals(1f, content.alpha)
            assertEquals(1f, root.alpha)
            assertEquals(6000, content.width)
            assertTrue(root.width > 0 && root.width <= (host.window.decorView.width * 0.8f).toInt())
            scope.close()
            assertNull(content.parent)
            assertFalse(root.isAttachedToWindow)
            val another = KToastScope.create(host)
            scopes.add(another)
            val reused = another.show("reuse") {
                contentFactory = KToastContentFactory { _, _, _ -> content }
            }
            assertEquals(ToastHandle.State.SHOWING, reused.state)
        }
    }

    @Test fun themeDefaultsCanBeOverriddenByLocalConfig() {
        val scope = scope(KToastScopeOptions(followHostTheme = true))
        rule.scenario.onActivity {
            var observed = 0
            scope.show("theme") {
                textColor = 0xff123456.toInt()
                contentFactory = KToastContentFactory { context, _, config ->
                    observed = config.textColor
                    TextView(context)
                }
            }
            assertEquals(0xff123456.toInt(), observed)
        }
    }

    @Test fun nonFiniteWidthRatioStillRendersDefaultBubbleText() {
        val scope = scope()
        val message = "scope width fallback"
        for (ratio in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            rule.scenario.onActivity {
                scope.show(message, Toast.LENGTH_LONG) {
                    maxWidthRatio = ratio
                    animationDuration = 0L
                }
            }
            onView(withText(message))
                .inRoot(withDecorView(hasDescendant(withText(message))))
                .check(matches(isDisplayed()))
        }
    }

    @Test fun diagnosticFailureIsIsolated_andRenderErrorDoesNotReportShown() {
        val events = mutableListOf<KToastDiagnosticEvent>()
        val scope = scope(KToastScopeOptions(diagnostics = KToastDiagnosticListener { _, event, _ ->
            events.add(event)
            error("diagnostic callback")
        }))
        rule.scenario.onActivity {
            var shown = 0
            val failed = scope.show("failed", callbacks = KToastCallbacks(onShow = { shown++ })) {
                contentFactory = KToastContentFactory { _, _, _ -> error("render") }
            }
            assertTrue(failed.isCanceled)
            assertEquals(0, shown)
            assertEquals(listOf(KToastDiagnosticEvent.ERROR, KToastDiagnosticEvent.DISMISSED), events)
            val next = scope.show("next")
            assertEquals(ToastHandle.State.SHOWING, next.state)
            next.cancel()
        }
    }
}
