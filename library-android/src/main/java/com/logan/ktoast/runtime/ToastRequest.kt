package com.logan.ktoast.runtime

import com.logan.ktoast.config.KToastConfig

internal data class ToastRequest(
    val message: CharSequence,
    val duration: Int,
    val config: KToastConfig? = null,
    val behavior: KToastBehavior = KToastBehavior(),
    val callbacks: KToastCallbacks = KToastCallbacks(),
    val handle: ToastHandle = ToastHandle()
) {
    val dedupeKey: String
        get() = "${behavior.groupKey.orEmpty()}::${message}"
}
