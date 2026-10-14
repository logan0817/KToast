package com.logan.ktoast.runtime

/**
 * 可选的全库运行限制。null 保留原规则；正数启用对应限制。
 * 在初始化完成、首个请求或延时任务登记前通过 KToast.setRuntimePolicy 设置。
 */
data class KToastRuntimePolicy @JvmOverloads constructor(
    val maxPendingRequests: Int? = null,
    val maxQueueWaitMillis: Long? = null,
    val maxDedupeEntries: Int? = null
) {
    init {
        require(maxPendingRequests == null || maxPendingRequests > 0) { "maxPendingRequests must be positive." }
        require(maxQueueWaitMillis == null || maxQueueWaitMillis > 0L) { "maxQueueWaitMillis must be positive." }
        require(maxDedupeEntries == null || maxDedupeEntries > 0) { "maxDedupeEntries must be positive." }
    }
}
