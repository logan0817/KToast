package com.logan.ktoast.strategy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.logan.ktoast.utils.KActivityStack
import com.logan.ktoast.KToast
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.utils.KToastUtils
import java.lang.ref.WeakReference

/**
 * 现代化 Toast 渲染策略
 *
 * 原理：
 * 使用 [WindowManager] 添加一个悬浮 View 到当前的 Activity Window 上。
 *
 * 优势：
 * 1. 绕过 Android 11+ 对原生 Toast [Toast.setView] 的限制。
 * 2. 支持高度自定义 UI（圆角、图标、布局）。
 * 3. 支持属性动画（进场/出场）。
 * 4. 支持交互（点击消失）。
 *
 * 注意：
 * 需要依赖 [KActivityStack] 获取当前栈顶 Activity，如果 Activity 销毁，会自动降级为系统 Toast。
 */
class ModernToastStrategy : IToastStrategy {

    // 使用弱引用防止内存泄漏，持有 View 和 WindowManager
    private var viewReference: WeakReference<View>? = null
    private var windowManagerReference: WeakReference<WindowManager>? = null

    // 自动消失的任务 Runnable
    private var dismissRunnable: Runnable? = null

    override fun show(message: CharSequence, duration: Int, config: KToastConfig) {
        val activity = KActivityStack.getTopActivity()

        // 基础检查：如果没有 Activity 或者 Activity 正在销毁，无法依附 Window，直接降级
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            fallbackToSystemToast(message, duration, config, "Activity is null or finishing")
            return
        }

        KToastUtils.runOnMainThread {
            // [双重检查] 线程切换需要时间，再次检查 Activity 状态
            if (activity.isFinishing || activity.isDestroyed) {
                return@runOnMainThread
            }

            cancel() // 彻底清理上一个 Toast

            val wm = activity.windowManager
            windowManagerReference = WeakReference(wm)

            val toastView = createBubbleView(activity, message, config)
            viewReference = WeakReference(toastView)

            // 配置点击消失逻辑
            if (config.cancelOnTouch) {
                toastView.setOnClickListener {
                    // 用户主动点击关闭，需移除原本的自动消失 Timer，防止逻辑冲突
                    removeDismissTimer()
                    executeExitAnimation(toastView, config.animationDuration) { cancel() }
                }
            }

            // 配置 WindowManager 参数
            val params = WindowManager.LayoutParams().apply {
                height = WindowManager.LayoutParams.WRAP_CONTENT
                width = WindowManager.LayoutParams.WRAP_CONTENT
                format = PixelFormat.TRANSLUCENT
                // TYPE_APPLICATION_PANEL：依附于应用窗口的面板，无需悬浮窗权限，必须绑定 Token
                type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL

                // FLAG_NOT_FOCUSABLE: 不获取焦点，保证后面页面可操作
                // FLAG_KEEP_SCREEN_ON: 保持屏幕常亮
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

                // 如果不允许点击，添加 FLAG_NOT_TOUCHABLE 让事件穿透到底层页面
                if (!config.cancelOnTouch) {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }

                gravity = config.gravity
                x = config.xOffset
                y = config.yOffset
                // [关键] 绑定 Token：必须使用 Activity 的 DecorView Token，否则抛出 BadTokenException
                token = activity.window.decorView.windowToken
            }

            try {
                wm.addView(toastView, params)
                executeEnterAnimation(toastView, config.animationDuration)

                // 设置自动消失 Timer
                val displayTime = if (duration == Toast.LENGTH_LONG) 3500L else 2000L

                dismissRunnable = Runnable {
                    val currentView = viewReference?.get()
                    // 确保 View 还在窗口上才执行退出动画
                    if (currentView != null && currentView.isAttachedToWindow) {
                        executeExitAnimation(currentView, config.animationDuration) { cancel() }
                    }
                }
                KToast.mainHandler.postDelayed(dismissRunnable!!, displayTime)

            } catch (e: Exception) {
                // 最终兜底：如果 AddView 失败（比如 BadTokenException、权限拦截、Token 失效）
                // 立刻降级显示系统 Toast，确保用户一定能看到消息
                if (KToast.debugMode) {
                    Log.e("KToast", "ModernToastStrategy failed, fallback to system toast.", e)
                }
                fallbackToSystemToast(message, duration, config, "Exception: ${e.message}")
            }
        }
    }

    /**
     * 移除自动消失的倒计时任务
     */
    private fun removeDismissTimer() {
        dismissRunnable?.let {
            KToast.mainHandler.removeCallbacks(it)
        }
        dismissRunnable = null
    }

    /**
     * 降级策略：使用系统原生 Toast
     */
    private fun fallbackToSystemToast(message: CharSequence, duration: Int, config: KToastConfig, reason: String) {
        if (KToast.debugMode) {
            Log.w("KToast", "Fallback to SystemToastStrategy. Reason: $reason")
        }
        SystemToastStrategy().show(message, duration, config)
    }

    /**
     * 动态构建气泡 View (LinearLayout + ImageView + TextView)
     */
    private fun createBubbleView(context: Context, message: CharSequence, config: KToastConfig): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            background = GradientDrawable().apply {
                setColor(config.backgroundColor)
                cornerRadius = KToastUtils.dp2px(config.backgroundRadius).toFloat()
            }

            val ph = KToastUtils.dp2px(config.paddingHorizontal.toFloat())
            val pv = KToastUtils.dp2px(config.paddingVertical.toFloat())
            setPadding(ph, pv, ph, pv)

            // 只有配置了 Icon 才添加 ImageView
            config.icon?.let { iconRes ->
                val imageView = ImageView(context).apply {
                    val size = KToastUtils.dp2px(config.iconSize)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = KToastUtils.dp2px(config.iconPadding.toFloat())
                    }
                    setImageResource(iconRes)
                    config.iconColor?.let { setColorFilter(it) }
                }
                addView(imageView)
            }

            val textView = TextView(context).apply {
                text = message
                textSize = config.textSize // sp
                setTextColor(config.textColor)
            }
            addView(textView)

            // Android 5.0+ 增加 Z 轴阴影
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = KToastUtils.dp2px(4f).toFloat()
            }

            // 初始状态：透明且缩小，用于进场动画
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
        }
    }

    private fun executeEnterAnimation(view: View, duration: Long) {
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(OvershootInterpolator()) // 回弹效果
            .start()
    }

    private fun executeExitAnimation(view: View, duration: Long, onEnd: () -> Unit) {
        view.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(duration)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                }
            })
            .start()
    }

    override fun cancel() {
        removeDismissTimer()
        val view = viewReference?.get()
        val wm = windowManagerReference?.get()

        if (view != null && wm != null) {
            try {
                view.animate().cancel() // 取消动画
                if (view.isAttachedToWindow) {
                    wm.removeViewImmediate(view) // 立即移除 View
                }
            } catch (e: Exception) {
                if (KToast.debugMode) e.printStackTrace()
            } finally {
                viewReference = null
                view.animate().setListener(null)
            }
        }
    }
}