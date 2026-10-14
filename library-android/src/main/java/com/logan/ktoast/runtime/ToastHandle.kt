package com.logan.ktoast.runtime

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

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

    @Volatile
    var state: State = State.PENDING
        internal set

    @Volatile
    var dismissReason: DismissReason? = null
        internal set

    /** 容量拒绝或等待过期的附加原因，普通结束为 null；在终态回调前可见。 */
    @Volatile
    var dropReason: ToastDropReason? = null
        private set

    val isFinished: Boolean
        get() = state == State.DISMISSED || state == State.CANCELED

    val isCanceled: Boolean
        get() = state == State.CANCELED

    @Volatile
    private var cancelDelegate: (() -> Boolean)? = null
    private val cancellationRequested = AtomicBoolean(false)

    internal val isCancellationRequested: Boolean
        get() = cancellationRequested.get()

    /**
     * 接受一次取消请求。返回 true 表示已接受，工作线程调用的实际清理和回调在主线程执行。
     * 已结束或已请求取消时返回 false；可通过 state 与 dismissReason 观察最终结果。
     */
    fun cancel(): Boolean {
        val delegate = cancelDelegate ?: return false
        if (isFinished || !cancellationRequested.compareAndSet(false, true)) return false
        return delegate()
    }

    @Synchronized
    internal fun attachCancelDelegate(delegate: () -> Boolean) {
        if (!isFinished && cancelDelegate == null) cancelDelegate = delegate
    }

    internal fun markShowing() {
        if (!isFinished) {
            state = State.SHOWING
        }
    }

    @Synchronized
    internal fun markDismissed(reason: DismissReason, drop: ToastDropReason? = null): Boolean {
        if (isFinished) return false
        dropReason = drop
        dismissReason = reason
        state = if (reason == DismissReason.CANCELED) State.CANCELED else State.DISMISSED
        cancelDelegate = null
        return true
    }

    companion object {
        private val nextId = AtomicLong(0L)
    }
}
