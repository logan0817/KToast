package com.logan.ktoastapp

import android.view.Gravity
import android.graphics.Color
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.toast

// 使用者可以根据自己的 UI 设计规范定义颜色
const val COLOR_SUCCESS = "#4CAF50" //成功样式 (绿色)
const val COLOR_ERROR = "#F44336"// 错误样式 (红色)

const val COLOR_WARNING = "#FF9800"//警告样式 (橙色)

/**
 * App 专属的成功提示
 */

// --- 成功样式 (绿色) ---

fun CharSequence?.toastSuccess(block: (KToastConfig.() -> Unit)? = null) {
    this.toast {
        backgroundColor = Color.parseColor(COLOR_SUCCESS)
        icon = R.drawable.ktoast_ic_success
        iconColor = Color.WHITE
        block?.invoke(this)
    }
}


// --- 错误样式 (红色) ---

fun CharSequence?.toastError(block: (KToastConfig.() -> Unit)? = null) {
    this.toast {
        backgroundColor = Color.parseColor(COLOR_ERROR)
        icon = R.drawable.ktoast_ic_error
        iconColor = Color.WHITE
        block?.invoke(this)
    }

}


// --- 警告样式 (橙色) ---

fun CharSequence?.toastWarning(block: (KToastConfig.() -> Unit)? = null) {
    this.toast {
        backgroundColor = Color.parseColor(COLOR_WARNING)
        icon = R.drawable.ktoast_ic_warning
        iconColor = Color.WHITE
        block?.invoke(this)
    }

}