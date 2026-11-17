package com.logan.ktoastapp

import com.logan.ktoast.KToast
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType

/** App 专属的成功提示 */
fun CharSequence?.toastSuccess(block: (KToastConfig.() -> Unit)? = null) {
    if (this.isNullOrBlank()) return
    KToast.showStyled(this, KToastStyle(KToastType.SUCCESS), localConfig = block)
}

/** App 专属的错误提示 */
fun CharSequence?.toastError(block: (KToastConfig.() -> Unit)? = null) {
    if (this.isNullOrBlank()) return
    KToast.showStyled(this, KToastStyle(KToastType.ERROR), localConfig = block)
}

/** App 专属的警告提示 */
fun CharSequence?.toastWarning(block: (KToastConfig.() -> Unit)? = null) {
    if (this.isNullOrBlank()) return
    KToast.showStyled(this, KToastStyle(KToastType.WARNING), localConfig = block)
}
