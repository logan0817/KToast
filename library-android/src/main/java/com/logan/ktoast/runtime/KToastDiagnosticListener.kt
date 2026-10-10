package com.logan.ktoast.runtime

/** 可选页面诊断；在主线程调用，不包含消息正文，不代表系统送达。 */
fun interface KToastDiagnosticListener {
    /** detail 在 DISMISSED 时为结束原因，在 ERROR 时仅为异常类型，SHOWN 时为空。 */
    fun onEvent(handle: ToastHandle, event: KToastDiagnosticEvent, detail: String?)
}

/** 页面诊断事件；SHOWN 只表示渲染调用返回，不保证用户看到提示。 */
enum class KToastDiagnosticEvent {
    SHOWN,
    DISMISSED,
    ERROR
}
