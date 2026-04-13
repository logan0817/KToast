package com.logan.ktoast.runtime

data class KToastCallbacks(
    var onShow: ((ToastHandle) -> Unit)? = null,
    var onDismiss: ((ToastHandle, ToastHandle.DismissReason) -> Unit)? = null,
    var onCancel: ((ToastHandle) -> Unit)? = null,
    var onFallbackToSystem: ((ToastHandle) -> Unit)? = null
)
