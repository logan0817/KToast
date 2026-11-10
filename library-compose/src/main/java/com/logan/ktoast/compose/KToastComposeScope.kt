package com.logan.ktoast.compose

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.logan.ktoast.KToastScopeOptions
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.lifecycle.KToastLifecycleScope
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastContentFactory
import com.logan.ktoast.runtime.ToastHandle
import java.lang.ref.WeakReference

/**
 * Composition 所拥有的页面提示入口，仍使用核心唯一队列。
 * 离开 Composition、Owner 销毁或显式 close 后永久关闭，后续新调用抛 IllegalStateException；
 * 已提交请求仍按核心契约取消。此点不同于关闭后仍返回取消句柄的 KToastLifecycleScope。
 * show/showStyled/showDelayed/showContent/cancel/close 可跨线程调用；局部配置在调用线程，内容和回调在主线程。
 */
class KToastComposeScope private constructor() : AutoCloseable {
    private val lock = Any()
    @Volatile private var binding: ComposeBinding? = null
    @Volatile private var closed = false
    private var unavailableReason = "KToast Compose scope is not bound yet. Call it from an event or a committed effect."

    val isClosed: Boolean get() = closed || binding?.delegate?.isClosed == true

    fun show(
        message: CharSequence,
        duration: Int = Toast.LENGTH_SHORT,
        behavior: KToastBehavior = KToastBehavior(),
        callbacks: KToastCallbacks = KToastCallbacks(),
        localConfig: (KToastConfig.() -> Unit)? = null
    ): ToastHandle = current().delegateOrThrow().show(message, duration, behavior, callbacks, localConfig)

    fun showStyled(
        message: CharSequence,
        style: KToastStyle,
        duration: Int = Toast.LENGTH_SHORT,
        behavior: KToastBehavior = KToastBehavior(),
        callbacks: KToastCallbacks = KToastCallbacks(),
        localConfig: (KToastConfig.() -> Unit)? = null
    ): ToastHandle = current().delegateOrThrow().showStyled(message, style, duration, behavior, callbacks, localConfig)

    fun showDelayed(
        message: CharSequence,
        delayMillis: Long,
        duration: Int = Toast.LENGTH_SHORT,
        behavior: KToastBehavior = KToastBehavior(),
        callbacks: KToastCallbacks = KToastCallbacks(),
        localConfig: (KToastConfig.() -> Unit)? = null
    ): ToastHandle = current().delegateOrThrow().showDelayed(message, delayMillis, duration, behavior, callbacks, localConfig)

    /**
     * 仅在实际展示时创建子 Composition，继承调用处的 CompositionLocal（包括应用主题）。
     * description 必须非空白，用作核心消息、去重依据和窗口无障碍描述；本方法拥有 contentFactory。
     * 工厂准备或窗口挂载失败沿核心错误路径清理；用户内容中的首次组合和后续重组异常遵循应用 Compose 异常机制。
     */
    fun showContent(
        description: CharSequence,
        duration: Int = Toast.LENGTH_SHORT,
        behavior: KToastBehavior = KToastBehavior(),
        callbacks: KToastCallbacks = KToastCallbacks(),
        localConfig: (KToastConfig.() -> Unit)? = null,
        content: @Composable () -> Unit
    ): ToastHandle {
        require(description.isNotBlank()) { "Compose toast description must not be blank." }
        val descriptionText = description.toString()
        val current = current()
        val pending = ComposeToastContent(WeakReference(current), content)
        val onCancel = callbacks.onCancel
        val onDismiss = callbacks.onDismiss
        val managedCallbacks = KToastCallbacks(
            onShow = callbacks.onShow,
            onFallbackToSystem = callbacks.onFallbackToSystem,
            onCancel = { handle ->
                try { pending.close() } finally { onCancel?.invoke(handle) }
            },
            onDismiss = { handle, reason ->
                try { pending.close() } finally { onDismiss?.invoke(handle, reason) }
            }
        )
        return try {
            current.delegateOrThrow().show(descriptionText, duration, behavior, managedCallbacks) {
                localConfig?.invoke(this)
                contentDescription = descriptionText
                contentFactory = KToastContentFactory { context, _, _ -> pending.create(context) }
            }
        } catch (error: Throwable) {
            pending.closeAfterFailure(error)
            throw error
        }
    }

    fun cancel() = current().delegateOrThrow().cancel()

    /** 可重复调用；关闭后不保留宿主、Owner 或父 Composition。 */
    override fun close() {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true
            unavailableReason = "KToast Compose scope is closed. Use the scope of the current composition."
            binding.also { binding = null }
        }
        previous?.close()
    }

    internal fun bind(environment: ComposeEnvironment) {
        check(environment.savedStateOwner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            "Cannot bind a destroyed SavedStateRegistryOwner."
        }
        val next = ComposeBinding(environment, ::close)
        val discarded = synchronized(lock) {
            if (closed) true else { binding = next; false }
        }
        if (discarded) next.close()
    }

    internal fun disablePreview() {
        synchronized(lock) {
            closed = true
            unavailableReason = "KToast display is disabled in Preview. Preview KToastContent directly."
        }
    }

    private fun current(): ComposeBinding = synchronized(lock) {
        checkNotNull(binding?.takeUnless { closed }) { unavailableReason }
    }

    companion object {
        internal fun create(): KToastComposeScope = KToastComposeScope()
    }
}

