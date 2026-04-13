package com.logan.ktoast.initializer

import android.app.Application
import android.content.Context
import androidx.startup.Initializer
import com.logan.ktoast.KToast

class KToastInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        KToast.init(context.applicationContext as Application)
    }

    override fun dependencies() = emptyList<Class<out Initializer<*>>>()
}