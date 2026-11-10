package com.logan.ktoastapp.app

import android.content.res.Configuration
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoast.KToast
import com.logan.ktoast.KToastScope
import com.logan.ktoast.KToastScopeOptions
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.config.KToastAnimation
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType
import com.logan.ktoast.lifecycle.KToastLifecycleScope
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastContentFactory
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.runtime.ToastDropReason
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoastapp.LifecycleDemoActivity
import com.logan.ktoastapp.MainActivity
import com.logan.ktoastapp.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class KToastStyleTest {
    @get:Rule val rule = activityScenarioRule<MainActivity>()
    private val closeables = mutableListOf<AutoCloseable>()

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            closeables.forEach { it.close() }
            KToast.cancel()
        }
    }

    @Test fun xmlResolvesDimensionsThemeAndNightWithoutChangingBase() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val base = KToastConfig(textColor = 0xFF010203.toInt(), paddingHorizontal = 42, icon = 123)
        val original = base.copy()
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val configuration = Configuration(target.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                fontScale = 2f
            }
            val context = ContextThemeWrapper(target.createConfigurationContext(configuration), R.style.KToastTestHostTheme)
            val style = KToastStyle(KToastType.SUCCESS, R.style.KToastTestBrand)
            val resolved = style.resolve(context, base)
            assertNotSame(base, resolved)
            assertEquals(original, base)
            assertEquals(KToastStyle(KToastType.SUCCESS, R.style.KToastTestBrand), style)
            assertEquals(if (night == Configuration.UI_MODE_NIGHT_YES) 0xFFDDEEFF.toInt() else 0xFF203040.toInt(), resolved.backgroundColor)
            assertEquals(0xFFFFFFFF.toInt(), resolved.textColor)
            assertEquals(13f, resolved.backgroundRadius, 0.01f)
            assertEquals(17f, resolved.textSize, 0.01f)
            assertEquals(19, resolved.paddingHorizontal)
            assertEquals(7, resolved.paddingVertical)
            assertEquals(com.logan.ktoast.R.drawable.ktoast_style_info, resolved.icon)
            assertEquals(0xFFFFE082.toInt(), resolved.iconColor)
            assertEquals(21f, resolved.iconSize, 0.01f)
            assertEquals(6, resolved.iconPadding)
            assertEquals(3, resolved.maxLines)
            assertEquals(0.65f, resolved.maxWidthRatio, 0.001f)
            assertEquals(33, resolved.minWidth)
            assertEquals(22, resolved.minHeight)
            assertEquals(0xFFFFE082.toInt(), resolved.borderColor)
            assertEquals(2, resolved.borderWidth)
            assertEquals(Gravity.TOP or Gravity.START, resolved.gravity)
            assertEquals((5f * context.resources.displayMetrics.density).toInt(), resolved.xOffset)
            assertEquals((9f * context.resources.displayMetrics.density).toInt(), resolved.yOffset)
            val themed = KToastStyle(KToastType.ERROR, R.style.KToastTestThemeBrand).resolve(context, base)
            assertEquals(0xFFFDF4DF.toInt(), themed.textColor)
            assertNull(themed.icon)
            val pixels = KToastStyle(styleRes = R.style.KToastTestPixels).resolve(context)
            assertEquals(42f, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, pixels.textSize,
                context.resources.displayMetrics), 0.1f)
        }
    }

    @Test fun styledOrderUsesBaseHostSemanticBrandThenLocalForAllEntrypoints() {
        rule.scenario.onActivity { host ->
            KToast.cancel()
            var original: KToastConfig? = null
            KToast.showHandle("capture base") { original = snapshot() }.cancel()
            val previous = original!!
            val previousTheme = host.resources.newTheme().apply { setTo(host.theme) }
            val globalAnimation = KToastAnimation(maxScale = 1.1f)
            try {
                KToast.config {
                    backgroundColor = 0xFF010203.toInt()
                    textColor = 0xFF040506.toInt()
                    animation = globalAnimation
                }
                host.theme.applyStyle(R.style.KToastTestHostTheme, true)
                val plain = KToastScope.create(host).also { closeables.add(it) }
                val themed = KToastScope.create(host, KToastScopeOptions(followHostTheme = true)).also { closeables.add(it) }
                val lifecycle = KToastLifecycleScope.create(host, host, KToastScopeOptions(followHostTheme = true)).also { closeables.add(it) }
                fun capture(scope: KToastScope, style: KToastStyle): KToastConfig {
                    var result: KToastConfig? = null
                    scope.showStyled("capture", style) { result = snapshot(); animationDuration = 0L }.cancel()
                    return result!!
                }
                val plainConfig = capture(plain, KToastStyle())
                assertEquals(0xFF010203.toInt(), plainConfig.backgroundColor)
                assertEquals(0xFF040506.toInt(), plainConfig.textColor)
                assertSame(globalAnimation, plainConfig.animation)
                val hostConfig = capture(themed, KToastStyle())
                assertEquals(0xFF112233.toInt(), hostConfig.backgroundColor)
                assertEquals(0xFFFDF4DF.toInt(), hostConfig.textColor)
                val semanticConfig = capture(themed, KToastStyle(KToastType.SUCCESS))
                val semanticExpected = KToastStyle(KToastType.SUCCESS).resolve(host)
                assertEquals(semanticExpected.backgroundColor, semanticConfig.backgroundColor)
                assertEquals(semanticExpected.textColor, semanticConfig.textColor)
                val style = KToastStyle(KToastType.SUCCESS, R.style.KToastTestBrand)
                val expected = style.resolve(host)
                val configs = mutableListOf<KToastConfig>()
                val customize: KToastConfig.() -> Unit = {
                    assertEquals(expected.backgroundColor, backgroundColor)
                    assertEquals(expected.textColor, textColor)
                    assertSame(globalAnimation, animation)
                    backgroundColor = 0xFF334455.toInt()
                    animationDuration = 0L
                    configs.add(copy())
                }
                KToast.showStyled("global", style, localConfig = customize).cancel()
                themed.showStyled("activity", style, localConfig = customize).cancel()
                lifecycle.showStyled("owner", style, localConfig = customize).cancel()
                assertEquals(3, configs.size)
                assertTrue(configs.all { it.backgroundColor == 0xFF334455.toInt() && it.textColor == expected.textColor })
                var unchanged: KToastConfig? = null
                plain.show("ordinary") { unchanged = copy() }.cancel()
                assertEquals(0xFF010203.toInt(), unchanged!!.backgroundColor)
                assertEquals(0xFF040506.toInt(), unchanged!!.textColor)
            } finally {
                host.theme.setTo(previousTheme)
                KToast.config {
                    backgroundColor = previous.backgroundColor
                    textColor = previous.textColor
                    animation = previous.animation
                }
            }
        }
    }

    @Test fun workerStyledRequestsPrepareOnCallerAndNotifyOnMain() {
        lateinit var scope: KToastScope
        lateinit var lifecycle: KToastLifecycleScope
        rule.scenario.onActivity { host ->
            KToast.cancel()
            scope = KToastScope.create(host).also { closeables.add(it) }
            lifecycle = KToastLifecycleScope.create(host, host).also { closeables.add(it) }
        }
        val failure = AtomicReference<Throwable?>()
        val shown = CountDownLatch(3)
        val callbacks = KToastCallbacks(onShow = {
            try { assertSame(Looper.getMainLooper(), Looper.myLooper()); it.cancel() }
            catch (error: Throwable) { failure.set(error) }
            finally { shown.countDown() }
        })
        val worker = Thread {
            try {
                val style = KToastStyle(KToastType.INFO, R.style.KToastTestBrand)
                val local: KToastConfig.() -> Unit = { assertNotSame(Looper.getMainLooper(), Looper.myLooper()); animationDuration = 0L }
                KToast.showStyled("worker global", style, callbacks = callbacks, localConfig = local)
                scope.showStyled("worker activity", style, callbacks = callbacks, localConfig = local)
                lifecycle.showStyled("worker owner", style, callbacks = callbacks, localConfig = local)
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        worker.join(5_000L)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertTrue(shown.await(5L, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
    }

    @Test fun queuedStyledRequestFreezesTheConfigurationExposedToDsl() {
        rule.scenario.onActivity { host ->
            KToast.cancel()
            val scope = KToastScope.create(host).also { closeables.add(it) }
            val style = KToastStyle(KToastType.ERROR, R.style.KToastTestBrand)
            val expected = style.resolve(host)
            val rendered = mutableListOf<KToastConfig>()
            val requestedAnimation = KToastAnimation(maxScale = 1.2f)
            lateinit var prepared: KToastConfig
            val blocker = scope.show("blocker", Toast.LENGTH_LONG) { animationDuration = 0L }
            val queued = scope.showStyled("queued", style, behavior = KToastBehavior(KToastDisplayMode.QUEUE)) {
                prepared = this
                animationDuration = 0L
                animation = requestedAnimation
                contentFactory = KToastContentFactory { context, message, config ->
                    rendered.add(config.snapshot())
                    TextView(context).apply { text = message }
                }
            }
            assertEquals(ToastHandle.State.PENDING, queued.state)
            prepared.backgroundColor = 0xFF000001.toInt()
            prepared.textColor = 0xFF000002.toInt()
            prepared.paddingHorizontal = 999
            prepared.animation = KToastAnimation(maxScale = 1.5f)
            blocker.cancel()
            assertEquals(ToastHandle.State.SHOWING, queued.state)
            assertEquals(1, rendered.size)
            assertEquals(expected.backgroundColor, rendered.single().backgroundColor)
            assertEquals(expected.textColor, rendered.single().textColor)
            assertEquals(expected.paddingHorizontal, rendered.single().paddingHorizontal)
            assertSame(requestedAnimation, rendered.single().animation)
            assertEquals(KToastStyle(KToastType.ERROR, R.style.KToastTestBrand), style)
            queued.cancel()
            val next = scope.showStyled("same style", style) { assertEquals(expected.backgroundColor, backgroundColor) }
            assertEquals(ToastHandle.State.SHOWING, next.state)
        }
    }

    @Test fun fullCapacitySkipsInvalidXmlAndResolverFailureReturnsItsSlot() {
        ActivityScenario.launch(LifecycleDemoActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                KToast.cancel()
                val owner = host.page.toastScope
                val scope = KToastScope.create(host).also { closeables.add(it) }
                val pending = (1..32).map { owner.showDelayed("pending $it", 60_000L) }
                val invalid = KToastStyle(KToastType.SUCCESS, R.string.app_name)
                var dsl = 0
                var dismissed = 0
                val callbacks = KToastCallbacks(onDismiss = { handle, _ ->
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    assertEquals(ToastDropReason.CAPACITY_EXCEEDED, handle.dropReason)
                    dismissed++
                })
                val rejected = listOf(
                    KToast.showStyled("global rejected", invalid, callbacks = callbacks) { dsl++ },
                    scope.showStyled("activity rejected", invalid, callbacks = callbacks) { dsl++ },
                    owner.showStyled("owner rejected", invalid, callbacks = callbacks) { dsl++ }
                )
                assertTrue(rejected.all { it.isCanceled && it.dropReason == ToastDropReason.CAPACITY_EXCEEDED })
                assertEquals(0, dsl)
                assertEquals(3, dismissed)
                owner.cancel()
                assertTrue(pending.all { it.isCanceled })
                try {
                    owner.showStyled("invalid accepted", invalid)
                    fail("Wrong resource type must propagate")
                } catch (_: IllegalArgumentException) {}
                val refill = (1..32).map { owner.showDelayed("refill $it", 60_000L) }
                assertTrue(refill.all { !it.isFinished })
                assertEquals(ToastDropReason.CAPACITY_EXCEEDED, owner.showStyled("full again", KToastStyle()).dropReason)
                owner.cancel()
            }
        }
    }

    @Test fun contentDetachFailureStillRemovesRootWindowAndPromotesQueue() {
        val previousDebugMode = KToast.debugMode
        val failures = AtomicInteger()
        val dismissed = AtomicInteger()
        lateinit var content: View
        lateinit var successor: View
        lateinit var oldRoot: View
        lateinit var active: ToastHandle
        lateinit var queued: ToastHandle
        try {
            rule.scenario.onActivity { host ->
                KToast.cancel()
                KToast.setDebugMode(true)
                val scope = KToastScope.create(host).also { closeables.add(it) }
                active = scope.show("controlled detach failure", Toast.LENGTH_LONG,
                    callbacks = KToastCallbacks(onDismiss = { _, _ -> dismissed.incrementAndGet() })) {
                    animationDuration = 0L
                    contentFactory = KToastContentFactory { context, message, _ ->
                        TextView(context).apply {
                            text = message
                            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(view: View) {}
                                override fun onViewDetachedFromWindow(view: View) {
                                    view.removeOnAttachStateChangeListener(this)
                                    failures.incrementAndGet()
                                    throw IllegalStateException("intentional toast content detach failure")
                                }
                            })
                        }.also { content = it }
                    }
                }
                queued = scope.show("queue after detach failure", Toast.LENGTH_LONG,
                    behavior = KToastBehavior(KToastDisplayMode.QUEUE)) {
                    animationDuration = 0L
                    contentFactory = KToastContentFactory { context, message, _ ->
                        TextView(context).apply { text = message }.also { successor = it }
                    }
                }
                assertEquals(ToastHandle.State.PENDING, queued.state)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            rule.scenario.onActivity { host ->
                oldRoot = content.rootView
                assertNotSame(host.window.decorView.rootView, oldRoot)
                assertTrue(oldRoot.isAttachedToWindow)
                assertTrue(active.cancel())
                assertEquals(1, failures.get())
                assertEquals(1, dismissed.get())
                assertTrue(active.isCanceled)
                assertFalse(oldRoot.isAttachedToWindow)
                assertEquals(ToastHandle.State.SHOWING, queued.state)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            rule.scenario.onActivity {
                assertFalse(oldRoot.isAttachedToWindow)
                assertTrue(successor.isAttachedToWindow)
                assertEquals(ToastHandle.State.SHOWING, queued.state)
                assertFalse(active.cancel())
                assertEquals(1, dismissed.get())
                queued.cancel()
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                KToast.cancel()
                KToast.setDebugMode(previousDebugMode)
            }
        }
    }
}
