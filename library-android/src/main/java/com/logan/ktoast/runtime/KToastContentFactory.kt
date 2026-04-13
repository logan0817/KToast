package com.logan.ktoast.runtime

import android.content.Context
import android.view.View
import com.logan.ktoast.config.KToastConfig

fun interface KToastContentFactory {
    fun create(context: Context, message: CharSequence, config: KToastConfig): View
}
