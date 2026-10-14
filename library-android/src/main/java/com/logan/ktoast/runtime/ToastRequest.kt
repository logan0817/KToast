package com.logan.ktoast.runtime

import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.KToastScope

internal data class ToastRequest(
    val message: CharSequence,
    val duration: Int,
    var config: KToastConfig? = null,
    val behavior: KToastBehavior = KToastBehavior(),
    val callbacks: KToastCallbacks = KToastCallbacks(),
    val handle: ToastHandle = ToastHandle(),
    val scope: KToastScope? = null,
    val admission: ToastAdmission.Ticket? = null,
    var eligibleAt: Long? = 0L,
    val cleanup: (() -> Unit)? = null
) {
    val expiresAt: Long?
        get() = admission?.policy?.maxQueueWaitMillis?.let { wait ->
            eligibleAt?.let { ToastTime.add(it, wait) }
        }

    val dedupeKey: Triple<Long?, String, String>
        get() = Triple(scope?.id, behavior.groupKey.orEmpty(), message.toString())
}
