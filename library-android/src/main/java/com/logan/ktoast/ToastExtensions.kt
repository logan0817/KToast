package com.logan.ktoast

import android.widget.Toast
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.utils.KToastUtils

/**
 * KToast Kotlin 扩展函数库
 *
 * 提供简洁的链式调用语法，支持 String 和 Resource ID 直接发起调用。
 * 包含：基础显示、调试显示、延时显示。
 */

// =================================================================================
// 基础显示 (Normal Show)
// =================================================================================

/**
 * 显示 Toast (CharSequence 扩展)
 *
 * @param duration 显示时长，默认 [Toast.LENGTH_SHORT]
 * @param block 局部配置 DSL，可临时修改颜色、位置、图标等
 */
fun CharSequence?.toast(
    duration: Int = Toast.LENGTH_SHORT,
    block: (KToastConfig.() -> Unit)? = null
) {
    if (this.isNullOrEmpty()) return
    KToast.show(this, duration, block)
}

/**
 * 显示 Toast (Resource ID 扩展)
 *
 * @param duration 显示时长，默认 [Toast.LENGTH_SHORT]
 * @param block 局部配置 DSL
 */
fun Int.toast(
    duration: Int = Toast.LENGTH_SHORT,
    block: (KToastConfig.() -> Unit)? = null
) {
    // 预检查初始化，防止在 Application 初始化前调用导致 Context 空指针
    KToastUtils.checkInit()
    KToast.context.getString(this).toast(duration, block)
}

// =================================================================================
// 调试显示 (Debug Show)
// =================================================================================

/**
 * 调试模式 Toast
 *
 * 仅当 [KToast.debugMode] 为 true 时才会显示。
 * 适用于：打印接口错误信息、调试埋点、开发阶段提示，避免污染线上环境。
 */
fun CharSequence?.debugShow(
    duration: Int = Toast.LENGTH_SHORT,
    block: (KToastConfig.() -> Unit)? = null
) {
    if (!KToast.debugMode) return
    this.toast(duration, block)
}

fun Int.debugShow(
    duration: Int = Toast.LENGTH_SHORT,
    block: (KToastConfig.() -> Unit)? = null
) {
    if (!KToast.debugMode) return
    this.toast(duration, block)
}

// =================================================================================
// 延时显示 (Delayed Show)
// =================================================================================

/**
 * 延时显示 (Delayed Show)
 * 适用于：界面跳转前的提示、动画结束后的提示等异步场景。
 *
 * @return Runnable? 返回延时任务对象。如果需要中途取消，可调用 [KToast.cancelDelayed(runnable)]
 */
fun CharSequence?.toastDelayed(
    delayMillis: Long,
    duration: Int = Toast.LENGTH_SHORT,
    block: (KToastConfig.() -> Unit)? = null
): Runnable? {
    if (this.isNullOrEmpty()) return null
    return KToast.showDelayed(this, delayMillis, duration, block)
}

/**
 * 延时显示 Toast (Resource ID)
 * * @return Runnable? 返回延时任务对象。
 */
fun Int.toastDelayed(
    delayMillis: Long,
    duration: Int = Toast.LENGTH_SHORT,
    block: (KToastConfig.() -> Unit)? = null
): Runnable? {
    KToastUtils.checkInit()
    val msg = KToast.context.getString(this)
    return KToast.showDelayed(msg, delayMillis, duration, block)
}