package com.logan.ktoast.strategy

import com.logan.ktoast.config.KToastConfig

interface IToastStrategy {
    fun show(message: CharSequence, duration: Int, config: KToastConfig)
    fun cancel()
}