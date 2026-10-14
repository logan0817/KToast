package com.logan.ktoast.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.logan.ktoast.KToastScope
import com.logan.ktoast.KToastScopeOptions
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.ToastHandle
import java.lang.ref.WeakReference

/**
 * 绑定 LifecycleOwner 的页面提示。Fragment 应使用 viewLifecycleOwner。
 * 创建在主线程执行；显示/延时/取消/关闭可跨线程调用。停止结束当轮请求，恢复不复活旧请求。
 */
class KToastLifecycleScope private constructor(host: Activity, owner: LifecycleOwner, options: KToastScopeOptions) : AutoCloseable {
    private val hostReference = WeakReference(host)
    private val application = host.application
    private val stopped = KToastScope.create(host, options).apply { close() }
    private val hostCallbacks: Application.ActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity: Activity) {
            if (activity === hostReference.get()) close()
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    private val session: LifecycleSession<KToastScope> = LifecycleSession(owner.lifecycle, stopped, {
        hostReference.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { KToastScope.create(it, options) }
    }, ::onMain) {
        application.unregisterActivityLifecycleCallbacks(hostCallbacks)
        hostReference.clear()
    }

    init {
        if (!session.isClosed) application.registerActivityLifecycleCallbacks(hostCallbacks)
    }

    val isClosed: Boolean get() = session.isClosed

    @JvmOverloads
    fun show(
        message: CharSequence,
        duration: Int = Toast.LENGTH_SHORT,
        behavior: KToastBehavior = KToastBehavior(),
        callbacks: KToastCallbacks = KToastCallbacks(),
        localConfig: (KToastConfig.() -> Unit)? = null
    ): ToastHandle = session.current().show(message, duration, behavior, callbacks, localConfig)

    /** Java 可直接传入完整配置，避免 Kotlin DSL。 */
    @JvmOverloads
    fun show(
        message: CharSequence,
        config: KToastConfig,
        duration: Int = Toast.LENGTH_SHORT,
        behavior: KToastBehavior = KToastBehavior(),
        callbacks: KToastCallbacks = KToastCallbacks()
    ): ToastHandle = session.current().show(message, config, duration, behavior, callbacks)

    @JvmOverloads
    fun showDelayed(
        message: CharSequence,
        delayMillis: Long,
        duration: Int = Toast.LENGTH_SHORT,
        behavior: KToastBehavior = KToastBehavior(),
        callbacks: KToastCallbacks = KToastCallbacks(),
        localConfig: (KToastConfig.() -> Unit)? = null
    ): ToastHandle = session.current().showDelayed(message, delayMillis, duration, behavior, callbacks, localConfig)

    /** 取消当前活动期请求，不关闭 Owner 绑定。 */
    fun cancel() = session.current().cancel()

    /** 永久关闭；立即关闭当前代理，监听器清理在主线程执行。可重复调用。 */
    override fun close(): Unit = session.close()

    companion object {
        private val handler = Handler(Looper.getMainLooper())

        private fun onMain(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
        }

        /** 宿主或 Owner 已结束时拒绝创建；Activity/View 重建后应重新绑定新实例。 */
        @JvmStatic
        @JvmOverloads
        fun create(host: Activity, owner: LifecycleOwner, options: KToastScopeOptions = KToastScopeOptions()): KToastLifecycleScope {
            check(Looper.myLooper() == Looper.getMainLooper()) { "KToastLifecycleScope.create must run on the main thread." }
            check(!host.isFinishing && !host.isDestroyed) { "Cannot bind an ended Activity." }
            check(owner.lifecycle.currentState != Lifecycle.State.DESTROYED) { "Cannot bind a destroyed LifecycleOwner." }
            return KToastLifecycleScope(host, owner, options)
        }
    }
}
