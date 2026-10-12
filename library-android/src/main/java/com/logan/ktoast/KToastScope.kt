package com.logan.ktoast

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.widget.Toast
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoast.utils.KActivityStack
import com.logan.ktoast.utils.KToastUtils
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Activity 页面提示作用域。停止时取消所有请求，销毁时永久关闭，不转移宿主。
 * create 必须在主线程调用；显示、取消和 close 可从工作线程调用，回调在主线程执行。
 */
class KToastScope private constructor(host: Activity, val options: KToastScopeOptions) : AutoCloseable {
    internal val id = nextId.incrementAndGet()
    private val hostReference = WeakReference(host)
    private val application = host.application
    @Volatile private var started = KActivityStack.isStarted(host)
    @Volatile private var cancellationGeneration = 0L
    /** close 调用时立即变为 true；窗口与监听器的清理在主线程完成。 */
    @Volatile var isClosed: Boolean = false
        private set

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity === hostReference.get()) started = true
        }
        override fun onActivityStopped(activity: Activity) {
            if (activity === hostReference.get()) {
                started = false
                cancel()
            }
        }
        override fun onActivityDestroyed(activity: Activity) {
            if (activity === hostReference.get()) close()
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    init {
        application.registerActivityLifecycleCallbacks(lifecycle)
    }

    /**
     * 提交页面提示并返回可取消句柄；页面未启动或作用域关闭时以 CANCELED 结束。
     * 局部配置 DSL 在调用线程执行，内容工厂和回调在主线程执行。
     */
    @JvmOverloads
    fun show(
        message: CharSequence,
        duration: Int = Toast.LENGTH_SHORT,
        behavior: KToastBehavior = KToastBehavior(),
        callbacks: KToastCallbacks = KToastCallbacks(),
        localConfig: (KToastConfig.() -> Unit)? = null
    ): ToastHandle = KToast.submitScopedRequest(this, message, 0L, duration, behavior, callbacks, localConfig)

    /** Java 可直接传配置对象，无需 Kotlin DSL；请求使用配置副本。 */
    @JvmOverloads
    fun show(
        message: CharSequence,
        config: KToastConfig,
        duration: Int = Toast.LENGTH_SHORT,
        behavior: KToastBehavior = KToastBehavior(),
        callbacks: KToastCallbacks = KToastCallbacks()
    ): ToastHandle = KToast.submitScopedRequest(this, message, 0L, duration, behavior, callbacks, null, config)

    /** 延时从调用时计算，返回与显示请求相同的句柄，可立即撤回。 */
    @JvmOverloads
    fun showDelayed(
        message: CharSequence,
        delayMillis: Long,
        duration: Int = Toast.LENGTH_SHORT,
        behavior: KToastBehavior = KToastBehavior(),
        callbacks: KToastCallbacks = KToastCallbacks(),
        localConfig: (KToastConfig.() -> Unit)? = null
    ): ToastHandle = KToast.submitScopedRequest(this, message, delayMillis, duration, behavior, callbacks, localConfig)

    /** 清理本作用域全部请求及去重历史，不关闭作用域；取消回调中的新请求可继续显示。 */
    fun cancel() {
        KToastUtils.runOnMainThread {
            // 尚在工作线程准备配置的请求还未进入队列，也必须观察本次取消边界。
            cancellationGeneration++
            KToast.cancelScope(id)
        }
    }

    /** 永久关闭并清理本作用域，可重复调用；后续提交的请求仍返回句柄并以 CANCELED 结束。 */
    override fun close() {
        isClosed = true
        KToastUtils.runOnMainThread {
            application.unregisterActivityLifecycleCallbacks(lifecycle)
            hostReference.clear()
            cancel()
        }
    }

    internal fun host(): Activity? = hostReference.get()

    internal fun submissionGeneration(): Long? {
        val generation = cancellationGeneration
        return generation.takeIf { started && !isClosed }
    }

    internal fun canShow(generation: Long): Boolean =
        canShow() && cancellationGeneration == generation

    internal fun canShow(): Boolean {
        val host = hostReference.get()
        return !isClosed && started && host != null && !host.isFinishing && !host.isDestroyed
    }

    companion object {
        private val nextId = AtomicLong()

        /**
         * 为明确宿主创建作用域，必须在主线程调用且框架已初始化。
         * 宿主已结束时抛出 IllegalStateException；Activity 重建后应创建新作用域。
         */
        @JvmStatic
        @JvmOverloads
        fun create(host: Activity, options: KToastScopeOptions = KToastScopeOptions()): KToastScope {
            check(KToastUtils.isMainThread()) { "KToastScope.create must run on the main thread." }
            KToastUtils.checkInit()
            check(!host.isFinishing && !host.isDestroyed) { "Cannot create a scope for an ended Activity." }
            return KToastScope(host, options)
        }
    }
}
