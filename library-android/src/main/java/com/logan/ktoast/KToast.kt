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
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.runtime.KToastRuntimePolicy
import com.logan.ktoast.runtime.ToastAdmission
import com.logan.ktoast.runtime.ToastDispatcher
import com.logan.ktoast.runtime.ToastDropReason
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoast.runtime.ToastRequest
import com.logan.ktoast.runtime.ToastTime
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
 * 默认通过 AndroidX Startup 初始化；关闭自动初始化时须在 Application 中调用 [init]。
 * 通过 [config] 定制全局样式；页面独有消息可使用 [KToastScope]。
 */
object KToast {

    /** 全局 Application 上下文 */
    internal lateinit var context: Application
    @Volatile private var initialized = false

    /** 主线程 Handler，用于调度 UI 操作和延时任务 */
    internal val mainHandler = Handler(Looper.getMainLooper())
    private val admission = ToastAdmission()

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
                mainHandler.postAtTime(task, ToastTime.add(SystemClock.uptimeMillis(), delayMillis))
                return ToastDispatcher.Cancellable {
                    mainHandler.removeCallbacks(task)
                }
            }
        },
        nowProvider = { SystemClock.uptimeMillis() },
        onError = { error ->
            if (debugMode) Log.e("KToast", "Toast request or callback failed.", error)
        },
        policyProvider = admission::policy
    )

    /** 全局配置对象，存储默认样式 */
    internal var globalConfig = KToastConfig()

    /** 当前正在显示的策略实例，用于处理连续调用时的取消逻辑 */
    private var currentStrategy: IToastStrategy? = null
    private val pendingSubmissions = ConcurrentHashMap<Long, ToastRequest>()
    private val delayedTasks = mutableMapOf<Runnable, ToastAdmission.Ticket>()
    private val scheduledSubmissions = mutableMapOf<Long, Runnable>()
    @Volatile private var globalCancelGeneration = 0L
    private val initLock = Any()

    /**
     * 调试模式开关
     *
     * * `true`: 允许显示 [debugShow] 触发的 Toast，且发生异常时会在控制台打印详细日志。
     * * `false`: 忽略所有调试级别的 Toast，内部异常日志静默处理（默认）。
     *
     * 建议在开发阶段开启，线上环境关闭。
     */
    // 保留旧 setter 的 JVM 签名，但避免 Java 与静态 setDebugMode(boolean) 发生重载歧义。
    @set:JvmSynthetic
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
     * 设置可选全库运行限制，必须在主线程、初始化完成后、首个请求/延时任务前调用。
     * 首次登记会冻结策略，后续修改抛出 IllegalStateException；null 限制保留旧规则。
     */
    @JvmStatic
    fun setRuntimePolicy(policy: KToastRuntimePolicy) {
        check(KToastUtils.isMainThread()) { "KToast.setRuntimePolicy must run on the main thread." }
        KToastUtils.checkInit()
        admission.configure(policy)
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
    ): ToastHandle = prepareSubmission(message, duration, behavior, callbacks, localConfig)

    internal fun submitScopedRequest(
        scope: KToastScope,
        message: CharSequence,
        delayMillis: Long,
        duration: Int,
        behavior: KToastBehavior,
        callbacks: KToastCallbacks,
        localConfig: (KToastConfig.() -> Unit)?,
        explicitConfig: KToastConfig? = null
    ): ToastHandle = prepareSubmission(message, duration, behavior, callbacks, localConfig,
        scope, delayMillis, explicitConfig)

    private fun prepareSubmission(
        message: CharSequence,
        duration: Int,
        behavior: KToastBehavior,
        callbacks: KToastCallbacks,
        localConfig: (KToastConfig.() -> Unit)?,
        scope: KToastScope? = null,
        delayMillis: Long = 0L,
        explicitConfig: KToastConfig? = null,
        transferredTicket: ToastAdmission.Ticket? = null,
        plannedAt: Long? = null,
        submittedGeneration: Long = globalCancelGeneration
    ): ToastHandle {
        KToastUtils.checkInit()
        val generation = scope?.submissionGeneration()
        val submittedAt = SystemClock.uptimeMillis()
        val dueAt = ToastTime.add(submittedAt, delayMillis)
        // 旧 Runnable 手动提前 run 仍立即触发；迟到执行则保留原计划时间计算有效期。
        val eligibleAt = plannedAt?.coerceAtMost(submittedAt) ?: dueAt
        val ticket = transferredTicket ?: admission.acquire()
        val handle = ToastHandle()
        val request = ToastRequest(message, duration, behavior = behavior.copy(), callbacks = callbacks,
            handle = handle, scope = scope, admission = ticket,
            eligibleAt = eligibleAt.takeUnless { behavior.displayMode == KToastDisplayMode.DEBOUNCE },
            cleanup = {
                pendingSubmissions.remove(handle.id)
                removeScheduledSubmission(handle.id)
            })
        dispatcher.prepare(request) { action ->
            KToastUtils.runOnMainThread(action)
        }
        if (ticket == null) {
            KToastUtils.runOnMainThread { dispatcher.reject(request, ToastDropReason.CAPACITY_EXCEEDED) }
            return handle
        }
        // 先登记再执行 DSL，取消能够找到仍在配置准备中的请求；额度直到准备退出才归还。
        pendingSubmissions[handle.id] = request
        try {
            val config = (explicitConfig ?: globalConfig).copy()
            if (explicitConfig == null && scope?.options?.followHostTheme == true) {
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
            if (!handle.isFinished) localConfig?.invoke(config)
            request.config = config
        } catch (error: Throwable) {
            pendingSubmissions.remove(handle.id)
            ticket.release()
            throw error
        } finally {
            ticket.endPreparation()
        }
        KToastUtils.runOnMainThread {
            if (submittedGeneration != globalCancelGeneration ||
                (scope != null && (generation == null || !scope.canShow(generation))) ||
                handle.isCancellationRequested || handle.isFinished) {
                dispatcher.cancelPreparedRequest(request)
            } else if (dueAt > SystemClock.uptimeMillis()) {
                dispatcher.watch(request)
                if (!handle.isFinished) {
                    val task = Runnable {
                        scheduledSubmissions.remove(handle.id)
                        pendingSubmissions.remove(handle.id)
                        dispatcher.submit(request)
                    }
                    scheduledSubmissions[handle.id] = task
                    mainHandler.postAtTime(task, dueAt)
                }
            } else {
                pendingSubmissions.remove(handle.id)
                dispatcher.submit(request)
            }
        }
        return handle
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
        val ticket = admission.acquire()
        val dueAt = ToastTime.add(SystemClock.uptimeMillis(), delayMillis)
        val task = object : Runnable {
            override fun run() {
                val generation = globalCancelGeneration
                val accepted = synchronized(delayedTasks) {
                    delayedTasks.remove(this)?.also { it.beginPreparation() }
                }
                if (accepted != null) {
                    mainHandler.removeCallbacks(this)
                    prepareSubmission(message, duration, KToastBehavior(), KToastCallbacks(), localConfig,
                        transferredTicket = accepted, plannedAt = dueAt, submittedGeneration = generation)
                }
            }
        }
        if (ticket != null) {
            try {
                synchronized(delayedTasks) {
                    delayedTasks[task] = ticket
                    mainHandler.postAtTime(task, dueAt)
                }
            } finally {
                ticket.endPreparation()
            }
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
            delayedTasks.remove(task)?.release()
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
            globalCancelGeneration++
            synchronized(delayedTasks) {
                delayedTasks.forEach { (task, ticket) ->
                    mainHandler.removeCallbacks(task)
                    ticket.release()
                }
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
