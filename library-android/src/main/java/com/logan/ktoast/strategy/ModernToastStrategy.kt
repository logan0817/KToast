package com.logan.ktoast.strategy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import android.view.accessibility.AccessibilityManager
import com.logan.ktoast.KToast
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoast.runtime.ToastRequest
import com.logan.ktoast.utils.KActivityStack
import com.logan.ktoast.utils.KToastUtils
import java.lang.ref.WeakReference
import java.util.ArrayDeque

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
 * 旧全局请求通过 [KActivityStack] 选择当前 Activity，宿主不可用或挂载失败时尝试系统降级。
 * 页面作用域只使用明确宿主，失败时取消，不转移页面或降级系统 Toast。
 * 两条路径已挂载的提示均随宿主停止、销毁或窗口脱离而取消。
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
    private var hostCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var attachListener: View.OnAttachStateChangeListener? = null
    private var windowAdded = false
    private var ownsClickListener = false
    private var exitStarted = false
    private var contentReference: WeakReference<View>? = null
    private var announceRunnable: Runnable? = null
    private var viewportListener: ViewTreeObserver.OnPreDrawListener? = null
    private var decorReference: WeakReference<View>? = null
    private var windowBounds: ToastWindowBounds? = null
    private var hostLayoutDirection: Int? = null
    private var contentLayoutListener: View.OnLayoutChangeListener? = null

    override fun show(request: ToastRequest, listener: ToastPresentationListener) {
        val config = request.config ?: KToast.globalConfig.copy()
        currentRequest = request
        this.listener = listener
        dismissNotified = false
        val activity = if (request.scope != null) request.scope.host() else KActivityStack.getTopActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed || request.scope?.canShow() == false) {
            if (request.scope != null) notifyDismiss(ToastHandle.DismissReason.CANCELED)
            else fallbackToSystemToast(request, listener, "Activity is null or finishing")
            return
        }

        try {
            val wm = activity.windowManager
            windowManagerReference = WeakReference(wm)
            val content = createToastView(activity, request.message, config, request.scope != null)
            contentReference = WeakReference(content)
            val toastView = if (request.scope != null) ToastViewport(activity).apply {
                // 公共样式已设置内容初始透明度，包装后改由根容器执行动画。
                content.alpha = 1f
                content.scaleX = 1f
                content.scaleY = 1f
                alpha = 0f
                scaleX = 0.8f
                scaleY = 0.8f
                addView(content)
            } else content
            viewReference = WeakReference(toastView)
            observeHost(activity)
            attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {}
                override fun onViewDetachedFromWindow(view: View) {
                    // 外部已在移除窗口，收尾时不要再次进入 WindowManager 的移除流程。
                    windowAdded = false
                    notifyDismiss(ToastHandle.DismissReason.CANCELED)
                }
            }.also { toastView.addOnAttachStateChangeListener(it) }

            if (config.cancelOnTouch) {
                ownsClickListener = true
                content.setOnClickListener {
                    if (exitStarted || currentRequest == null) return@setOnClickListener
                    exitStarted = true
                    removeDismissTimer()
                    executeExitAnimation(toastView, config.animationDuration) {
                        notifyDismiss(ToastHandle.DismissReason.CANCELED)
                    }
                }
            }

            val params = WindowManager.LayoutParams().apply {
                height = WindowManager.LayoutParams.WRAP_CONTENT
                width = WindowManager.LayoutParams.WRAP_CONTENT
                format = PixelFormat.TRANSLUCENT
                // Activity 子窗口无需悬浮窗权限，使用宿主 DecorView 的 Token。
                type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                if (!config.cancelOnTouch) {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                gravity = config.gravity
                x = config.xOffset
                y = config.yOffset
                token = activity.window.decorView.windowToken
            }
            if (request.scope != null) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0)
                val decor = activity.window.decorView
                decorReference = WeakReference(decor)
                viewportListener = ViewTreeObserver.OnPreDrawListener {
                    if (windowAdded && currentRequest != null) {
                        try {
                            val bounds = ToastWindowGeometry.resolve(activity, request.scope.options)
                            if (bounds != windowBounds || decor.layoutDirection != hostLayoutDirection) {
                                applyViewport(activity, toastView as ToastViewport, params, request, config, bounds)
                                wm.updateViewLayout(toastView, params)
                            }
                        } catch (error: Exception) {
                            KToast.dispatcher.onPresentationError(request.handle, error)
                            notifyDismiss(ToastHandle.DismissReason.CANCELED)
                        }
                    }
                    true
                }.also { decor.viewTreeObserver.addOnPreDrawListener(it) }
                contentLayoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (windowAdded && currentRequest != null &&
                        (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                        try {
                            applyViewport(activity, toastView as ToastViewport, params, request, config)
                            wm.updateViewLayout(toastView, params)
                        } catch (error: Exception) {
                            KToast.dispatcher.onPresentationError(request.handle, error)
                            notifyDismiss(ToastHandle.DismissReason.CANCELED)
                        }
                    }
                }.also { toastView.addOnLayoutChangeListener(it) }
                applyViewport(activity, toastView as ToastViewport, params, request, config)
            }
            wm.addView(toastView, params)
            windowAdded = true
            if (config.announceForAccessibility) {
                val description = content.contentDescription
                if (!description.isNullOrBlank()) {
                    announceRunnable = Runnable {
                        if (toastView.isAttachedToWindow && currentRequest != null) {
                            content.announceForAccessibility(description)
                        }
                    }
                    toastView.post(announceRunnable!!)
                }
            }
            executeEnterAnimation(toastView, config.animationDuration)
            val baseTime = if (request.duration == Toast.LENGTH_LONG) 3500 else 2000
            val displayTime = if (request.scope?.options?.useAccessibilityTimeout == true && Build.VERSION.SDK_INT >= 29) {
                val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val flags = accessibilityContentFlags(content, config)
                manager.getRecommendedTimeoutMillis(baseTime, flags).coerceAtLeast(baseTime).toLong()
            } else baseTime.toLong()
            dismissRunnable = Runnable {
                exitStarted = true
                val currentView = viewReference?.get()
                if (currentView != null && currentView.isAttachedToWindow) {
                    executeExitAnimation(currentView, config.animationDuration) {
                        notifyDismiss(ToastHandle.DismissReason.COMPLETED)
                    }
                } else {
                    notifyDismiss(ToastHandle.DismissReason.CANCELED)
                }
            }
            KToast.mainHandler.postDelayed(dismissRunnable!!, displayTime)
        } catch (error: Exception) {
            if (request.scope != null) {
                // 交给调度层统一结束，失败请求不发送 onShow。
                throw error
            }
            KToast.dispatcher.onPresentationError(request.handle, error)
            fallbackToSystemToast(request, listener, "Exception: ${error.message}")
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun accessibilityContentFlags(content: View, config: KToastConfig): Int {
        var flags = AccessibilityManager.FLAG_CONTENT_TEXT or
            (if (config.icon != null) AccessibilityManager.FLAG_CONTENT_ICONS else 0)
        // 自定义子控件也可能提供无障碍点击操作，不能只按气泡关闭开关判断交互。
        val remaining = ArrayDeque<View>()
        remaining.add(content)
        while (remaining.isNotEmpty()) {
            val view = remaining.removeFirst()
            if (view.visibility != View.VISIBLE) continue
            if (view is ImageView) flags = flags or AccessibilityManager.FLAG_CONTENT_ICONS
            if (view.isEnabled && (view.isClickable || view.isLongClickable)) {
                flags = flags or AccessibilityManager.FLAG_CONTENT_CONTROLS
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) remaining.addLast(view.getChildAt(index))
            }
        }
        return flags
    }

    private fun observeHost(host: Activity) {
        hostCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) {
                if (activity === host) notifyDismiss(ToastHandle.DismissReason.CANCELED)
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (activity === host) notifyDismiss(ToastHandle.DismissReason.CANCELED)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        }.also { KToast.context.registerActivityLifecycleCallbacks(it) }
    }

    // 已通过 getAbsoluteGravity 处理 RTL，这里按物理坐标选择对应系统栏边距。
    @SuppressLint("RtlHardcoded")
    private fun applyViewport(
        host: Activity,
        viewport: ToastViewport,
        params: WindowManager.LayoutParams,
        request: ToastRequest,
        config: KToastConfig,
        bounds: ToastWindowBounds = ToastWindowGeometry.resolve(host, request.scope!!.options)
    ) {
        windowBounds = bounds
        hostLayoutDirection = host.window.decorView.layoutDirection
        val ratio = config.maxWidthRatio.takeIf { it.isFinite() }?.coerceIn(0.1f, 1f) ?: 0.8f
        viewport.widthLimit = ((bounds.width - bounds.left - bounds.right) * ratio).toInt().coerceAtLeast(1)
        viewport.heightLimit = (bounds.height - bounds.top - bounds.bottom).coerceAtLeast(1)
        viewport.measure(
            View.MeasureSpec.makeMeasureSpec(viewport.widthLimit, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(viewport.heightLimit, View.MeasureSpec.AT_MOST)
        )
        val gravity = Gravity.getAbsoluteGravity(config.gravity, host.window.decorView.layoutDirection)
        val position = ToastPlacement.resolve(bounds, viewport.measuredWidth, viewport.measuredHeight, gravity, config.xOffset, config.yOffset)
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = position.first
        params.y = position.second
        viewport.requestLayout()
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
        dismissNotified = true
        currentRequest = null
        this.listener = null
        removeCurrentView()
        listener.onFallbackToSystem(request)
    }

    /**
     * 动态构建气泡 View (LinearLayout + ImageView + TextView)
     */
    private fun createToastView(context: Context, message: CharSequence, config: KToastConfig, scoped: Boolean): View {
        val baseView = config.contentFactory?.create(context, message, config)
            ?: createBubbleView(context, message, config, scoped)
        require(baseView.parent == null) { "Toast content must not already have a parent." }
        applyCommonViewConfig(baseView, message, config)
        return baseView
    }

    private fun createBubbleView(context: Context, message: CharSequence, config: KToastConfig, scoped: Boolean): View {
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
                // 页面入口由根容器统一限制整体宽度，避免原始非法比例再次把文字压为零宽。
                if (!scoped) maxWidth = (context.resources.displayMetrics.widthPixels * config.maxWidthRatio).toInt()
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
        notifyDismiss(reason)
    }

    private fun removeCurrentView() {
        removeDismissTimer()
        hostCallbacks?.let { KToast.context.unregisterActivityLifecycleCallbacks(it) }
        hostCallbacks = null
        val view = viewReference?.get()
        val content = contentReference?.get()
        val wm = windowManagerReference?.get()
        val shouldRemoveWindow = windowAdded
        windowAdded = false
        attachListener?.let { view?.removeOnAttachStateChangeListener(it) }
        attachListener = null
        contentLayoutListener?.let { view?.removeOnLayoutChangeListener(it) }
        contentLayoutListener = null
        announceRunnable?.let { view?.removeCallbacks(it) }
        announceRunnable = null
        viewportListener?.let { listener ->
            decorReference?.get()?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        viewportListener = null
        windowBounds = null
        hostLayoutDirection = null
        decorReference = null
        if (ownsClickListener) {
            content?.setOnClickListener(null)
            content?.isClickable = false
            ownsClickListener = false
        }

        try {
            // 先拆除监听器，再取消动画，防止旧动画结束回调覆盖本次取消原因。
            view?.animate()?.setListener(null)
            if (view is ToastViewport) view.removeAllViews()
            contentReference = null
            view?.animate()?.cancel()
            // addView 已注册窗口时，首轮遍历可能尚未附着 View，仍必须移除。
            if (view != null && wm != null && shouldRemoveWindow) {
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
        removeCurrentView()
        currentListener?.onDismiss(handle, reason)
    }
}

/** 限制整个内容的测量边界，包括图标、内边距和调用方的自定义 View。 */
internal class ToastViewport(context: Context) : FrameLayout(context) {
    var widthLimit = 1
    var heightLimit = 1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun constrain(spec: Int, limit: Int): Int {
            val size = if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) limit
            else MeasureSpec.getSize(spec).coerceAtMost(limit)
            return MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
        }
        super.onMeasure(constrain(widthMeasureSpec, widthLimit), constrain(heightMeasureSpec, heightLimit))
    }
}
