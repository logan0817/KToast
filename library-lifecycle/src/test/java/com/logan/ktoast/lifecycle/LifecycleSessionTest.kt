package com.logan.ktoast.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class LifecycleSessionTest {
    private class Delegate : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }

    private class Owner(initial: Lifecycle.State = Lifecycle.State.CREATED) : LifecycleOwner {
        val observers = mutableListOf<DefaultLifecycleObserver>()
        private var state = initial
        private val registry = object : Lifecycle() {
            override fun getCurrentState() = state
            override fun addObserver(observer: LifecycleObserver) { observers.add(observer as DefaultLifecycleObserver) }
            override fun removeObserver(observer: LifecycleObserver) { observers.remove(observer) }
        }
        override fun getLifecycle() = registry
        fun start() { state = Lifecycle.State.STARTED; observers.toList().forEach { it.onStart(this) } }
        fun stop() { state = Lifecycle.State.CREATED; observers.toList().forEach { it.onStop(this) } }
        fun destroy() { state = Lifecycle.State.DESTROYED; observers.toList().forEach { it.onDestroy(this) } }
    }

    @Test fun stopAndRestartUsesNewDelegate() {
        val owner = Owner()
        val stopped = Delegate().apply { close() }
        val session = LifecycleSession(owner.lifecycle, stopped, { Delegate() }, { it() })
        assertSame(stopped, session.current())
        owner.start()
        val first = session.current()
        assertFalse(first.closed)
        owner.stop()
        assertTrue(first.closed)
        assertSame(stopped, session.current())
        owner.start()
        assertNotSame(first, session.current())
        session.close()
        assertTrue(session.isClosed)
        owner.start()
        assertSame(stopped, session.current())
        assertTrue(owner.observers.isEmpty())
    }

    @Test fun alreadyStartedCreatesOnlyOneDelegate() {
        val owner = Owner(Lifecycle.State.STARTED)
        var calls = 0
        val session = LifecycleSession(owner.lifecycle, Delegate(), { calls++; Delegate() }, { it() })
        owner.start()
        assertEquals(1, calls)
        session.close()
    }

    @Test fun ownerDestroyUnregistersAndCleansHostExactlyOnce() {
        val owner = Owner()
        var cleaned = 0
        lateinit var session: LifecycleSession<Delegate>
        session = LifecycleSession(owner.lifecycle, Delegate(), { Delegate() }, { it() }) {
            cleaned++
            session.close()
        }
        owner.start()
        val active = session.current()
        owner.destroy()
        session.close()
        assertTrue(active.closed)
        assertTrue(owner.observers.isEmpty())
        assertEquals(1, cleaned)
    }

    @Test fun backgroundCloseMarksClosedBeforeMainCleanup() {
        val owner = Owner()
        val actions = mutableListOf<() -> Unit>()
        var cleaned = 0
        val stopped = Delegate()
        val session = LifecycleSession(owner.lifecycle, stopped, { Delegate() }, { actions.add(it) }) { cleaned++ }
        owner.start()
        val captured = session.current()
        thread { session.close() }.join()
        assertTrue(session.isClosed)
        assertTrue(captured.closed)
        assertSame(stopped, session.current())
        assertEquals(0, cleaned)
        actions.single()()
        assertEquals(1, cleaned)
        assertTrue(owner.observers.isEmpty())
    }

    @Test fun closeDuringFactoryDiscardsNewDelegate() {
        val owner = Owner()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val created = Delegate()
        val session = LifecycleSession(owner.lifecycle, Delegate(), {
            entered.countDown(); resume.await(); created
        }, { it() })
        val starter = thread { owner.start() }
        entered.await()
        session.close()
        resume.countDown()
        starter.join()
        assertTrue(created.closed)
        assertTrue(session.isClosed)
    }

    @Test fun reentrantStopDuringFactoryDoesNotReviveOldRound() {
        val owner = Owner()
        val created = Delegate()
        val stopped = Delegate()
        val session = LifecycleSession(owner.lifecycle, stopped, { owner.stop(); created }, { it() })
        owner.start()
        assertTrue(created.closed)
        assertSame(stopped, session.current())
        session.close()
    }

    @Test(expected = IllegalStateException::class)
    fun destroyedOwnerIsRejected() {
        LifecycleSession(Owner(Lifecycle.State.DESTROYED).lifecycle, Delegate(), { Delegate() }, { it() })
    }
}
