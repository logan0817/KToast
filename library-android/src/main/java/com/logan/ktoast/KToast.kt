package com.logan.ktoast

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import java.util.concurrent.ConcurrentHashMap

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
    @Volatile private var initialized = false

    /** 主线程 Handler，用于调度 UI 操作和延时任务 */
    internal val mainHandler = Handler(Looper.getMainLooper())

    internal val dispatcher = ToastDispatcher(
        runtime = object : ToastDispatcher.Runtime {
            override fun show(request: ToastRequest) {
                presentRequest(request)
            }

            override fun cancelActive(reason: ToastHandle.DismissReason) {
                val strategy = currentStrategy
                currentStrategy = null
                strategy?.cancel(reason)
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
        nowProvider = { SystemClock.uptimeMillis() },
        onError = { error ->
            if (debugMode) Log.e("KToast", "Toast request or callback failed.", error)
        }
    )

    /** 全局配置对象，存储默认样式 */
    internal var globalConfig = KToastConfig()

    /** 当前正在显示的策略实例，用于处理连续调用时的取消逻辑 */
    private var currentStrategy: IToastStrategy? = null
    private val pendingSubmissions = ConcurrentHashMap<Long, ToastRequest>()
    private val delayedTasks = mutableSetOf<Runnable>()
    private val scheduledSubmissions = mutableMapOf<Long, Runnable>()
    private val initLock = Any()

    /**
     * 调试模式开关
     *
     * * `true`: 允许显示 [debugShow] 触发的 Toast，且发生异常时会在控制台打印详细日志。
     * * `false`: 忽略所有调试级别的 Toast，内部异常日志静默处理（默认）。
     *
     * 建议在开发阶段开启，线上环境关闭。
     */
    @Volatile var debugMode: Boolean = false

    /**
     * 检查 KToast 是否已完成初始化。
     *
     * @return true 表示已初始化，false 表示未初始化。
     */
    @JvmStatic
    fun isInitialized(): Boolean {
        return initialized
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
        synchronized(initLock) {
            // 初始化逻辑：仅在 context 未初始化时执行（通常是自动初始化那次）
            if (!isInitialized()) {
                globalConfig = KToastConfigFactory.createDefault(app)
                KActivityStack.register(app)
                // 最后发布初始化标记，避免其他线程观察到尚未准备好的全局配置。
                this.context = app
                initialized = true
            }
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
     * 全局样式配置 DSL。建议在初始化或主线程统一设置，不与其他线程的请求并发修改。
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
     * 全局显示入口，保留按应用前后台状态选择渲染策略的行为。
     *
     * 配置副本与局部 DSL 在调用线程准备；DSL 应保持轻量，避免直接操作 View。
     * 内容工厂、窗口操作和业务回调在主线程执行。页面独有消息应使用 [KToastScope]。
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
            val pending = pendingSubmissions.values.filter { it.behavior.groupKey == groupKey }
            pending.forEach {
                pendingSubmissions.remove(it.handle.id)
                removeScheduledSubmission(it.handle.id)
            }
            dispatcher.cancelGroup(groupKey, pending)
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

        // 先准备取消能力，避免工作线程返回句柄后、主线程入队前无法撤回。
        dispatcher.prepare(request, KToastUtils::runOnMainThread)
        pendingSubmissions[request.handle.id] = request
        KToastUtils.runOnMainThread {
            pendingSubmissions.remove(request.handle.id)
            dispatcher.submit(request)
        }
        return request.handle
    }

    internal fun submitScopedRequest(
        scope: KToastScope,
        message: CharSequence,
        delayMillis: Long,
        duration: Int,
        behavior: KToastBehavior,
        callbacks: KToastCallbacks,
        localConfig: (KToastConfig.() -> Unit)?,
        explicitConfig: KToastConfig? = null
    ): ToastHandle {
        KToastUtils.checkInit()
        // 配置准备可发生在工作线程；取消或页面停止后恢复也不能复活旧请求。
        val generation = scope.submissionGeneration()
        val submittedAt = SystemClock.uptimeMillis()
        val config = (explicitConfig ?: globalConfig).copy()
        if (explicitConfig == null && scope.options.followHostTheme) {
            scope.host()?.let { host ->
                val colors = host.obtainStyledAttributes(R.styleable.KToastHostTheme)
                try {
                    config.backgroundColor = colors.getColor(R.styleable.KToastHostTheme_android_colorBackground, config.backgroundColor)
                    config.textColor = colors.getColor(R.styleable.KToastHostTheme_android_textColorPrimary, config.textColor)
                } finally {
                    colors.recycle()
                }
            }
        }
        localConfig?.invoke(config)
        val dueAt = submittedAt + delayMillis.coerceIn(0L, Long.MAX_VALUE - submittedAt)
        val request = ToastRequest(message, duration, config, behavior.copy(), callbacks, ToastHandle(), scope)
        dispatcher.prepare(request) { action ->
            KToastUtils.runOnMainThread {
                removeScheduledSubmission(request.handle.id)
                pendingSubmissions.remove(request.handle.id)
                action()
            }
        }
        pendingSubmissions[request.handle.id] = request
        KToastUtils.runOnMainThread {
            if (generation == null || !scope.canShow(generation) || request.handle.isCancellationRequested || request.handle.isFinished) {
                pendingSubmissions.remove(request.handle.id)
                dispatcher.cancelPreparedRequest(request)
            } else if (dueAt > SystemClock.uptimeMillis()) {
                val task = Runnable {
                    scheduledSubmissions.remove(request.handle.id)
                    pendingSubmissions.remove(request.handle.id)
                    dispatcher.submit(request)
                }
                scheduledSubmissions[request.handle.id] = task
                mainHandler.postDelayed(task, (dueAt - SystemClock.uptimeMillis()).coerceAtLeast(0L))
            } else {
                pendingSubmissions.remove(request.handle.id)
                dispatcher.submit(request)
            }
        }
        return request.handle
    }

    internal fun cancelScope(scopeId: Long) {
        KToastUtils.runOnMainThread {
            val pending = pendingSubmissions.values.filter { it.scope?.id == scopeId }
            pending.forEach {
                pendingSubmissions.remove(it.handle.id)
                removeScheduledSubmission(it.handle.id)
            }
            dispatcher.cancelScope(scopeId, pending)
        }
    }

    private fun removeScheduledSubmission(handleId: Long) {
        scheduledSubmissions.remove(handleId)?.let { mainHandler.removeCallbacks(it) }
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

        val task = object : Runnable {
            override fun run() {
                val accepted = synchronized(delayedTasks) { delayedTasks.remove(this) }
                if (accepted) {
                    mainHandler.removeCallbacks(this)
                    show(message, duration, localConfig)
                }
            }
        }
        synchronized(delayedTasks) {
            delayedTasks.add(task)
            mainHandler.postDelayed(task, delayMillis)
        }
        return task
    }

    /**
     * 取消特定的延时任务
     */
    @JvmStatic
    fun cancelDelayed(task: Runnable?) {
        if (task == null) return
        synchronized(delayedTasks) {
            delayedTasks.remove(task)
            mainHandler.removeCallbacks(task)
        }
    }

    /**
     * 取消全局及页面作用域的当前、排队、防抖、待提交和延时请求。
     * 工作线程调用时在主线程清理；终态回调中新提交的请求保留。
     * 仅清理一个页面的消息时使用 [KToastScope.cancel]。
     */
    @JvmStatic
    fun cancel() {
        KToastUtils.runOnMainThread {
            synchronized(delayedTasks) {
                delayedTasks.forEach { mainHandler.removeCallbacks(it) }
                delayedTasks.clear()
            }
            val pending = pendingSubmissions.values.toList()
            pending.forEach {
                pendingSubmissions.remove(it.handle.id)
                removeScheduledSubmission(it.handle.id)
            }
            // 保留已投递的提交/取消操作，让句柄能够收到终态；避免清空整个 Handler。
            dispatcher.cancelAll(pending)
        }
    }

    private fun presentRequest(request: ToastRequest) {
        var activeStrategy: IToastStrategy = if (request.scope != null || KActivityStack.isAppForeground()) {
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
                if (currentStrategy === activeStrategy) {
                    currentStrategy = null
                }
                dispatcher.onPresentationFinished(handle, reason)
            }
        })
    }
}
