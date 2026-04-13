package com.logan.ktoast

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.config.KToastConfigFactory
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.ToastDispatcher
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoast.runtime.ToastRequest
import com.logan.ktoast.strategy.IToastStrategy
import com.logan.ktoast.strategy.ModernToastStrategy
import com.logan.ktoast.strategy.SystemToastStrategy
import com.logan.ktoast.strategy.ToastPresentationListener
import com.logan.ktoast.utils.KActivityStack
import com.logan.ktoast.utils.KToastUtils

/**
 * KToast 核心管理类 (Singleton)
 *
 * 负责全局配置管理、框架初始化以及 Toast 显示策略的调度。
 * 应通过 [init] 方法在 Application 中初始化，
 * 通过 [config] 方法进行全局样式的定制。
 */
object KToast {

    /** 全局 Application 上下文 */
    internal lateinit var context: Application

    /** 主线程 Handler，用于调度 UI 操作和延时任务 */
    internal val mainHandler = Handler(Looper.getMainLooper())

    internal val dispatcher = ToastDispatcher(
        runtime = object : ToastDispatcher.Runtime {
            override fun show(request: ToastRequest) {
                presentRequest(request)
            }

            override fun cancelActive(reason: ToastHandle.DismissReason) {
                currentStrategy?.cancel(reason)
            }
        },
        scheduler = object : ToastDispatcher.Scheduler {
            override fun schedule(
                delayMillis: Long,
                action: () -> Unit
            ): ToastDispatcher.Cancellable {
                val task = Runnable(action)
                mainHandler.postDelayed(task, delayMillis)
                return ToastDispatcher.Cancellable {
                    mainHandler.removeCallbacks(task)
                }
            }
        },
        nowProvider = { SystemClock.uptimeMillis() }
    )

    /** 全局配置对象，存储默认样式 */
    internal var globalConfig = KToastConfig()

    /** 当前正在显示的策略实例，用于处理连续调用时的取消逻辑 */
    private var currentStrategy: IToastStrategy? = null

    /**
     * 调试模式开关
     *
     * * `true`: 允许显示 [debugShow] 触发的 Toast，且发生异常时会在控制台打印详细日志。
     * * `false`: 忽略所有调试级别的 Toast，内部异常日志静默处理（默认）。
     *
     * 建议在开发阶段开启，线上环境关闭。
     */
    var debugMode: Boolean = false

    /**
     * 检查 KToast 是否已完成初始化。
     *
     * @return true 表示已初始化，false 表示未初始化。
     */
    @JvmStatic
    fun isInitialized(): Boolean {
        return this::context.isInitialized
    }

    /**
     * 初始化框架
     *
     * 默认情况下会通过 Jetpack App Startup 自动初始化。
     * 如果业务方禁用了 App Startup，也可以在 [Application.onCreate] 中手动调用。
     *
     * @param app Application 实例，用于获取上下文和注册生命周期监听。
     */
    @JvmStatic
    fun init(app: Application) {
        // 初始化逻辑：仅在 context 未初始化时执行（通常是自动初始化那次）
        if (!isInitialized()) {
            this.context = app
            // 注册生命周期监听
            KActivityStack.register(app)
            // 使用工厂创建系统适配的默认配置
            globalConfig = KToastConfigFactory.createDefault(app)
        }
    }

    /**
     * 设置调试模式
     */
    @JvmStatic
    fun setDebugMode(enabled: Boolean): KToast {
        this.debugMode = enabled
        return this
    }

    /**
     * 全局样式配置 DSL
     *
     * 示例：
     * ```kotlin
     * KToast.config {
     * backgroundColor = Color.BLACK
     * backgroundRadius = 10f
     * }
     * ```
     */
    @JvmStatic
    fun config(block: KToastConfig.() -> Unit): KToast {
        this.globalConfig.apply(block)
        return this
    }

