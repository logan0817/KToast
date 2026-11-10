package compatibility

import com.logan.ktoast.runtime.KToastRuntimePolicy
import com.logan.ktoast.runtime.ToastDropReason

fun main() {
    val policy = KToastRuntimePolicy(maxPendingRequests = 10, maxQueueWaitMillis = 1000L, maxDedupeEntries = 20)
    val copy = policy.copy(maxPendingRequests = 5)
    val (capacity, wait, history) = copy
    check(capacity == 5 && wait == 1000L && history == 20)
    check(copy == KToastRuntimePolicy(5, 1000L, 20))
    check(ToastDropReason.entries.toList() == listOf(ToastDropReason.CAPACITY_EXCEEDED, ToastDropReason.EXPIRED))
    println("PASS: Kotlin consumer compiled against 1.2.0")
}
