package com.logan.ktoast.runtime

import java.util.ArrayDeque

internal class ToastDispatcher(
    private val runtime: Runtime,
    private val scheduler: Scheduler,
    private val nowProvider: () -> Long,
    private val onError: (Exception) -> Unit = {},
    private val policyProvider: () -> KToastRuntimePolicy = { KToastRuntimePolicy() }
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
    private val acceptedAt = LinkedHashMap<Triple<Long?, String, String>, Long>(16, 0.75f, true)
    private val waitingRequests = mutableMapOf<Long, ToastRequest>()
    private var expiryTask: Cancellable? = null
    private var expiryDueAt: Long? = null
    private var expiryGeneration = 0L
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
            if (!acceptWaiting(request)) return@dispatch
            when (request.behavior.displayMode) {
                KToastDisplayMode.REPLACE -> replaceWith(request)
                KToastDisplayMode.QUEUE -> enqueueOrShow(request)
                KToastDisplayMode.IGNORE_IF_SAME -> {
                    if (shouldIgnore(request)) {
                        finishRequest(request, ToastHandle.DismissReason.IGNORED)
                    } else {
                        acceptedAt[request.dedupeKey] = nowProvider()
                        policyProvider().maxDedupeEntries?.let { limit ->
                            while (acceptedAt.size > limit) acceptedAt.remove(acceptedAt.keys.first())
                        }
                        enqueueOrShow(request)
                    }
                }
                KToastDisplayMode.DEBOUNCE -> scheduleDebounced(request)
            }
        }
        return request.handle
    }

    /** 延时请求也登记在同一过期集合中，不等待入队才开始清理。 */
    fun watch(request: ToastRequest) {
        dispatch { acceptWaiting(request) }
    }

    fun reject(request: ToastRequest, reason: ToastDropReason) {
        dispatch {
            finishRequest(request, ToastHandle.DismissReason.CANCELED,
                reason.takeUnless { request.handle.isCancellationRequested })
        }
    }

    internal fun dedupeEntryCount(): Int = acceptedAt.size

    private fun acceptWaiting(request: ToastRequest): Boolean {
        if (request.handle.isFinished) return false
        if (request.handle.isCancellationRequested || request.scope?.canShow() == false) {
            finishRequest(request, ToastHandle.DismissReason.CANCELED)
            return false
        }
        if (request.expiresAt?.let { nowProvider() >= it } == true) {
            dropWaiting(request)
            return false
        }
        waitingRequests[request.handle.id] = request
        return true
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
            val pending = (takeQueuedRequests() + waitingRequests.values + pendingSubmissions)
                .distinctBy { it.handle.id }
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
            val matches = (queue + waitingRequests.values).filter { it.behavior.groupKey == groupKey }
                .distinctBy { it.handle.id }
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
            val matches = (queue + waitingRequests.values).filter { it.scope?.id == scopeId }
                .distinctBy { it.handle.id }
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
        if (request.expiresAt?.let { nowProvider() >= it } == true) {
            dropWaiting(request)
            dispatch { promoteNext() }
            return
        }
        waitingRequests.remove(request.handle.id)
        cleanupRequest(request)
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
        request.eligibleAt = ToastTime.add(nowProvider(), request.behavior.windowMillis)
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

    private fun finishRequest(request: ToastRequest, reason: ToastHandle.DismissReason, drop: ToastDropReason? = null) {
        if (!request.handle.markDismissed(reason, drop)) return
        waitingRequests.remove(request.handle.id)
        cleanupRequest(request)
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

    private fun cleanupRequest(request: ToastRequest) {
        try {
            request.cleanup?.invoke()
        } catch (error: Exception) {
            reportError(error)
        } finally {
            request.admission?.release()
        }
    }

    private fun dropWaiting(request: ToastRequest) {
        if (debouncedRequests[request.scope?.id] === request) clearDebounce(request.scope?.id)
        queue.remove(request)
        finishRequest(request, ToastHandle.DismissReason.CANCELED, ToastDropReason.EXPIRED)
    }

    private fun refreshExpiry() {
        val next = waitingRequests.values.mapNotNull { it.expiresAt }.minOrNull()
        if (next == expiryDueAt) return
        expiryGeneration++
        val generation = expiryGeneration
        try {
            expiryTask?.cancel()
        } catch (error: Exception) {
            reportError(error)
        }
        expiryTask = null
        expiryDueAt = next
        if (next == null) return
        try {
            expiryTask = scheduler.schedule((next - nowProvider()).coerceAtLeast(0L)) {
                dispatch {
                    if (generation != expiryGeneration) return@dispatch
                    expiryTask = null
                    expiryDueAt = null
                    val now = nowProvider()
                    waitingRequests.values.filter { it.expiresAt?.let { deadline -> now >= deadline } == true }
                        .toList().forEach { dropWaiting(it) }
                    promoteNext()
                }
            }
        } catch (error: Exception) {
            reportError(error)
            expiryDueAt = null
            // 无法保证超时清理时终止这些等待项，不能让额度和队列无限滞留。
            waitingRequests.values.filter { it.expiresAt != null }.toList().forEach {
                if (debouncedRequests[it.scope?.id] === it) clearDebounce(it.scope?.id)
                queue.remove(it)
                diagnostic(it, KToastDiagnosticEvent.ERROR, error.javaClass.name)
                finishRequest(it, ToastHandle.DismissReason.CANCELED)
            }
        }
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
                refreshExpiry()
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
