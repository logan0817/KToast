package com.logan.ktoast.runtime

/** 配置冻结与额度预留使用同一把锁；锁内不执行业务配置、回调或窗口调用。 */
internal class ToastAdmission {
    private var configuredPolicy = KToastRuntimePolicy()
    private var frozen = false
    private var pending = 0

    @Synchronized
    fun configure(policy: KToastRuntimePolicy) {
        check(!frozen) { "Runtime policy must be configured before the first request or delayed task." }
        configuredPolicy = policy
    }

    @Synchronized
    fun policy(): KToastRuntimePolicy = configuredPolicy

    @Synchronized
    fun pendingCount(): Int = pending

    @Synchronized
    fun acquire(): Ticket? {
        frozen = true
        if (configuredPolicy.maxPendingRequests?.let { pending >= it } == true) return null
        pending++
        return Ticket(configuredPolicy) { synchronized(this) { pending-- } }
    }

    internal class Ticket(val policy: KToastRuntimePolicy, private val returnSlot: () -> Unit) {
        private var preparing = 1
        private var releaseRequested = false
        private var returned = false

        @Synchronized
        fun beginPreparation() {
            check(!returned && !releaseRequested) { "Cannot reuse a released admission ticket." }
            preparing++
        }

        @Synchronized
        fun endPreparation() {
            check(preparing > 0)
            preparing--
            returnIfReady()
        }

        @Synchronized
        fun release() {
            releaseRequested = true
            returnIfReady()
        }

        private fun returnIfReady() {
            // 已取消的 DSL 仍在执行时保留额度，退出准备后才允许下一请求复用。
            if (releaseRequested && preparing == 0 && !returned) {
                returned = true
                returnSlot()
            }
        }
    }
}

internal object ToastTime {
    fun add(time: Long, delayMillis: Long): Long =
        time + delayMillis.coerceIn(0L, Long.MAX_VALUE - time)
}
