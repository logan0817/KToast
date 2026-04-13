package com.logan.ktoast.runtime

import java.util.concurrent.atomic.AtomicLong

class ToastHandle internal constructor(
    val id: Long = nextId.incrementAndGet()
) {

    enum class State {
        PENDING,
        SHOWING,
        DISMISSED,
        CANCELED
    }

    enum class DismissReason {
        COMPLETED,
        CANCELED,
        REPLACED,
        IGNORED,
        DEBOUNCED
    }

    var state: State = State.PENDING
        internal set

    var dismissReason: DismissReason? = null
        internal set

    val isFinished: Boolean
        get() = state == State.DISMISSED || state == State.CANCELED

    val isCanceled: Boolean
        get() = state == State.CANCELED

    private var cancelDelegate: (() -> Boolean)? = null

    fun cancel(): Boolean = cancelDelegate?.invoke() ?: false

    internal fun attachCancelDelegate(delegate: () -> Boolean) {
        cancelDelegate = delegate
    }

    internal fun markShowing() {
        if (!isFinished) {
            state = State.SHOWING
        }
    }

    internal fun markDismissed(reason: DismissReason) {
        if (isFinished) return
        dismissReason = reason
        state = if (reason == DismissReason.CANCELED) State.CANCELED else State.DISMISSED
    }

    companion object {
        private val nextId = AtomicLong(0L)
    }
}
