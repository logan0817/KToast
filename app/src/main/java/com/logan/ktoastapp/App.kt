package com.logan.ktoastapp

import android.app.Application
import android.graphics.Color
import android.view.Gravity
import com.logan.ktoast.BuildConfig
import com.logan.ktoast.KToast

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        //✅ 场景一：最简初始化 (什么都不配)
//        KToast.init(this)
        //✅ 场景二：只开 Debug (常用)
//        KToast.init(this, isDebug = BuildConfig.DEBUG)
        //✅ 场景三：全功能初始化 (一步到位)
        KToast.init(this, isDebug = BuildConfig.DEBUG) {
            textColor = Color.WHITE
            backgroundColor = Color.parseColor("#E6323232")
            backgroundRadius = 24f
            gravity = Gravity.CENTER
            yOffset = 0
            textSize = 14f
        }
    }

}