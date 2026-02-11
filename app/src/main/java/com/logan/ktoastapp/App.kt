package com.logan.ktoastapp

import android.app.Application
import android.graphics.Color
import android.view.Gravity
import com.logan.ktoast.BuildConfig
import com.logan.ktoast.KToast

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 初始化框架
        KToast.init(this)
        //设置调试模式开关
        KToast.debugMode = BuildConfig.DEBUG
        //(可选) 配置全局默认样式
        KToast.config {
            textColor = Color.WHITE
            backgroundColor = Color.parseColor("#E6323232")
            backgroundRadius = 24f
            gravity = Gravity.CENTER
            yOffset = 0
            textSize = 14f
        }
    }

}