    /**
     * 核心显示方法 (Internal)
     *
     * 内部负责：
     * 1. 切换至主线程。
     * 2. 合并全局配置与局部配置。
     * 3. 根据应用前后台状态，智能选择渲染策略。
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        message: CharSequence,
        duration: Int,
        localConfig: (KToastConfig.() -> Unit)? = null
    ) {
        submitRequest(message, duration, KToastBehavior(), KToastCallbacks(), localConfig)
    }

    @JvmStatic
    @JvmOverloads
    fun showHandle(
        message: CharSequence,
        duration: Int = Toast.LENGTH_SHORT,
        behavior: KToastBehavior = KToastBehavior(),
        callbacks: KToastCallbacks = KToastCallbacks(),
        localConfig: (KToastConfig.() -> Unit)? = null
    ): ToastHandle {
        return submitRequest(message, duration, behavior, callbacks, localConfig)
    }

    @JvmStatic
    fun cancelGroup(groupKey: String) {
        KToastUtils.runOnMainThread {
            dispatcher.cancelGroup(groupKey)
        }
    }

    private fun submitRequest(
        message: CharSequence,
        duration: Int,
        behavior: KToastBehavior,
        callbacks: KToastCallbacks,
        localConfig: (KToastConfig.() -> Unit)? = null
    ): ToastHandle {
        KToastUtils.checkInit()
        val finalConfig = globalConfig.copy().apply {
            localConfig?.invoke(this)
        }
        val request = ToastRequest(
            message = message,
            duration = duration,
            config = finalConfig,
            behavior = behavior.copy(),
            callbacks = callbacks,
            handle = ToastHandle()
        )

        // 确保在主线程执行显示逻辑
        KToastUtils.runOnMainThread {
            dispatcher.submit(request)
        }
        return request.handle
    }

    /**
     * Java 友好的调试 Toast。
     */
    @JvmStatic
    @JvmOverloads
    fun showDebug(
        message: CharSequence,
        duration: Int = Toast.LENGTH_SHORT
    ) {
        if (!debugMode) return
        show(message, duration)
    }

    /**
     * Java 友好的延时 Toast。
     */
    @JvmStatic
    @JvmOverloads
    fun showDelayed(
        message: CharSequence,
        delayMillis: Long,
        duration: Int = Toast.LENGTH_SHORT
    ): Runnable {
        return enqueueDelayedShow(message, delayMillis, duration)
    }

    /**
     * Java 友好的资源 ID 版本延时 Toast。
     */
    @JvmStatic
    @JvmOverloads
    fun showDelayed(
        messageResId: Int,
        delayMillis: Long,
        duration: Int = Toast.LENGTH_SHORT
    ): Runnable {
        KToastUtils.checkInit()
        return enqueueDelayedShow(context.getString(messageResId), delayMillis, duration)
    }

    /**
     * 延时显示核心逻辑 (Internal)
     *
     * 利用 Handler 消息队列机制实现非阻塞延时。
     * 即使在延时期间 Activity 发生跳转，只要应用在前台，ModernToastStrategy 仍能正确找到新的 TopActivity 显示。
     *
     * * @return 返回 Runnable 对象，可用于手动取消该任务
     */
    @JvmStatic
    @JvmOverloads
    internal fun enqueueDelayedShow(
        message: CharSequence,
        delayMillis: Long,
        duration: Int,
        localConfig: (KToastConfig.() -> Unit)? = null
    ): Runnable {
        KToastUtils.checkInit()

        val task = Runnable {
            show(message, duration, localConfig)
        }
        mainHandler.postDelayed(task, delayMillis)
        return task
    }

    /**
     * 取消特定的延时任务
     */
    @JvmStatic
    fun cancelDelayed(task: Runnable?) {
        task?.let { mainHandler.removeCallbacks(it) }
    }

    /**
     * 取消当前正在显示的 Toast，并且取消所有还在排队的延时任务。
     * 通常用于页面销毁时清理，或者需要立即打断提示的场景。
     */
    @JvmStatic
    fun cancel() {
        KToastUtils.runOnMainThread {
            dispatcher.cancelAll()
            currentStrategy = null
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun presentRequest(request: ToastRequest) {
        var activeStrategy: IToastStrategy = if (KActivityStack.isAppForeground()) {
            ModernToastStrategy()
        } else {
            SystemToastStrategy()
        }
        currentStrategy = activeStrategy
        activeStrategy.show(request, object : ToastPresentationListener {
            override fun onFallbackToSystem(request: ToastRequest) {
                dispatcher.onFallbackToSystem(request.handle)
                val fallbackStrategy = SystemToastStrategy()
                activeStrategy = fallbackStrategy
                currentStrategy = fallbackStrategy
                fallbackStrategy.show(request, this)
            }

            override fun onDismiss(handle: ToastHandle, reason: ToastHandle.DismissReason) {
                if (currentStrategy === activeStrategy || reason == ToastHandle.DismissReason.CANCELED) {
                    currentStrategy = null
                }
                dispatcher.onPresentationFinished(handle, reason)
            }
        })
    }
}
