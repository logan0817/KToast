package com.logan.ktoast.strategy

import android.os.Build
import android.widget.Toast
import com.logan.ktoast.KToast
import com.logan.ktoast.config.KToastConfig

class SystemToastStrategy : IToastStrategy {
    private var toast: Toast? = null

    override fun show(message: CharSequence, duration: Int, config: KToastConfig) {
        cancel()

        KToast.mainHandler.post {
            toast = Toast.makeText(KToast.context, message, duration).apply {
                // API 30+ (Android 11) 只有在显示自定义 View 时 setGravity 才生效
                // 但系统 Toast 在 30+ 只能显示文本，所以这里的 setGravity 可能会被系统忽略
                // 我们依然保留调用以兼容旧版本
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    setGravity(config.gravity, config.xOffset, config.yOffset)
                }
                show()
            }
        }
    }

    override fun cancel() {
        try {
            toast?.cancel()
        } catch (e: Exception) {
            // 忽略某些国产 ROM 的异常
        }
        toast = null
    }
}