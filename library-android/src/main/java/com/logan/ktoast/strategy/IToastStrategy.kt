package com.logan.ktoast.strategy

import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoast.runtime.ToastRequest

internal interface IToastStrategy {
    fun show(request: ToastRequest, listener: ToastPresentationListener)
    fun cancel(reason: ToastHandle.DismissReason = ToastHandle.DismissReason.CANCELED)
}

internal interface ToastPresentationListener {
    fun onFallbackToSystem(request: ToastRequest)
    fun onDismiss(handle: ToastHandle, reason: ToastHandle.DismissReason)
}
