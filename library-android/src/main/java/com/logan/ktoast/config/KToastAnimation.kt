package com.logan.ktoast.config

import android.animation.TimeInterpolator
import android.view.animation.OvershootInterpolator

/**
 * 前台自定义 Toast 的动画；系统 Toast 不读取此配置。
 * 插值器必须返回有限数值，提交后不要修改其内部状态。
 * 抛出 Exception 或返回非有限值会报告 ERROR、取消当前请求并继续队列。
 * [maxScale] 同时决定透明边距和缩放上限，较强回弹可显式调大（1～2）。
 */
data class KToastAnimation @JvmOverloads constructor(
    /** null 使用原有的默认回弹。 */
    val enterInterpolator: TimeInterpolator? = null,
    /** null 使用原有的默认回弹。 */
    val exitInterpolator: TimeInterpolator? = null,
    val maxScale: Float = 1.05f
) {
    init {
        require(maxScale.isFinite() && maxScale in 1f..2f) { "maxScale must be finite and between 1 and 2." }
    }

    internal fun boundedInterpolator(entering: Boolean): TimeInterpolator {
        val delegate = (if (entering) enterInterpolator else exitInterpolator) ?: OvershootInterpolator()
        val start = if (entering) 0.8f else 1f
        val delta = if (entering) 0.2f else -0.2f
        val first = -start / delta
        val second = (maxScale - start) / delta
        return TimeInterpolator { fraction ->
            val value = delegate.getInterpolation(fraction)
            require(value.isFinite()) { "Toast animation interpolator must return a finite value." }
            value.coerceIn(minOf(first, second), maxOf(first, second))
        }
    }
}
