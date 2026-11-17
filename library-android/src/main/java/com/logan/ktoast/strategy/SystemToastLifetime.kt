package com.logan.ktoast.strategy

import com.logan.ktoast.runtime.ToastDispatcher
import com.logan.ktoast.runtime.ToastHandle

/** 系统回调决定真实终态；兜底先撤下原生提示，避免句柄结束后仍有不可取消的窗口。 */
internal class SystemToastLifetime(
    private val scheduler: ToastDispatcher.Scheduler,
    private val displayMillis: Long,
    private val hasPlatformCallbacks: Boolean,
    private val cancelToast: () -> Unit,
    private val onDismiss: (ToastHandle.DismissReason) -> Unit
) {
    private var timer: ToastDispatcher.Cancellable? = null
    private var generation = 0L
    private var finished = false
    private var shown = false

    fun start() {
        // 系统队列可能延迟展示；没有回调的旧平台只能按推荐时长结束。
        schedule(displayMillis + if (hasPlatformCallbacks) 30_000L else 0L)
    }

    fun onShown() {
        if (finished || shown) return
        shown = true
        // 推荐时长不含系统入场/退场动画，留出余量后才视作回调丢失。
        schedule(displayMillis + 5_000L)
    }

    fun onHidden() = finish(ToastHandle.DismissReason.COMPLETED, cancelPlatform = false)

    fun cancel(reason: ToastHandle.DismissReason) = finish(reason, cancelPlatform = true)

    private fun schedule(delayMillis: Long) {
        val expectedGeneration = ++generation
        timer?.cancel()
        timer = scheduler.schedule(delayMillis) {
            if (!finished && generation == expectedGeneration) {
                val reason = if (hasPlatformCallbacks) ToastHandle.DismissReason.CANCELED
                    else ToastHandle.DismissReason.COMPLETED
                finish(reason, cancelPlatform = true)
            }
        }
    }

    private fun finish(reason: ToastHandle.DismissReason, cancelPlatform: Boolean) {
        if (finished) return
        finished = true
        generation++
        timer?.cancel()
        timer = null
        try {
            if (cancelPlatform) cancelToast()
        } finally {
            onDismiss(reason)
        }
    }
}
