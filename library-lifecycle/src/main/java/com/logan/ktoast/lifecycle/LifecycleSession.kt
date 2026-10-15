package com.logan.ktoast.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** 每轮启动更换代理，已捕获的旧代理只能关闭，不能重新启用。 */
internal class LifecycleSession<T : AutoCloseable>(
    private val lifecycle: Lifecycle,
    private val stopped: T,
    private val factory: () -> T?,
    private val onMain: (() -> Unit) -> Unit,
    private val onClosed: () -> Unit = {}
) : DefaultLifecycleObserver, AutoCloseable {
    private val lock = Any()
    private var delegate = stopped
    private var generation = 0L
    @Volatile var isClosed = false
        private set

    init {
        check(lifecycle.currentState != Lifecycle.State.DESTROYED) { "Cannot bind a destroyed LifecycleOwner." }
        lifecycle.addObserver(this)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
    }

    fun current(): T = synchronized(lock) { if (isClosed) stopped else delegate }

    override fun onStart(owner: LifecycleOwner) = start()

    private fun start() {
        val expectedGeneration = synchronized(lock) {
            if (isClosed || delegate !== stopped) return
            generation
        }
        val next = factory() ?: run { close(); return }
        val discard = synchronized(lock) {
            if (isClosed || generation != expectedGeneration || delegate !== stopped) true
            else { delegate = next; false }
        }
        if (discard) next.close()
    }

    override fun onStop(owner: LifecycleOwner) {
        val previous = synchronized(lock) {
            generation++
            delegate.also { delegate = stopped }
        }
        previous.close()
    }

    override fun onDestroy(owner: LifecycleOwner) = close()

    override fun close() {
        val previous = synchronized(lock) {
            if (isClosed) return
            isClosed = true
            delegate.also { delegate = stopped }
        }
        try {
            previous.close()
        } finally {
            onMain {
                try { lifecycle.removeObserver(this) } finally { onClosed() }
            }
        }
    }
}
