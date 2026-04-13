package com.logan.ktoast.strategy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.logan.ktoast.KToast
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoast.runtime.ToastRequest
import com.logan.ktoast.utils.KActivityStack
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
internal class ModernToastStrategy : IToastStrategy {

    // 使用弱引用防止内存泄漏，持有 View 和 WindowManager
    private var viewReference: WeakReference<View>? = null
    private var windowManagerReference: WeakReference<WindowManager>? = null

    // 自动消失的任务 Runnable
    private var dismissRunnable: Runnable? = null
    private var currentRequest: ToastRequest? = null
    private var listener: ToastPresentationListener? = null
    private var dismissNotified = false

    override fun show(request: ToastRequest, listener: ToastPresentationListener) {
        val message = request.message
        val duration = request.duration
        val config = request.config ?: KToast.globalConfig.copy()
        val activity = KActivityStack.getTopActivity()

        // 基础检查：如果没有 Activity 或者 Activity 正在销毁，无法依附 Window，直接降级
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            fallbackToSystemToast(request, listener, "Activity is null or finishing")
            return
        }

        // [双重检查] 线程切换需要时间，再次检查 Activity 状态
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }

        val wm = activity.windowManager
        windowManagerReference = WeakReference(wm)
        currentRequest = request
        this.listener = listener
        dismissNotified = false

        val toastView = createToastView(activity, message, config)
        viewReference = WeakReference(toastView)

        // 配置点击消失逻辑
        if (config.cancelOnTouch) {
            toastView.setOnClickListener {
                // 用户主动点击关闭，需移除原本的自动消失 Timer，防止逻辑冲突
                removeDismissTimer()
                executeExitAnimation(toastView, config.animationDuration) {
                    removeCurrentView()
                    notifyDismiss(ToastHandle.DismissReason.CANCELED)
                }
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
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

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
            if (config.announceForAccessibility) {
                val description = toastView.contentDescription
                if (!description.isNullOrBlank()) {
                    toastView.post { toastView.announceForAccessibility(description) }
                }
            }
            executeEnterAnimation(toastView, config.animationDuration)

            // 设置自动消失 Timer
            val displayTime = if (duration == Toast.LENGTH_LONG) 3500L else 2000L

            dismissRunnable = Runnable {
                val currentView = viewReference?.get()
                // 确保 View 还在窗口上才执行退出动画
                if (currentView != null && currentView.isAttachedToWindow) {
                    executeExitAnimation(currentView, config.animationDuration) {
                        removeCurrentView()
                        notifyDismiss(ToastHandle.DismissReason.COMPLETED)
                    }
                }
            }
            KToast.mainHandler.postDelayed(dismissRunnable!!, displayTime)

        } catch (e: Exception) {
            // 最终兜底：如果 AddView 失败（比如 BadTokenException、权限拦截、Token 失效）
            // 立刻降级显示系统 Toast，确保用户一定能看到消息
            if (KToast.debugMode) {
                Log.e("KToast", "ModernToastStrategy failed, fallback to system toast.", e)
            }
            fallbackToSystemToast(request, listener, "Exception: ${e.message}")
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
    private fun fallbackToSystemToast(
        request: ToastRequest,
        listener: ToastPresentationListener,
        reason: String
    ) {
        if (KToast.debugMode) {
            Log.w("KToast", "Fallback to SystemToastStrategy. Reason: $reason")
        }
        removeCurrentView()
        currentRequest = null
        this.listener = null
        listener.onFallbackToSystem(request)
    }

    /**
     * 动态构建气泡 View (LinearLayout + ImageView + TextView)
     */
    private fun createToastView(context: Context, message: CharSequence, config: KToastConfig): View {
        val baseView = config.contentFactory?.create(context, message, config)
            ?: createBubbleView(context, message, config)
        applyCommonViewConfig(baseView, message, config)
        return baseView
    }

    private fun createBubbleView(context: Context, message: CharSequence, config: KToastConfig): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            background = GradientDrawable().apply {
                setColor(config.backgroundColor)
                cornerRadius = KToastUtils.dp2px(config.backgroundRadius).toFloat()
                if (config.borderColor != null && config.borderWidth > 0) {
                    setStroke(KToastUtils.dp2px(config.borderWidth.toFloat()), config.borderColor!!)
                }
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
                maxWidth = (context.resources.displayMetrics.widthPixels * config.maxWidthRatio).toInt()
                maxLines = config.maxLines
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(textView)
        }
    }

    private fun applyCommonViewConfig(view: View, message: CharSequence, config: KToastConfig) {
        view.minimumWidth = KToastUtils.dp2px(config.minWidth.toFloat())
        view.minimumHeight = KToastUtils.dp2px(config.minHeight.toFloat())
        view.contentDescription = config.contentDescription ?: message
        view.elevation = KToastUtils.dp2px(4f).toFloat()
        view.alpha = 0f
        view.scaleX = 0.8f
        view.scaleY = 0.8f
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

    override fun cancel(reason: ToastHandle.DismissReason) {
        removeDismissTimer()
        removeCurrentView()
        notifyDismiss(reason)
    }

    private fun removeCurrentView() {
        val view = viewReference?.get()
        val wm = windowManagerReference?.get()

        try {
            view?.animate()?.cancel()
            if (view != null && wm != null && view.isAttachedToWindow) {
                wm.removeViewImmediate(view)
            }
        } catch (e: Exception) {
            if (KToast.debugMode) e.printStackTrace()
        } finally {
            view?.animate()?.setListener(null)
            viewReference = null
            windowManagerReference = null
            dismissRunnable = null
        }
    }

    private fun notifyDismiss(reason: ToastHandle.DismissReason) {
        if (dismissNotified) return
        val handle = currentRequest?.handle ?: return
        dismissNotified = true
        currentRequest = null
        val currentListener = listener
        listener = null
        currentListener?.onDismiss(handle, reason)
    }
}
