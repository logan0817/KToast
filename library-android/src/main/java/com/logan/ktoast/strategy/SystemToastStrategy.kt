package com.logan.ktoast.strategy

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.logan.ktoast.KToast
import com.logan.ktoast.runtime.ToastDispatcher
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoast.runtime.ToastRequest

internal class SystemToastStrategy : IToastStrategy {
    private var lifetime: SystemToastLifetime? = null
    private var removePlatformCallback: (() -> Unit)? = null

    override fun show(request: ToastRequest, listener: ToastPresentationListener) {
        val config = request.config ?: KToast.globalConfig.snapshot()
        val nativeToast = Toast.makeText(KToast.context, request.message, request.duration).apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                setGravity(config.gravity, config.xOffset, config.yOffset)
            }
        }
        val baseTime = if (request.duration == Toast.LENGTH_LONG) 3500 else 2000
        val displayTime = if (Build.VERSION.SDK_INT >= 29) {
            val manager = KToast.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getRecommendedTimeoutMillis(baseTime, AccessibilityManager.FLAG_CONTENT_TEXT).coerceAtLeast(baseTime)
        } else baseTime
        val session = SystemToastLifetime(
            scheduler = object : ToastDispatcher.Scheduler {
                override fun schedule(delayMillis: Long, action: () -> Unit): ToastDispatcher.Cancellable {
                    val task = Runnable(action)
                    KToast.mainHandler.postDelayed(task, delayMillis)
                    return ToastDispatcher.Cancellable { KToast.mainHandler.removeCallbacks(task) }
                }
            },
            displayMillis = displayTime.toLong(),
            hasPlatformCallbacks = Build.VERSION.SDK_INT >= 30,
            cancelToast = {
                try {
                    nativeToast.cancel()
                } catch (_: Exception) {
                    // 厂商取消异常不能阻断库内终态和队列推进。
                }
            },
            onDismiss = { reason ->
                try {
                    removePlatformCallback?.invoke()
                } finally {
                    removePlatformCallback = null
                    lifetime = null
                    listener.onDismiss(request.handle, reason)
                }
            }
        )
        lifetime = session
        if (Build.VERSION.SDK_INT >= 30) {
            val callback = object : Toast.Callback() {
                override fun onToastShown() = session.onShown()
                override fun onToastHidden() = session.onHidden()
            }
            nativeToast.addCallback(callback)
            removePlatformCallback = { nativeToast.removeCallback(callback) }
        }
        session.start()
        nativeToast.show()
    }

    override fun cancel(reason: ToastHandle.DismissReason) {
        lifetime?.cancel(reason)
    }
}
