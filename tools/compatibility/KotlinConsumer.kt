package compatibility

import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.runtime.ToastHandle

/** 验证已编译 Kotlin 默认参数、命名参数、copy 与析构调用。 */
fun main() {
    val config = KToastConfig(textSize = 16f, maxLines = 3)
    val overridden = config.copy(backgroundRadius = 12f)
    check(overridden.textSize == 16f && overridden.maxLines == 3)
    check(overridden.backgroundRadius == 12f)
    val (textColor, backgroundColor) = overridden
    check(textColor == config.textColor && backgroundColor == config.backgroundColor)

    val behavior = KToastBehavior(groupKey = "upload")
    val copied = behavior.copy(displayMode = KToastDisplayMode.QUEUE)
    check(copied.groupKey == "upload" && copied.windowMillis == 300L)
    val callbacks = KToastCallbacks(onDismiss = { _, _ -> })
    check(callbacks.copy().onDismiss != null)
    check(ToastHandle.DismissReason.values().size == 5)
    println("Kotlin consumer compiled against 1.0.5 linked successfully")
}
