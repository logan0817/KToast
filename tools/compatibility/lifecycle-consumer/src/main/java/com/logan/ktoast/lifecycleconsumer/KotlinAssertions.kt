package com.logan.ktoast.lifecycleconsumer

import com.logan.ktoast.KToast
import com.logan.ktoast.lifecycle.KToastLifecycleScope
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.runtime.ToastHandle

fun verifyKotlinConsumer(scope: KToastLifecycleScope) {
    KToast.debugMode = true
    check(KToast.debugMode)
    KToast.debugMode = false
    check(!KToast.debugMode)
    val active = scope.show("Kotlin", localConfig = { animationDuration = 0L })
    val queued = scope.show("queue", behavior = KToastBehavior(KToastDisplayMode.QUEUE))
    check(active.state == ToastHandle.State.SHOWING)
    check(queued.state == ToastHandle.State.PENDING)
    check(active.cancel())
    check(queued.state == ToastHandle.State.SHOWING)
    val delayed = scope.showDelayed("delayed", 60_000L)
    check(delayed.cancel() && delayed.isCanceled)
    scope.cancel()
    check(queued.isCanceled)
}
