package com.logan.ktoast.config

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.logan.ktoast.utils.KToastUtils

object KToastConfigFactory {

    /**
     * 根据当前系统环境创建默认配置
     */
    fun createDefault(context: Context): KToastConfig {
        return KToastConfig().apply {
            // 自动匹配系统 yOffset
            yOffset = getSystemToastYOffset(context)

            // 自动判断是否是深色模式，设置背景颜色
            val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            if (isDarkMode) {
                backgroundColor = Color.parseColor("#EE424242") // 深灰色
                textColor = Color.WHITE
            } else {
                backgroundColor = Color.parseColor("#E6323232") // 稍浅的暗色
                textColor = Color.WHITE
            }
        }
    }

    /**
     * 反射获取系统 Toast 的默认偏移量
     */
    private fun getSystemToastYOffset(context: Context): Int {
        return try {
            val resId = context.resources.getIdentifier("toast_y_offset", "dimen", "android")
            if (resId > 0) {
                context.resources.getDimensionPixelSize(resId)
            } else {
                KToastUtils.dp2px(64f) // 找不到则保底使用 64dp
            }
        } catch (e: Exception) {
            KToastUtils.dp2px(64f)
        }
    }
}