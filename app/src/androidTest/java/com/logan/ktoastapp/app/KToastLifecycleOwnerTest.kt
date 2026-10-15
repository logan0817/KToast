package com.logan.ktoastapp.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoast.KToast
import com.logan.ktoast.lifecycle.KToastLifecycleScope
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoastapp.LifecycleDemoActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class KToastLifecycleOwnerTest {
    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { KToast.cancel() }
    }

    @Test fun fragmentSwitchAndViewRecreationCloseOldScope() {
        ActivityScenario.launch(LifecycleDemoActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                val old = host.page.toastScope
                var dismissed = 0
                val delayed = old.showDelayed("A old", 60_000L, callbacks = KToastCallbacks(
                    onDismiss = { _, _ -> dismissed++ }))
                host.switchPage("B")
                assertTrue(old.isClosed)
                assertTrue(delayed.isCanceled)
                assertEquals(1, dismissed)
                assertTrue(old.show("late A").isCanceled)
                host.switchPage("A")
                val fresh = host.page.toastScope
                assertEquals(ToastHandle.State.SHOWING, fresh.show("A new").state)
                host.rebuildPageView()
                assertTrue(fresh.isClosed)
                assertNotSame(fresh, host.page.toastScope)
                assertEquals(ToastHandle.State.SHOWING, host.page.toastScope.show("new view").state)
            }
        }
    }

    @Test fun stoppedOwnerRejectsAndRestartsWithoutRevivingRequests() {
        ActivityScenario.launch(LifecycleDemoActivity::class.java).use { scenario ->
            lateinit var scope: KToastLifecycleScope
            lateinit var delayed: ToastHandle
            scenario.onActivity {
                scope = it.page.toastScope
                delayed = scope.showDelayed("old round", 60_000L)
            }
            scenario.whileActivityStopped {
                assertTrue(delayed.isCanceled)
                val stopped = scope.show("stopped")
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue(stopped.isCanceled)
                assertFalse(scope.isClosed)
            }
            scenario.onActivity { assertEquals(ToastHandle.State.SHOWING, scope.show("new round").state) }
        }
    }

    @Test fun workerPreparingAcrossStopAndResumeCannotUseNewRound() {
        ActivityScenario.launch(LifecycleDemoActivity::class.java).use { scenario ->
            lateinit var scope: KToastLifecycleScope
            scenario.onActivity { scope = it.page.toastScope }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var handle: ToastHandle? = null
            var failure: Throwable? = null
            val worker = Thread {
                try {
                    handle = scope.show("old preparation") {
                        entered.countDown()
                        // 由转场驱动线程放行，避免设备变慢时独立倒计时提前结束准备阶段。
                        release.await()
                    }
                } catch (error: Throwable) { failure = error }
            }
            worker.start()
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                scenario.whileActivityStopped { }
            } finally {
                release.countDown()
                worker.join(5_000)
            }
            assertFalse(worker.isAlive)
            failure?.let { throw AssertionError(it) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(handle!!.isCanceled)
            scenario.onActivity { assertEquals(ToastHandle.State.SHOWING, scope.show("new preparation").state) }
        }
    }

    @Test fun createdDestroyedOwnerAndHostFirstDestructionAreHandled() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        lateinit var scope: KToastLifecycleScope
        lateinit var endedHost: LifecycleDemoActivity
        ActivityScenario.launch(LifecycleDemoActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                endedHost = host
                owner.registry.currentState = Lifecycle.State.CREATED
                scope = KToastLifecycleScope.create(host, owner)
                assertTrue(scope.show("before start").isCanceled)
                owner.registry.currentState = Lifecycle.State.STARTED
                assertEquals(ToastHandle.State.SHOWING, scope.show("started").state)
                scope.cancel()
                KToastLifecycleScope.create(host, owner).use { alreadyStarted ->
                    assertEquals(ToastHandle.State.SHOWING, alreadyStarted.show("already started").state)
                }
                assertEquals(1, owner.registry.observerCount)
                val destroyed = object : LifecycleOwner {
                    val registry = LifecycleRegistry(this)
                    override val lifecycle: Lifecycle get() = registry
                }
                destroyed.registry.currentState = Lifecycle.State.CREATED
                destroyed.registry.currentState = Lifecycle.State.DESTROYED
                assertThrows(IllegalStateException::class.java) {
                    KToastLifecycleScope.create(host, destroyed)
                }
                assertEquals(0, destroyed.registry.observerCount)
            }
        }
        assertTrue(scope.isClosed)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals(0, owner.registry.observerCount)
            assertThrows(IllegalStateException::class.java) {
                KToastLifecycleScope.create(endedHost, owner)
            }
            owner.registry.currentState = Lifecycle.State.DESTROYED
            assertEquals(0, owner.registry.observerCount)
        }
    }

    @Test fun closeCallbackCannotReopenOwner() {
        ActivityScenario.launch(LifecycleDemoActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                val scope = host.page.toastScope
                var calls = 0
                lateinit var fromCallback: ToastHandle
                scope.show("active", callbacks = KToastCallbacks(onDismiss = { _, _ ->
                    calls++
                    fromCallback = scope.show("callback")
                }))
                scope.close()
                scope.close()
                assertTrue(fromCallback.isCanceled)
                assertEquals(1, calls)
            }
        }
    }
}
