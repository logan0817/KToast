package com.logan.ktoastapp.app

import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoast.KToast
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastRuntimePolicy
import com.logan.ktoast.runtime.ToastDropReason
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoastapp.LifecycleDemoActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class KToastRuntimePolicyDeviceTest {
    @Test fun saturatedDelayDoesNotOverflowAndroidHandlerClock() {
        ActivityScenario.launch(LifecycleDemoActivity::class.java).use { scenario ->
            lateinit var active: ToastHandle
            lateinit var delayed: ToastHandle
            lateinit var legacy: Runnable
            scenario.onActivity { host ->
                KToast.cancel()
                active = host.page.toastScope.show("active during huge delay")
                delayed = host.page.toastScope.showDelayed("huge scoped delay", Long.MAX_VALUE)
                legacy = KToast.showDelayed("huge legacy delay", Long.MAX_VALUE)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals(ToastHandle.State.SHOWING, active.state)
                assertEquals(ToastHandle.State.PENDING, delayed.state)
                KToast.cancelDelayed(legacy)
                delayed.cancel()
                KToast.cancel()
            }
        }
    }

    @Test fun legacyDelayedTransferAndDslFailureReturnCapacity() {
        ActivityScenario.launch(LifecycleDemoActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                KToast.cancel()
                val scope = host.page.toastScope
                val legacy = KToast.showDelayed("legacy transfer", 60_000L)
                val pending = (1..31).map { scope.showDelayed("scoped $it", 60_000L) }
                assertEquals(ToastDropReason.CAPACITY_EXCEEDED, scope.show("full").dropReason)
                legacy.run()
                legacy.run()
                KToast.cancelDelayed(legacy)
                try {
                    scope.show("DSL failure") { throw IllegalArgumentException("expected DSL failure") }
                    fail("DSL exception must propagate")
                } catch (_: IllegalArgumentException) {}
                val last = scope.showDelayed("returned slot", 60_000L)
                assertFalse(last.isFinished)
                assertEquals(ToastDropReason.CAPACITY_EXCEEDED, scope.show("full again").dropReason)
                KToast.cancel()
                assertTrue(pending.all { it.isCanceled })
                assertTrue(last.isCanceled)
                legacy.run()
                assertEquals(ToastHandle.State.SHOWING, scope.show("after cleanup").state)
                scope.cancel()
            }
        }
    }

    @Test fun expiredPreparationNotifiesOnceOnMainWithoutShowing() {
        ActivityScenario.launch(LifecycleDemoActivity::class.java).use { scenario ->
            lateinit var scope: com.logan.ktoast.lifecycle.KToastLifecycleScope
            scenario.onActivity { KToast.cancel(); scope = it.page.toastScope }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            var shown = 0
            var canceled = 0
            var failure: Throwable? = null
            var handle: ToastHandle? = null
            val worker = Thread {
                try {
                    handle = scope.show("expired preparation", callbacks = KToastCallbacks(
                        onShow = { shown++ },
                        onCancel = { canceled++ },
                        onDismiss = { result, _ ->
                            try {
                                assertSame(Looper.getMainLooper(), Looper.myLooper())
                                assertEquals(ToastDropReason.EXPIRED, result.dropReason)
                            } catch (error: Throwable) { failure = error }
                            finished.countDown()
                        })) {
                        entered.countDown()
                        check(release.await(20, TimeUnit.SECONDS))
                    }
                } catch (error: Throwable) { failure = error; finished.countDown() }
            }
            worker.start()
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                // Demo 的固定策略为 10 秒；仅等待调用线程准备，不阻塞主线程。
                Thread.sleep(10_050L)
            } finally { release.countDown() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            worker.join(5_000)
            failure?.let { throw AssertionError(it) }
            assertTrue(handle!!.isCanceled)
            assertEquals(0, shown)
            assertEquals(1, canceled)
        }
    }

    @Test fun rejectedRequestSkipsDslAndNotifiesOnceOnMain() {
        ActivityScenario.launch(LifecycleDemoActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                KToast.cancel()
                val scope = host.page.toastScope
                val pending = (1..32).map { scope.showDelayed("pending $it", 60_000L) }
                var callbacks = 0
                var dsl = 0
                val rejected = scope.show("overflow", callbacks = KToastCallbacks(
                    onCancel = { callbacks++; assertSame(Looper.getMainLooper(), Looper.myLooper()) },
                    onDismiss = { handle, _ ->
                        callbacks++
                        assertEquals(ToastDropReason.CAPACITY_EXCEEDED, handle.dropReason)
                    })) { dsl++ }
                assertEquals(0, dsl)
                assertTrue(rejected.isCanceled)
                assertEquals(ToastDropReason.CAPACITY_EXCEEDED, rejected.dropReason)
                assertEquals(2, callbacks)
                assertFalse(rejected.cancel())
                scope.cancel()
                assertTrue(pending.all { it.isCanceled })
                assertEquals(ToastHandle.State.SHOWING, scope.show("capacity returned").state)
                try {
                    KToast.setRuntimePolicy(KToastRuntimePolicy())
                    fail("Policy must be frozen")
                } catch (_: IllegalStateException) {}
                scope.cancel()
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { KToast.cancel() }
    }
}
