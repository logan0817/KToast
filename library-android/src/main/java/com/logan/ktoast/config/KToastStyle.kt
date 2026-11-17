package com.logan.ktoast.config

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import com.logan.ktoast.R
import kotlin.math.roundToInt

/**
 * 可复用的语义和品牌样式，不持有 Context 或 View。
 * [styleRes] 必须是 style 资源 ID，为 0 时不叠加品牌 XML；资源可使用 values-night 和宿主主题属性。
 */
data class KToastStyle @JvmOverloads constructor(
    val type: KToastType = KToastType.NORMAL,
    val styleRes: Int = 0
) {
    /**
     * 返回基础配置的独立副本，依次应用语义和品牌 XML，不改变 [baseConfig]。
     * 在调用线程读取资源和主题，不创建 View、无需初始化 KToast；调用期间不要并发修改主题。
     * XML 几何尺寸使用 Android dimension，文本字号建议使用 sp。
     */
    @JvmOverloads
    fun resolve(context: Context, baseConfig: KToastConfig = KToastConfig()): KToastConfig {
        val config = baseConfig.snapshot()
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applySemantic(config, dark)
        if (styleRes != 0) applyXml(context, config)
        return config
    }

    internal fun applySemantic(config: KToastConfig, dark: Boolean) {
        when (type) {
            KToastType.NORMAL -> return
            KToastType.SUCCESS -> {
                config.backgroundColor = if (dark) 0xFFB9F6CA.toInt() else 0xFF1B5E20.toInt()
                config.textColor = if (dark) 0xFF103C1C.toInt() else 0xFFFFFFFF.toInt()
                config.icon = R.drawable.ktoast_style_success
            }
            KToastType.ERROR -> {
                config.backgroundColor = if (dark) 0xFFFFDAD6.toInt() else 0xFFB71C1C.toInt()
                config.textColor = if (dark) 0xFF410002.toInt() else 0xFFFFFFFF.toInt()
                config.icon = R.drawable.ktoast_style_error
            }
            KToastType.WARNING -> {
                config.backgroundColor = if (dark) 0xFF5C4300.toInt() else 0xFFFFE082.toInt()
                config.textColor = if (dark) 0xFFFFE082.toInt() else 0xFF3E2723.toInt()
                config.icon = R.drawable.ktoast_style_warning
            }
            KToastType.INFO -> {
                config.backgroundColor = if (dark) 0xFFD1E4FF.toInt() else 0xFF0D47A1.toInt()
                config.textColor = if (dark) 0xFF001D36.toInt() else 0xFFFFFFFF.toInt()
                config.icon = R.drawable.ktoast_style_info
            }
        }
        config.iconColor = config.textColor
    }

    private fun applyXml(context: Context, config: KToastConfig) {
        require(context.resources.getResourceTypeName(styleRes) == "style") {
            "KToastStyle.styleRes must reference a style resource."
        }
        // 空主题只判断品牌及父样式的声明，实际取值仍通过宿主解析 ?attr。
        val declarations = context.resources.newTheme().obtainStyledAttributes(styleRes, R.styleable.KToastStyle)
        val declared = try {
            BooleanArray(R.styleable.KToastStyle.size) { declarations.hasValue(it) }
        } finally {
            declarations.recycle()
        }
        val values = context.obtainStyledAttributes(styleRes, R.styleable.KToastStyle)
        val metrics = context.resources.displayMetrics
        fun dp(index: Int, fallback: Float): Float =
            if (values.hasValue(index)) values.getDimension(index, 0f) / metrics.density else fallback
        try {
            for (index in declared.indices) {
                if (!declared[index]) continue
                when (index) {
                    R.styleable.KToastStyle_kToastTextColor -> config.textColor = values.getColor(index, config.textColor)
                    R.styleable.KToastStyle_kToastBackgroundColor -> config.backgroundColor = values.getColor(index, config.backgroundColor)
                    R.styleable.KToastStyle_kToastBackgroundRadius -> config.backgroundRadius = dp(index, config.backgroundRadius)
                    R.styleable.KToastStyle_kToastTextSize -> if (values.hasValue(index)) {
                        val value = TypedValue()
                        values.getValue(index, value)
                        // 保留 sp 原值，避免大字体的非线性缩放在再次设置 TextView 字号时被重复计算。
                        config.textSize = if (value.type == TypedValue.TYPE_DIMENSION &&
                            (value.data and TypedValue.COMPLEX_UNIT_MASK) == TypedValue.COMPLEX_UNIT_SP) {
                            TypedValue.complexToFloat(value.data)
                        } else {
                            val pixels = values.getDimension(index, 0f)
                            if (Build.VERSION.SDK_INT >= 34) {
                                TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, pixels, metrics)
                            } else {
                                @Suppress("DEPRECATION")
                                (pixels / metrics.scaledDensity)
                            }
                        }
                    }
                    R.styleable.KToastStyle_kToastPaddingHorizontal -> config.paddingHorizontal = dp(index, config.paddingHorizontal.toFloat()).roundToInt()
                    R.styleable.KToastStyle_kToastPaddingVertical -> config.paddingVertical = dp(index, config.paddingVertical.toFloat()).roundToInt()
                    R.styleable.KToastStyle_kToastIcon -> if (values.hasValue(index)) {
                        config.icon = values.getResourceId(index, 0).takeIf { it != 0 }
                    }
                    R.styleable.KToastStyle_kToastIconColor -> if (values.hasValue(index)) {
                        config.iconColor = values.getColor(index, config.textColor)
                    }
                    R.styleable.KToastStyle_kToastIconSize -> config.iconSize = dp(index, config.iconSize)
                    R.styleable.KToastStyle_kToastIconPadding -> config.iconPadding = dp(index, config.iconPadding.toFloat()).roundToInt()
                    R.styleable.KToastStyle_kToastMaxLines -> config.maxLines = values.getInt(index, config.maxLines)
                    R.styleable.KToastStyle_kToastMaxWidthRatio -> config.maxWidthRatio = values.getFloat(index, config.maxWidthRatio)
                    R.styleable.KToastStyle_kToastMinWidth -> config.minWidth = dp(index, config.minWidth.toFloat()).roundToInt()
                    R.styleable.KToastStyle_kToastMinHeight -> config.minHeight = dp(index, config.minHeight.toFloat()).roundToInt()
                    R.styleable.KToastStyle_kToastBorderColor -> if (values.hasValue(index)) {
                        config.borderColor = values.getColor(index, 0)
                    }
                    R.styleable.KToastStyle_kToastBorderWidth -> config.borderWidth = dp(index, config.borderWidth.toFloat()).roundToInt()
                    R.styleable.KToastStyle_kToastGravity -> config.gravity = values.getInt(index, config.gravity)
                    R.styleable.KToastStyle_kToastXOffset -> config.xOffset = values.getDimensionPixelOffset(index, config.xOffset)
                    R.styleable.KToastStyle_kToastYOffset -> config.yOffset = values.getDimensionPixelOffset(index, config.yOffset)
                }
            }
            // 隐藏优先于图标声明，不依赖 aapt 分配的属性顺序。
            if (declared[R.styleable.KToastStyle_kToastShowIcon] &&
                !values.getBoolean(R.styleable.KToastStyle_kToastShowIcon, true)) config.icon = null
        } finally {
            values.recycle()
        }
    }
}
