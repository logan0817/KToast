package com.logan.ktoast.utils

import android.os.Looper
import com.logan.ktoast.KToast

/**
 * 内部工具类 (Internal)
 * 处理单位转换、线程检查和初始化校验
 */
object KToastUtils {

    /**
     * 检查 KToast 是否已初始化
     * @throws IllegalStateException 如果未初始化
     */
    fun checkInit() {
        if (!KToast.isInitialized()) {
            throw IllegalStateException("KToast has not been initialized. Please call KToast.init(app) in your Application class.")
        }
    }

    /**
     * dp 转 px
     */
    fun dp2px(dpValue: Float): Int {
        checkInit()
        val scale = KToast.context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    /**
     * sp 转 px
     */
    fun sp2px(spValue: Float): Int {
        checkInit()
        val fontScale = KToast.context.resources.displayMetrics.scaledDensity
        return (spValue * fontScale + 0.5f).toInt()
    }

    /**
     * 检查当前是否在主线程
     */
    fun isMainThread(): Boolean = Looper.getMainLooper() == Looper.myLooper()

    /**
     * 安全地在主线程执行 Lambda
     * 如果当前已在主线程则直接执行，否则 Post 到主线程队列
     */
    fun runOnMainThread(block: () -> Unit) {
        if (isMainThread()) {
            block()
        } else {
            KToast.mainHandler.post(block)
        }
    }
}