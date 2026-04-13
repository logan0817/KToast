package com.logan.ktoast.runtime

import java.util.ArrayDeque

internal class ToastDispatcher(
    private val runtime: Runtime,
    private val scheduler: Scheduler,
    private val nowProvider: () -> Long
) {

    fun interface Cancellable {
        fun cancel()
    }

    interface Scheduler {
        fun schedule(delayMillis: Long, action: () -> Unit): Cancellable
    }

    interface Runtime {
        fun show(request: ToastRequest)
        fun cancelActive(reason: ToastHandle.DismissReason)
    }

    private val queue = ArrayDeque<ToastRequest>()
    private val acceptedAt = mutableMapOf<String, Long>()

    private var activeRequest: ToastRequest? = null
    private var debouncedRequest: ToastRequest? = null
    private var debounceTask: Cancellable? = null
    private var pausePromotion = false

    fun submit(request: ToastRequest): ToastHandle {
        request.handle.attachCancelDelegate {
            cancelHandle(request.handle, ToastHandle.DismissReason.CANCELED, notifyRuntime = true)
        }

        return when (request.behavior.displayMode) {
            KToastDisplayMode.REPLACE -> {
                replaceWith(request)
                request.handle
            }

            KToastDisplayMode.QUEUE -> {
                enqueueOrShow(request)
                request.handle
            }

            KToastDisplayMode.IGNORE_IF_SAME -> {
                if (shouldIgnore(request)) {
                    finishRequest(request, ToastHandle.DismissReason.IGNORED)
                } else {
                    acceptedAt[request.dedupeKey] = nowProvider()
                    enqueueOrShow(request)
                }
                request.handle
            }

            KToastDisplayMode.DEBOUNCE -> {
                scheduleDebounced(request)
                request.handle
            }
        }
    }

    fun onFallbackToSystem(handle: ToastHandle) {
        findRequest(handle)?.callbacks?.onFallbackToSystem?.invoke(handle)
    }

    fun onPresentationFinished(
        handle: ToastHandle,
        reason: ToastHandle.DismissReason
    ) {
        val request = activeRequest?.takeIf { it.handle.id == handle.id } ?: return
        activeRequest = null
        finishRequest(request, reason)
        if (!pausePromotion) {
            promoteNext()
        }
    }

    fun cancelAll() {
        pausePromotion = true
        try {
            activeRequest?.handle?.let {
                cancelHandle(it, ToastHandle.DismissReason.CANCELED, notifyRuntime = true)
            }
            clearQueuedRequests(ToastHandle.DismissReason.CANCELED)
        } finally {
            pausePromotion = false
        }
    }

    fun cancelGroup(groupKey: String): Boolean {
        var canceled = false
        pausePromotion = true
        try {
            activeRequest?.takeIf { it.behavior.groupKey == groupKey }?.let {
                canceled = cancelHandle(it.handle, ToastHandle.DismissReason.CANCELED, notifyRuntime = true) || canceled
            }

            val queuedMatches = queue.filter { it.behavior.groupKey == groupKey }
            for (request in queuedMatches) {
                queue.remove(request)
                finishRequest(request, ToastHandle.DismissReason.CANCELED)
                canceled = true
            }

            debouncedRequest?.takeIf { it.behavior.groupKey == groupKey }?.let {
                debouncedRequest = null
                debounceTask?.cancel()
                debounceTask = null
                finishRequest(it, ToastHandle.DismissReason.CANCELED)
                canceled = true
            }
        } finally {
            pausePromotion = false
        }
        if (canceled) {
            promoteNext()
        }
        return canceled
    }

    private fun shouldIgnore(request: ToastRequest): Boolean {
        val lastAcceptedAt = acceptedAt[request.dedupeKey] ?: return false
        val withinWindow = nowProvider() - lastAcceptedAt < request.behavior.windowMillis
        if (!withinWindow) {
            acceptedAt.remove(request.dedupeKey)
        }
        return withinWindow
    }

    private fun replaceWith(request: ToastRequest) {
        activeRequest?.let {
            runtime.cancelActive(ToastHandle.DismissReason.REPLACED)
            onPresentationFinished(it.handle, ToastHandle.DismissReason.REPLACED)
        }
        clearQueuedRequests(ToastHandle.DismissReason.REPLACED)
        showNow(request)
    }

    private fun enqueueOrShow(request: ToastRequest) {
        if (activeRequest == null) {
            showNow(request)
        } else {
            queue.addLast(request)
        }
    }

    private fun showNow(request: ToastRequest) {
        activeRequest = request
        request.handle.markShowing()
        runtime.show(request)
        request.callbacks.onShow?.invoke(request.handle)
    }

    private fun scheduleDebounced(request: ToastRequest) {
        debouncedRequest?.let {
            finishRequest(it, ToastHandle.DismissReason.DEBOUNCED)
        }
        debounceTask?.cancel()
        debouncedRequest = request
        debounceTask = scheduler.schedule(request.behavior.windowMillis) {
            val latestRequest = debouncedRequest ?: return@schedule
            debouncedRequest = null
            debounceTask = null
            enqueueOrShow(latestRequest)
        }
    }

    private fun promoteNext() {
        if (activeRequest != null) return
        if (queue.isEmpty()) return
        val next = queue.removeFirst()
        showNow(next)
    }

    private fun clearQueuedRequests(reason: ToastHandle.DismissReason) {
        while (queue.isNotEmpty()) {
            finishRequest(queue.removeFirst(), reason)
        }
        debouncedRequest?.let {
            debouncedRequest = null
            debounceTask?.cancel()
            debounceTask = null
            finishRequest(it, reason)
        }
    }

    private fun cancelHandle(
        handle: ToastHandle,
        reason: ToastHandle.DismissReason,
        notifyRuntime: Boolean
    ): Boolean {
        activeRequest?.takeIf { it.handle.id == handle.id }?.let {
            if (notifyRuntime) {
                runtime.cancelActive(reason)
            }
            onPresentationFinished(handle, reason)
            return true
        }
        val queued = queue.firstOrNull { it.handle.id == handle.id }
        if (queued != null) {
            queue.remove(queued)
            finishRequest(queued, reason)
            return true
        }
        debouncedRequest?.takeIf { it.handle.id == handle.id }?.let {
            debouncedRequest = null
            debounceTask?.cancel()
            debounceTask = null
            finishRequest(it, reason)
            return true
        }
        return false
    }

    private fun finishRequest(
        request: ToastRequest,
        reason: ToastHandle.DismissReason
    ) {
        request.handle.markDismissed(reason)
        if (reason == ToastHandle.DismissReason.CANCELED) {
            request.callbacks.onCancel?.invoke(request.handle)
        }
        request.callbacks.onDismiss?.invoke(request.handle, reason)
    }

    private fun findRequest(handle: ToastHandle): ToastRequest? {
        return activeRequest?.takeIf { it.handle.id == handle.id }
            ?: queue.firstOrNull { it.handle.id == handle.id }
            ?: debouncedRequest?.takeIf { it.handle.id == handle.id }
    }
}