/**
 * 在 Composition 提交后绑定真实 ComponentActivity、LifecycleOwner 和 SavedStateRegistryOwner。
 * 普通重组复用入口，任一绑定对象改变即关闭旧入口；Preview 不绑定窗口，显示操作明确抛异常。
 */
@Composable
fun rememberKToastScope(options: KToastScopeOptions = KToastScopeOptions()): KToastComposeScope {
    val context = LocalContext.current
    val host = context.componentActivity()
    val owner = LocalLifecycleOwner.current
    val savedStateOwner = LocalSavedStateRegistryOwner.current
    val parentComposition = rememberCompositionContext()
    val inspection = LocalInspectionMode.current
    val scope = remember(host, owner, savedStateOwner, parentComposition, options, inspection) { KToastComposeScope.create() }
    DisposableEffect(scope) {
        if (inspection) scope.disablePreview()
        else {
            checkNotNull(host) { "KToast Compose requires a ComponentActivity host." }
            scope.bind(ComposeEnvironment(host, owner, savedStateOwner, parentComposition, options))
        }
        onDispose { scope.close() }
    }
    return scope
}

internal data class ComposeEnvironment(
    val host: ComponentActivity,
    val owner: LifecycleOwner,
    val savedStateOwner: SavedStateRegistryOwner,
    val parentComposition: CompositionContext,
    val options: KToastScopeOptions
)

internal class ComposeBinding(environment: ComposeEnvironment, onDestroyed: () -> Unit) : AutoCloseable {
    @Volatile var environment: ComposeEnvironment? = environment
        private set
    @Volatile var delegate: KToastLifecycleScope? = KToastLifecycleScope.create(environment.host, environment.owner, environment.options)
        private set
    private val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = onDestroyed()
    }

    init {
        environment.owner.lifecycle.addObserver(observer)
        if (environment.savedStateOwner !== environment.owner) environment.savedStateOwner.lifecycle.addObserver(observer)
    }

    fun delegateOrThrow(): KToastLifecycleScope = checkNotNull(delegate?.takeUnless { it.isClosed }) {
        "KToast Compose scope is closed."
    }

    override fun close() {
        val oldEnvironment = environment
        environment = null
        val oldDelegate = delegate
        delegate = null
        try { oldDelegate?.close() } finally {
            if (oldEnvironment != null) onMain {
                oldEnvironment.owner.lifecycle.removeObserver(observer)
                if (oldEnvironment.savedStateOwner !== oldEnvironment.owner) oldEnvironment.savedStateOwner.lifecycle.removeObserver(observer)
            }
        }
    }
}

/** 工厂只弱持有绑定；未展示取消时不创建 View 或 Composition，终态释放调用方内容。 */
private class ComposeToastContent(
    private val binding: WeakReference<ComposeBinding>,
    content: @Composable () -> Unit
) : AutoCloseable {
    private val lock = Any()
    private var content: (@Composable () -> Unit)? = content
    private var view: ComposeView? = null

    fun create(context: Context): View {
        val environment = checkNotNull(binding.get()?.environment) { "Compose toast owner is no longer available." }
        synchronized(lock) { checkNotNull(content) { "Compose toast content was already released." } }
        val next = ComposeView(context)
        synchronized(lock) { view = next }
        try {
            // 同一真实 SavedStateOwner 下可同时存在页面 ComposeView；短暂提示不恢复旧进程内容。
            next.id = View.generateViewId()
            next.setViewTreeLifecycleOwner(environment.owner)
            next.setViewTreeSavedStateRegistryOwner(environment.savedStateOwner)
            next.setParentCompositionContext(environment.parentComposition)
            next.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            next.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {}
                override fun onViewDetachedFromWindow(view: View) {
                    view.removeOnAttachStateChangeListener(this)
                    close()
                }
            })
            next.setContent {
                val currentContent = synchronized(lock) { content }
                currentContent?.invoke()
            }
            return next
        } catch (error: Throwable) {
            closeAfterFailure(error)
            throw error
        }
    }

    override fun close() {
        val previous = synchronized(lock) {
            content = null
            binding.clear()
            view.also { view = null }
        }
        if (previous != null) onMain {
            try { previous.disposeComposition() }
            finally {
                previous.setParentCompositionContext(null)
                previous.setViewTreeLifecycleOwner(null)
                previous.setViewTreeSavedStateRegistryOwner(null)
            }
        }
    }
}

private fun Context.componentActivity(): ComponentActivity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        val base = current.baseContext
        if (base === current) break
        current = base
    }
    return current as? ComponentActivity
}

private val mainHandler = Handler(Looper.getMainLooper())

private fun onMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
}

private fun AutoCloseable.closeAfterFailure(error: Throwable) {
    try { close() } catch (cleanupError: Throwable) { error.addSuppressed(cleanupError) }
}
