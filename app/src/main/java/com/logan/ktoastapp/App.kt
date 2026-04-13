package com.logan.ktoastapp

import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.view.Gravity
import com.logan.ktoast.KToast

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        //✅ 全局配置
        KToast.setDebugMode(isDebuggable).config {
            textColor = Color.WHITE
            backgroundColor = Color.parseColor("#E6323232")
            backgroundRadius = 24f
            gravity = Gravity.CENTER
            yOffset = 0
            textSize = 14f
        }
    }

}
