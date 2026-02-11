package com.logan.ktoast

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.config.KToastConfigFactory
import com.logan.ktoast.strategy.IToastStrategy
import com.logan.ktoast.strategy.ModernToastStrategy
import com.logan.ktoast.strategy.SystemToastStrategy
import com.logan.ktoast.utils.KActivityStack
import com.logan.ktoast.utils.KToastUtils

/**
 * KToast 核心管理类 (Singleton)
 *
 * 负责全局配置管理、框架初始化以及 Toast 显示策略的调度。
 * 开发者应通过 [init] 方法在 Application 中初始化，
 * 并通过 [config] 方法进行全局样式的定制。
 */
object KToast {

    /** 全局 Application 上下文 */
    internal lateinit var context: Application

    /** 主线程 Handler，用于调度 UI 操作和延时任务 */
    internal val mainHandler = Handler(Looper.getMainLooper())

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
    @JvmField
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
     * **注意：** 必须在 [Application.onCreate] 中调用。
     *
     * @param app Application 实例，用于获取上下文和注册生命周期监听。
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        app: Application,
        isDebug: Boolean = false,
        configBlock: (KToastConfig.() -> Unit)? = null
    ) {
        this.context = app
        this.debugMode = isDebug

        // 注册 Activity 生命周期监听
        KActivityStack.register(app)

        // 先使用工厂创建系统适配的默认配置
        globalConfig = KToastConfigFactory.createDefault(app)

        // 如果用户传了配置块，则覆盖默认配置
        configBlock?.let {
            globalConfig.apply(it)
        }
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
    fun config(block: KToastConfig.() -> Unit) {
        globalConfig.apply(block)
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
        // 确保在主线程执行显示逻辑
        KToastUtils.runOnMainThread {
            // 1. 如果当前有正在显示的 Toast，先取消它，防止视觉层叠
            currentStrategy?.cancel()

            // 2. 准备配置：以全局配置为基准，应用本次调用的局部修改
            val finalConfig = if (localConfig != null) {
                globalConfig.copy().apply(localConfig)
            } else {
                globalConfig
            }

            // 3. 智能策略选择
            // 前台 -> ModernToastStrategy (自定义 Window，支持动画、圆角、图标)
            // 后台 -> SystemToastStrategy (系统原生 Toast，保底方案，无样式限制)
            val strategy = if (KActivityStack.isAppForeground()) {
                ModernToastStrategy()
            } else {
                SystemToastStrategy()
            }

            // 4. 保存引用并执行显示
            currentStrategy = strategy
            strategy.show(message, duration, finalConfig)
        }
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
    internal fun showDelayed(
        message: CharSequence,
        delayMillis: Long,
        duration: Int,
        localConfig: (KToastConfig.() -> Unit)? = null
    ): Runnable {
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
            // 取消正在显示的
            currentStrategy?.cancel()
            currentStrategy = null

            // 清空 Handler 中所有待执行的延时 Toast 任务
            // 这样能防止"页面销毁了，过了3秒 Toast 却弹出来了"的问题
            mainHandler.removeCallbacksAndMessages(null)
        }
    }
}