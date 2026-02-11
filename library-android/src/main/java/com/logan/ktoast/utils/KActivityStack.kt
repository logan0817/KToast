package com.logan.ktoast.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

object KActivityStack {
    private var foregroundActivityCount = 0

    // 使用弱引用防止内存泄漏
    private var topActivityWeak: WeakReference<Activity>? = null

    /**
     * 判断应用是否处于前台
     */
    fun isAppForeground(): Boolean = foregroundActivityCount > 0

    /**
     * 获取当前顶部的 Activity
     */
    fun getTopActivity(): Activity? = topActivityWeak?.get()

    /**
     * 注册生命周期监听
     */
    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                topActivityWeak = WeakReference(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                foregroundActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount--
            }

            // 必须实现的接口方法，根据需要选填
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (getTopActivity() == activity) {
                    topActivityWeak = null
                }
            }
        })
    }
}