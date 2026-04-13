package com.logan.ktoast.strategy

import android.os.Build
import android.widget.Toast
import com.logan.ktoast.KToast
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoast.runtime.ToastRequest

internal class SystemToastStrategy : IToastStrategy {
    private var toast: Toast? = null
    private var dismissRunnable: Runnable? = null
    private var currentRequest: ToastRequest? = null
    private var listener: ToastPresentationListener? = null
    private var dismissNotified = false

    override fun show(request: ToastRequest, listener: ToastPresentationListener) {
        val config = request.config ?: KToast.globalConfig.copy()
        currentRequest = request
        this.listener = listener
        dismissNotified = false

        toast = Toast.makeText(KToast.context, request.message, request.duration).apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                setGravity(config.gravity, config.xOffset, config.yOffset)
            }
            show()
        }

        val displayTime = if (request.duration == Toast.LENGTH_LONG) 3500L else 2000L
        dismissRunnable = Runnable {
            notifyDismiss(ToastHandle.DismissReason.COMPLETED)
        }
        KToast.mainHandler.postDelayed(dismissRunnable!!, displayTime)
    }

    override fun cancel(reason: ToastHandle.DismissReason) {
        dismissRunnable?.let { KToast.mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        try {
            toast?.cancel()
        } catch (e: Exception) {
            // 忽略某些国产 ROM 的异常
        }
        toast = null
        notifyDismiss(reason)
    }

    private fun notifyDismiss(reason: ToastHandle.DismissReason) {
        if (dismissNotified) return
        val handle = currentRequest?.handle ?: return
        dismissNotified = true
        currentRequest = null
        val currentListener = listener
        listener = null
        currentListener?.onDismiss(handle, reason)
    }
}
