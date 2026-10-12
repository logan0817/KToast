package com.logan.ktoast

import com.logan.ktoast.runtime.KToastDiagnosticListener

/** 页面作用域的现代适配设置；旧全局入口不读取这些设置。 */
data class KToastScopeOptions @JvmOverloads constructor(
    /** 默认避让系统栏，API 30+ 同时考虑显示缺口。 */
    val avoidSystemBars: Boolean = true,
    /** 默认关闭；开启后随可见输入法与宿主窗口变化重新定位。 */
    val avoidIme: Boolean = false,
    /** API 29+ 按显示时内容向平台查询推荐时长；低版本保留普通时长。 */
    val useAccessibilityTimeout: Boolean = true,
    /** 显式开启后，按宿主当前主题读取背景与文字色，局部样式仍可覆盖。 */
    val followHostTheme: Boolean = false,
    /** 可选主线程观察器，异常被隔离，不记录消息正文。 */
    val diagnostics: KToastDiagnosticListener? = null
)
