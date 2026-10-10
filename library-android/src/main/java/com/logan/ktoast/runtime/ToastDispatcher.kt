package com.logan.ktoast.runtime

import java.util.ArrayDeque

internal class ToastDispatcher(
    private val runtime: Runtime,
    private val scheduler: Scheduler,
    private val nowProvider: () -> Long,
    private val onError: (Exception) -> Unit = {}
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
    private val acceptedAt = mutableMapOf<Triple<Long?, String, String>, Long>()
    private val operations = ArrayDeque<() -> Unit>()
    private val callbacks = ArrayDeque<() -> Unit>()
    private var draining = false

    private var activeRequest: ToastRequest? = null
    private val debouncedRequests = mutableMapOf<Long?, ToastRequest>()
    private val debounceTasks = mutableMapOf<Long?, Cancellable>()

    // 仅安装句柄委托，可在提交到主线程前调用；所有调度状态仍由 execute 切回主线程修改。
    fun prepare(request: ToastRequest, execute: (() -> Unit) -> Unit = { it() }) {
        request.handle.attachCancelDelegate {
            execute { cancelPreparedRequest(request) }
            true
        }
    }

    fun submit(request: ToastRequest): ToastHandle {
        prepare(request)
        dispatch {
            if (request.handle.isFinished) return@dispatch
            if (request.handle.isCancellationRequested || request.scope?.canShow() == false) {
                finishRequest(request, ToastHandle.DismissReason.CANCELED)
                return@dispatch
            }
            when (request.behavior.displayMode) {
                KToastDisplayMode.REPLACE -> replaceWith(request)
                KToastDisplayMode.QUEUE -> enqueueOrShow(request)
                KToastDisplayMode.IGNORE_IF_SAME -> {
                    if (shouldIgnore(request)) {
                        finishRequest(request, ToastHandle.DismissReason.IGNORED)
                    } else {
                        acceptedAt[request.dedupeKey] = nowProvider()
                        enqueueOrShow(request)
                    }
                }
                KToastDisplayMode.DEBOUNCE -> scheduleDebounced(request)
            }
        }
        return request.handle
    }

    fun onFallbackToSystem(handle: ToastHandle) {
        val request = findRequest(handle) ?: return
        request.callbacks.onFallbackToSystem?.let { callback ->
            callbacks.addLast { callback(handle) }
        }
        dispatch {}
    }

    fun onPresentationFinished(handle: ToastHandle, reason: ToastHandle.DismissReason) {
        dispatch {
            val request = activeRequest?.takeIf { it.handle.id == handle.id } ?: return@dispatch
            activeRequest = null
            finishRequest(request, reason)
            promoteNext()
        }
    }

    fun onPresentationError(handle: ToastHandle, error: Exception) {
        reportError(error)
        val request = findRequest(handle) ?: return
        diagnostic(request, KToastDiagnosticEvent.ERROR, error.javaClass.name)
        dispatch {}
    }

    fun cancelAll(pendingSubmissions: List<ToastRequest> = emptyList()) {
        dispatch {
            val active = activeRequest
            activeRequest = null
            val pending = takeQueuedRequests() + pendingSubmissions
            if (active != null) {
                cancelRuntime(ToastHandle.DismissReason.CANCELED)
                finishRequest(active, ToastHandle.DismissReason.CANCELED)
            }
            pending.forEach { finishRequest(it, ToastHandle.DismissReason.CANCELED) }
        }
    }

    fun cancelGroup(groupKey: String, pendingSubmissions: List<ToastRequest> = emptyList()): Boolean {
        var canceled = false
        dispatch {
            val matches = queue.filter { it.behavior.groupKey == groupKey }
            matches.forEach { queue.remove(it) }
            matches.forEach { finishRequest(it, ToastHandle.DismissReason.CANCELED) }
            pendingSubmissions.filter { it.behavior.groupKey == groupKey }.forEach {
                finishRequest(it, ToastHandle.DismissReason.CANCELED)
                canceled = true
            }
            debouncedRequests.values.filter { it.behavior.groupKey == groupKey }.forEach {
                clearDebounce(it.scope?.id)
                finishRequest(it, ToastHandle.DismissReason.CANCELED)
                canceled = true
            }
            activeRequest?.takeIf { it.behavior.groupKey == groupKey }?.let {
                activeRequest = null
                cancelRuntime(ToastHandle.DismissReason.CANCELED)
                finishRequest(it, ToastHandle.DismissReason.CANCELED)
                canceled = true
            }
            canceled = canceled || matches.isNotEmpty()
            promoteNext()
        }
        return canceled
    }

    fun cancelScope(scopeId: Long, pendingSubmissions: List<ToastRequest> = emptyList()) {
        dispatch {
            val matches = queue.filter { it.scope?.id == scopeId }
            matches.forEach { queue.remove(it) }
            val debounced = debouncedRequests[scopeId]
            clearDebounce(scopeId)
            val active = activeRequest?.takeIf { it.scope?.id == scopeId }
            if (active != null) {
                activeRequest = null
                cancelRuntime(ToastHandle.DismissReason.CANCELED)
                finishRequest(active, ToastHandle.DismissReason.CANCELED)
            }
            (matches + listOfNotNull(debounced) + pendingSubmissions).forEach {
                finishRequest(it, ToastHandle.DismissReason.CANCELED)
            }
            // 页面生命周期结束后释放该页面历史，避免 scope ID 的去重记录持续累积。
            acceptedAt.keys.removeAll { it.first == scopeId }
            promoteNext()
        }
    }

    fun cancelPreparedRequest(request: ToastRequest) {
        dispatch {
            if (request.handle.isFinished) return@dispatch
            when {
                activeRequest?.handle?.id == request.handle.id -> {
                    activeRequest = null
                    cancelRuntime(ToastHandle.DismissReason.CANCELED)
                }
                debouncedRequests[request.scope?.id]?.handle?.id == request.handle.id ->
                    clearDebounce(request.scope?.id)
                else -> queue.remove(request)
            }
            finishRequest(request, ToastHandle.DismissReason.CANCELED)
            promoteNext()
        }
    }

    private fun shouldIgnore(request: ToastRequest): Boolean {
        val lastAcceptedAt = acceptedAt[request.dedupeKey] ?: return false
        val withinWindow = nowProvider() - lastAcceptedAt < request.behavior.windowMillis
        if (!withinWindow) acceptedAt.remove(request.dedupeKey)
        return withinWindow
    }

    private fun replaceWith(request: ToastRequest) {
        if (request.scope != null) {
            val scopeId = request.scope.id
            val matches = queue.filter { it.scope?.id == scopeId }
            matches.forEach { queue.remove(it) }
            val debounced = debouncedRequests[scopeId]
            clearDebounce(scopeId)
            val active = activeRequest?.takeIf { it.scope?.id == scopeId }
            if (active != null) {
                activeRequest = null
                cancelRuntime(ToastHandle.DismissReason.REPLACED)
                finishRequest(active, ToastHandle.DismissReason.REPLACED)
            }
            (matches + listOfNotNull(debounced)).forEach {
                finishRequest(it, ToastHandle.DismissReason.REPLACED)
            }
            enqueueOrShow(request)
            return
        }
        val active = activeRequest
        activeRequest = null
        val pending = takeQueuedRequests()
        if (active != null) {
            cancelRuntime(ToastHandle.DismissReason.REPLACED)
            finishRequest(active, ToastHandle.DismissReason.REPLACED)
        }
        pending.forEach { finishRequest(it, ToastHandle.DismissReason.REPLACED) }
        showNow(request)
    }

    private fun enqueueOrShow(request: ToastRequest) {
        if (activeRequest == null) showNow(request) else queue.addLast(request)
    }

    private fun showNow(request: ToastRequest) {
        if (request.handle.isFinished || request.handle.isCancellationRequested || request.scope?.canShow() == false) {
            finishRequest(request, ToastHandle.DismissReason.CANCELED)
            dispatch { promoteNext() }
            return
        }
        activeRequest = request
        request.handle.markShowing()
        try {
            runtime.show(request)
            request.callbacks.onShow?.let { callback ->
                callbacks.addLast { callback(request.handle) }
            }
            diagnostic(request, KToastDiagnosticEvent.SHOWN)
        } catch (error: Exception) {
            reportError(error)
            diagnostic(request, KToastDiagnosticEvent.ERROR, error.javaClass.name)
            activeRequest = null
            cancelRuntime(ToastHandle.DismissReason.CANCELED)
            // 保留既有枚举；渲染失败以 CANCELED 收尾，调试日志提供具体原因。
            finishRequest(request, ToastHandle.DismissReason.CANCELED)
            dispatch { promoteNext() }
        }
    }

    private fun scheduleDebounced(request: ToastRequest) {
        val scopeId = request.scope?.id
        val previous = debouncedRequests[scopeId]
        clearDebounce(scopeId)
        previous?.let { finishRequest(it, ToastHandle.DismissReason.DEBOUNCED) }
        debouncedRequests[scopeId] = request
        try {
            debounceTasks[scopeId] = scheduler.schedule(request.behavior.windowMillis) {
                dispatch {
                    // 取消后的旧定时回调不能消费后续请求。
                    if (debouncedRequests[scopeId] !== request) return@dispatch
                    debouncedRequests.remove(scopeId)
                    debounceTasks.remove(scopeId)
                    enqueueOrShow(request)
                }
            }
        } catch (error: Exception) {
            reportError(error)
            diagnostic(request, KToastDiagnosticEvent.ERROR, error.javaClass.name)
            clearDebounce(scopeId)
            finishRequest(request, ToastHandle.DismissReason.CANCELED)
        }
    }

    private fun promoteNext() {
        if (activeRequest == null && queue.isNotEmpty()) showNow(queue.removeFirst())
    }

    private fun takeQueuedRequests(): List<ToastRequest> {
        val pending = queue.toMutableList()
        queue.clear()
        pending.addAll(debouncedRequests.values)
        debouncedRequests.keys.toList().forEach { clearDebounce(it) }
        return pending
    }

    private fun clearDebounce(scopeId: Long?) {
        debouncedRequests.remove(scopeId)
        val task = debounceTasks.remove(scopeId)
        try {
            task?.cancel()
        } catch (error: Exception) {
            reportError(error)
        }
    }

    private fun cancelRuntime(reason: ToastHandle.DismissReason) {
        try {
            runtime.cancelActive(reason)
        } catch (error: Exception) {
            reportError(error)
        }
    }

    private fun finishRequest(request: ToastRequest, reason: ToastHandle.DismissReason) {
        if (!request.handle.markDismissed(reason)) return
        if (reason == ToastHandle.DismissReason.CANCELED) {
            request.callbacks.onCancel?.let { callback ->
                callbacks.addLast { callback(request.handle) }
            }
        }
        request.callbacks.onDismiss?.let { callback ->
            callbacks.addLast { callback(request.handle, reason) }
        }
        diagnostic(request, KToastDiagnosticEvent.DISMISSED, reason.name)
    }

    private fun diagnostic(request: ToastRequest, event: KToastDiagnosticEvent, detail: String? = null) {
        request.scope?.options?.diagnostics?.let { listener ->
            callbacks.addLast { listener.onEvent(request.handle, event, detail) }
        }
    }

    private fun findRequest(handle: ToastHandle): ToastRequest? =
        activeRequest?.takeIf { it.handle.id == handle.id }
            ?: queue.firstOrNull { it.handle.id == handle.id }
            ?: debouncedRequests.values.firstOrNull { it.handle.id == handle.id }

    // 先完成状态转换，再发送回调；回调重入与渲染器同步通知作为后续操作串行处理。
    private fun dispatch(operation: () -> Unit) {
        operations.addLast(operation)
        if (draining) return
        draining = true
        try {
            while (operations.isNotEmpty() || callbacks.isNotEmpty()) {
                while (operations.isNotEmpty()) operations.removeFirst().invoke()
                if (callbacks.isNotEmpty()) {
                    try {
                        callbacks.removeFirst().invoke()
                    } catch (error: Exception) {
                        reportError(error)
                    }
                }
            }
        } finally {
            draining = false
        }
    }

    private fun reportError(error: Exception) {
        try {
            onError(error)
        } catch (_: Exception) {
            // 诊断处理失败也不能阻断请求收尾和队列推进。
        }
    }
}
