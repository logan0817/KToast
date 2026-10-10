package com.logan.ktoast.runtime

import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.KToastScope

internal data class ToastRequest(
    val message: CharSequence,
    val duration: Int,
    val config: KToastConfig? = null,
    val behavior: KToastBehavior = KToastBehavior(),
    val callbacks: KToastCallbacks = KToastCallbacks(),
    val handle: ToastHandle = ToastHandle(),
    val scope: KToastScope? = null
) {
    val dedupeKey: Triple<Long?, String, String>
        get() = Triple(scope?.id, behavior.groupKey.orEmpty(), message.toString())
}
