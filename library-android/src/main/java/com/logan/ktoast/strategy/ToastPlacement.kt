package com.logan.ktoast.strategy

import android.view.Gravity
import android.annotation.SuppressLint

/** 对物理 Gravity 计算位置；偏移再大也不能将页面提示推到安全区域之外。 */
internal object ToastPlacement {
    // 调用方已经解析 START/END；这里输入的是物理方向，不能再按语言翻转。
    @SuppressLint("RtlHardcoded")
    fun resolve(bounds: ToastWindowBounds, width: Int, height: Int, gravity: Int, x: Int, y: Int): Pair<Int, Int> {
        val horizontal = when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.RIGHT -> bounds.width.toLong() - width - x
            Gravity.CENTER_HORIZONTAL -> (bounds.width.toLong() - width) / 2 + x
            else -> x.toLong()
        }
        val vertical = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> bounds.height.toLong() - height - y
            Gravity.CENTER_VERTICAL -> (bounds.height.toLong() - height) / 2 + y
            else -> y.toLong()
        }
        val left = bounds.left.toLong()
        val top = bounds.top.toLong()
        val right = (bounds.width.toLong() - bounds.right - width).coerceAtLeast(left)
        val bottom = (bounds.height.toLong() - bounds.bottom - height).coerceAtLeast(top)
        return horizontal.coerceIn(left, right).toInt() to vertical.coerceIn(top, bottom).toInt()
    }
}
