package com.logan.ktoastapp.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoast.KToastScope
import com.logan.ktoast.KToastScopeOptions
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastContentFactory
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoastapp.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.hamcrest.Matchers.sameInstance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class KToastPlatformTest {
    val rule = activityScenarioRule<MainActivity>()
    @get:Rule val rules = RuleChain.outerRule(rule).around(WindowFailureDiagnosticsRule())
    private var scope: KToastScope? = null

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { scope?.close() }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun imeAvoidancePlacesEntireContentAboveVisibleKeyboard() {
        val imeVisible = CountDownLatch(1)
        lateinit var editor: EditText
        lateinit var content: View
        lateinit var observer: ViewTreeObserver.OnPreDrawListener
        val imeState = AtomicReference("No editor frame")
        rule.scenario.onActivity { host ->
            editor = EditText(host)
            host.setContentView(FrameLayout(host).apply { addView(editor) })
            editor.requestFocus()
            observer = ViewTreeObserver.OnPreDrawListener {
                val keyboard = editor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
                imeState.set("attached=${editor.isAttachedToWindow} windowFocus=${editor.hasWindowFocus()} editorFocus=${editor.hasFocus()} ime=$keyboard")
                if (keyboard > 0) {
                    imeVisible.countDown()
                }
                true
            }
            editor.viewTreeObserver.addOnPreDrawListener(observer)
        }
        val visible = try {
            // ActivityScenario 的 RESUMED 不保证窗口已有焦点；过早 show 会被系统取消。
            // Espresso 等待可交互窗口后点击；点击本身不保证系统发起 IME 显示请求。
            onView(sameInstance(editor)).perform(click())
            rule.scenario.onActivity { host ->
                assertTrue("IME fixture requires an attached, focused editor",
                    editor.isAttachedToWindow && editor.hasWindowFocus() && editor.hasFocus())
                host.window.insetsController!!.show(WindowInsets.Type.ime())
            }
            imeVisible.await(5L, TimeUnit.SECONDS)
        } finally {
            rule.scenario.onActivity {
                val keyboard = editor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
                imeState.set("${imeState.get()}; finalAttached=${editor.isAttachedToWindow} finalWindowFocus=${editor.hasWindowFocus()} finalEditorFocus=${editor.hasFocus()} finalIme=$keyboard")
                editor.viewTreeObserver.removeOnPreDrawListener(observer)
            }
        }
        assertTrue("IME did not become visible: ${imeState.get()}", visible)
        rule.scenario.onActivity { host ->
            scope = KToastScope.create(host, KToastScopeOptions(avoidIme = true))
            scope!!.show("above IME", Toast.LENGTH_LONG) {
                animationDuration = 0L
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                yOffset = 0
                contentFactory = KToastContentFactory { context, message, _ ->
                    TextView(context).apply { text = message }.also { content = it }
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        rule.scenario.onActivity { host ->
            val root = content.rootView
            assertTrue(root.isAttachedToWindow)
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            val keyboard = host.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
            assertTrue("IME disappeared before content geometry was checked", keyboard > 0)
            val bottom = host.windowManager.currentWindowMetrics.bounds.bottom
            assertTrue("Content overlaps IME", location[1] + root.height <= bottom - keyboard)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun recommendedTimeoutKeepsScopeVisibleBeyondLegacyShortDuration() {
        // 仅在显式配置的专用模拟器上执行；不修改使用方手机的无障碍设置。
        assumeTrue(InstrumentationRegistry.getArguments().getString("accessibilityTimeoutFixture") == "true")
        val dismissed = CountDownLatch(1)
        lateinit var handle: ToastHandle
        rule.scenario.onActivity { host ->
            val manager = host.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            assertTrue(manager.getRecommendedTimeoutMillis(2000, AccessibilityManager.FLAG_CONTENT_TEXT) >= 5000)
            scope = KToastScope.create(host)
            handle = scope!!.show("recommended timeout", callbacks = KToastCallbacks(
                onDismiss = { _, _ -> dismissed.countDown() }
            )) { animationDuration = 0L }
        }
        assertFalse("Scope ignored recommended timeout", dismissed.await(2500L, TimeUnit.MILLISECONDS))
        rule.scenario.onActivity {
            assertEquals(ToastHandle.State.SHOWING, handle.state)
            assertTrue(handle.cancel())
            assertEquals(0L, dismissed.count)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun customButtonUsesInteractiveRecommendedTimeout_withoutBubbleCancel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("interactiveTimeoutFixture") == "true")
        val dismissed = CountDownLatch(1)
        lateinit var handle: ToastHandle
        lateinit var button: Button
        var clicks = 0
        rule.scenario.onActivity { host ->
            val manager = host.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            // 只延长交互提示，确保普通文字时长不会掩盖控件标记缺失。
            assertEquals(2000, manager.getRecommendedTimeoutMillis(2000, AccessibilityManager.FLAG_CONTENT_TEXT))
            assertTrue(manager.getRecommendedTimeoutMillis(2000, AccessibilityManager.FLAG_CONTENT_CONTROLS) >= 5000)
            scope = KToastScope.create(host)
            handle = scope!!.show("custom action", callbacks = KToastCallbacks(
                onDismiss = { _, _ -> dismissed.countDown() }
            )) {
                animationDuration = 0L
                cancelOnTouch = false
                contentFactory = KToastContentFactory { context, _, _ ->
                    FrameLayout(context).apply {
                        addView(Button(context).apply {
                            text = "操作"
                            setOnClickListener { clicks++ }
                        }.also { button = it })
                    }
                }
            }
        }
        assertFalse("Custom button ignored interactive timeout", dismissed.await(2500L, TimeUnit.MILLISECONDS))
        rule.scenario.onActivity {
            assertEquals(ToastHandle.State.SHOWING, handle.state)
            assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(1, clicks)
            assertFalse(handle.isFinished)
            assertTrue(handle.cancel())
            assertEquals(0L, dismissed.count)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun customPlainTextDoesNotUseInteractiveTimeout() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("interactiveTimeoutFixture") == "true")
        val dismissed = CountDownLatch(1)
        lateinit var handle: ToastHandle
        rule.scenario.onActivity { host ->
            val manager = host.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            assertEquals(2000, manager.getRecommendedTimeoutMillis(2000, AccessibilityManager.FLAG_CONTENT_TEXT))
            assertTrue(manager.getRecommendedTimeoutMillis(2000, AccessibilityManager.FLAG_CONTENT_CONTROLS) >= 5000)
            scope = KToastScope.create(host)
            handle = scope!!.show("plain custom", callbacks = KToastCallbacks(
                onDismiss = { _, _ -> dismissed.countDown() }
            )) {
                animationDuration = 0L
                cancelOnTouch = false
                contentFactory = KToastContentFactory { context, message, _ ->
                    TextView(context).apply { text = message }
                }
            }
        }
        assertTrue("Plain text incorrectly used interactive timeout", dismissed.await(5L, TimeUnit.SECONDS))
        assertEquals(ToastHandle.DismissReason.COMPLETED, handle.dismissReason)
    }
}
