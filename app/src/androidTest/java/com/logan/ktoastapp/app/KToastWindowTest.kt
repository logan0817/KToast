package com.logan.ktoastapp.app

import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
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
import com.logan.ktoast.runtime.KToastContentFactory
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoastapp.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.sameInstance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class KToastWindowTest {
    @get:Rule val rule = activityScenarioRule<MainActivity>()
    private var scope: KToastScope? = null

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { scope?.close() }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun relativeGravityFollowsHostDirectionWhileShowing() {
        lateinit var content: View
        lateinit var handle: ToastHandle
        rule.scenario.onActivity { host ->
            host.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
            scope = KToastScope.create(host)
            handle = scope!!.show("direction", Toast.LENGTH_LONG) {
                animationDuration = 0L
                gravity = Gravity.START or Gravity.TOP
                xOffset = 0
                yOffset = 0
                contentFactory = KToastContentFactory { context, message, _ ->
                    TextView(context).apply { text = message }.also { content = it }
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val moved = CountDownLatch(1)
        lateinit var observer: ViewTreeObserver.OnPreDrawListener
        rule.scenario.onActivity { host ->
            val decor = host.window.decorView
            val root = content.rootView
            val safe = decor.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val bounds = host.windowManager.currentWindowMetrics.bounds
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            assertTrue(root.isAttachedToWindow)
            assertEquals(bounds.right - safe.right, location[0] + root.width)
            assertEquals(bounds.top + safe.top, location[1])
            observer = ViewTreeObserver.OnPreDrawListener {
                root.getLocationOnScreen(location)
                if (root.isAttachedToWindow && location[0] == bounds.left + safe.left) moved.countDown()
                true
            }
            root.viewTreeObserver.addOnPreDrawListener(observer)
            decor.layoutDirection = View.LAYOUT_DIRECTION_LTR
            decor.requestLayout()
            decor.invalidate()
        }
        assertTrue("Showing START toast did not follow the new host direction", moved.await(3L, TimeUnit.SECONDS))
        rule.scenario.onActivity {
            content.rootView.viewTreeObserver.removeOnPreDrawListener(observer)
            assertEquals(ToastHandle.State.SHOWING, handle.state)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun cutoutFixtureKeepsEntireContentInsideSafeArea() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("displayCutoutFixture") == "true"
        )
        lateinit var content: View
        rule.scenario.onActivity { host ->
            val cutout = host.window.decorView.rootWindowInsets.displayCutout
            assertNotNull("Dedicated cutout fixture is missing", cutout)
            assertTrue(cutout!!.safeInsetTop > 0)
            scope = KToastScope.create(host)
            scope!!.show("cutout", Toast.LENGTH_LONG) {
                gravity = Gravity.TOP or Gravity.START
                xOffset = Int.MIN_VALUE
                yOffset = Int.MIN_VALUE
                animationDuration = 0L
                contentFactory = KToastContentFactory { context, message, _ ->
                    TextView(context).apply { text = message }.also { content = it }
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        rule.scenario.onActivity { host ->
            val root = content.rootView
            val bounds = host.windowManager.currentWindowMetrics.bounds
            val safe = host.window.decorView.rootWindowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            assertTrue(root.isAttachedToWindow)
            assertEquals(bounds.left + safe.left, location[0])
            assertEquals(bounds.top + safe.top, location[1])
            assertTrue(location[0] + root.width <= bounds.right - safe.right)
            assertTrue(location[1] + root.height <= bounds.bottom - safe.bottom)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun showingToastMovesWhenKeyboardAppears() {
        lateinit var content: View
        lateinit var editor: EditText
        lateinit var handle: ToastHandle
        val moved = CountDownLatch(1)
        val position = AtomicReference("No post-IME frame")
        lateinit var observer: ViewTreeObserver.OnPreDrawListener
        rule.scenario.onActivity { host ->
            editor = EditText(host)
            host.setContentView(FrameLayout(host).apply { addView(editor) })
            editor.requestFocus()
            scope = KToastScope.create(host, KToastScopeOptions(avoidIme = true))
            handle = scope!!.show("before IME", Toast.LENGTH_LONG) {
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
            val decor = host.window.decorView
            val root = content.rootView
            assertTrue(root.isAttachedToWindow)
            assertEquals(0, decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom)
            val before = IntArray(2)
            root.getLocationOnScreen(before)
            observer = ViewTreeObserver.OnPreDrawListener {
                val keyboard = decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
                val now = IntArray(2)
                root.getLocationOnScreen(now)
                position.set("ime=$keyboard beforeY=${before[1]} nowY=${now[1]} height=${root.height} state=${handle.state}")
                if (keyboard > 0 && root.isAttachedToWindow && now[1] < before[1] &&
                    now[1] + root.height <= host.windowManager.currentWindowMetrics.bounds.bottom - keyboard) {
                    moved.countDown()
                }
                true
            }
            decor.viewTreeObserver.addOnPreDrawListener(observer)
            // 提示是独立子窗口，宿主的 pre-draw 可能早于其更新后的绘制。
            // 两个窗口都观察，仍要求实际屏幕位置移动并完整避让键盘。
            root.viewTreeObserver.addOnPreDrawListener(observer)
        }
        val changed = try {
            // 与首次避让用例使用同一真实输入操作，避免初始化与 IME 显示请求竞争。
            onView(sameInstance(editor)).perform(click())
            rule.scenario.onActivity { host ->
                assertTrue("IME fixture requires an attached, focused editor",
                    editor.isAttachedToWindow && editor.hasWindowFocus() && editor.hasFocus())
                host.window.insetsController!!.show(WindowInsets.Type.ime())
            }
            moved.await(3L, TimeUnit.SECONDS)
        } finally {
            rule.scenario.onActivity {
                val root = content.rootView
                val location = IntArray(2)
                root.getLocationOnScreen(location)
                val params = root.layoutParams as WindowManager.LayoutParams
                position.set("${position.get()}; finalIme=${it.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom} finalY=${location[1]} requestedY=${params.y} state=${handle.state}")
                it.window.decorView.viewTreeObserver.removeOnPreDrawListener(observer)
                root.viewTreeObserver.removeOnPreDrawListener(observer)
            }
        }
        assertTrue("Showing toast did not move above the IME: ${position.get()}", changed)
        assertEquals(ToastHandle.State.SHOWING, handle.state)
    }
}
