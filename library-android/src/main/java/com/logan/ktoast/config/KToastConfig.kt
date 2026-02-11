package com.logan.ktoast.config

import android.graphics.Color
import android.view.Gravity

/**
 * KToast 样式配置类
 * 支持 DSL 方式修改
 */
data class KToastConfig(
    /** 文本颜色 */
    var textColor: Int = Color.WHITE,

    /** 背景颜色，建议使用带透明度的色值 */
    var backgroundColor: Int = Color.parseColor("#E6323232"),

    /** 背景圆角 (单位: dp) */
    var backgroundRadius: Float = 24f,

    /** 显示位置 Gravity */
    var gravity: Int = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,

    /** X 轴偏移量 (单位: px) */
    var xOffset: Int = 0,

    /** * Y 轴偏移量 (单位: px)
     * 注意：在 API 30+ 且应用处于后台时，系统原生 Toast 将忽略此偏移量
     */
    var yOffset: Int = 64,

    /** 文本大小 (单位: sp) */
    var textSize: Float = 14f,

    /** 水平内边距 (单位: dp) */
    var paddingHorizontal: Int = 24,

    /** 垂直内边距 (单位: dp) */
    var paddingVertical: Int = 12,

    /** 弹出/消失动画时长 (ms) */
    var animationDuration: Long = 250L,

    /** 是否允许点击气泡立即消失 */
    var cancelOnTouch: Boolean = false,

    /** 图标资源 ID (可选) */
    var icon: Int? = null,

    /** 图标颜色滤镜 (可选) */
    var iconColor: Int? = null,

    /** 图标大小 (单位: dp) */
    var iconSize: Float = 24f,

    /** 图标与文字间距 (单位: dp) */
    var iconPadding: Int = 8
)