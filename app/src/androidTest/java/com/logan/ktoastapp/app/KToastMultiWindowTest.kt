package com.logan.ktoastapp.app

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toast
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoast.KToastScope
import com.logan.ktoast.runtime.KToastContentFactory
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoastapp.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KToastMultiWindowTest {
    @get:Rule val rule = activityScenarioRule<MainActivity>()
    private var scope: KToastScope? = null
    private var enteredFreeform = false
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }.also {
                check(!it.contains("Error:") && !it.contains("Exception")) { it }
            }

    private fun windowMode(mode: Int) {
        val packageName = instrumentation.targetContext.packageName
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        shell("am start -W --windowingMode $mode --activity-single-top -n $packageName/com.logan.ktoastapp.MainActivity")
        instrumentation.waitForIdleSync()
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { scope?.close() }
        if (enteredFreeform) windowMode(1)
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun freeformFixtureUsesHostBounds_andHandlesTaskResize() {
        // 只在显式启用自由窗口的专用模拟器运行，不修改日常设备的开发者设置。
        assumeTrue(InstrumentationRegistry.getArguments().getString("multiWindowFixture") == "true")
        enteredFreeform = true
        windowMode(5)
        lateinit var content: View
        lateinit var originalHost: MainActivity
        lateinit var originalBounds: Rect
        lateinit var originalScope: KToastScope
        lateinit var handle: ToastHandle
        var taskId = 0
        rule.scenario.onActivity { host ->
            originalHost = host
            assertTrue("Fixture did not enter multi-window", host.isInMultiWindowMode)
            originalBounds = Rect(host.windowManager.currentWindowMetrics.bounds)
            val maximum = host.windowManager.maximumWindowMetrics.bounds
            assertTrue(originalBounds.width() < maximum.width())
            assertTrue(originalBounds.left > maximum.left)
            originalScope = KToastScope.create(host).also { scope = it }
            taskId = host.taskId
            handle = originalScope.show("freeform", Toast.LENGTH_LONG) {
                gravity = Gravity.TOP or Gravity.START
                xOffset = Int.MIN_VALUE
                yOffset = Int.MIN_VALUE
                maxWidthRatio = 0.8f
                animationDuration = 0L
                contentFactory = KToastContentFactory { context, _, _ ->
                    View(context).apply {
                        // 用显式布局尺寸触发整体限制，避免 minimumWidth 被样式覆盖后测试失效。
                        layoutParams = FrameLayout.LayoutParams(2_000, 2_000)
                    }.also { content = it }
                }
            }
        }
        instrumentation.waitForIdleSync()
        rule.scenario.onActivity { assertInsideHost(it, content) }

        shell("am task resize $taskId 60 200 1020 1600")
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var resized = false
        while (!resized && SystemClock.uptimeMillis() < deadline) {
            rule.scenario.onActivity { host ->
                resized = host.windowManager.currentWindowMetrics.bounds.width() != originalBounds.width()
            }
            if (!resized) SystemClock.sleep(16L)
        }
        assertTrue("Task did not resize", resized)
        rule.scenario.onActivity { host ->
            assertTrue(host.isInMultiWindowMode)
            if (host !== originalHost) {
                assertTrue(originalScope.isClosed)
                assertTrue(handle.isCanceled)
                scope = KToastScope.create(host)
                handle = scope!!.show("resized", Toast.LENGTH_LONG) {
                    animationDuration = 0L
                    gravity = Gravity.TOP or Gravity.START
                    xOffset = Int.MIN_VALUE
                    yOffset = Int.MIN_VALUE
                    contentFactory = KToastContentFactory { context, _, _ ->
                        View(context).apply { layoutParams = FrameLayout.LayoutParams(2_000, 2_000) }
                            .also { content = it }
                    }
                }
            } else {
                assertFalse(originalScope.isClosed)
                assertEquals(ToastHandle.State.SHOWING, handle.state)
            }
        }
        instrumentation.waitForIdleSync()
        rule.scenario.onActivity {
            assertEquals(ToastHandle.State.SHOWING, handle.state)
            assertInsideHost(it, content)
        }
    }

    private fun assertInsideHost(host: MainActivity, content: View) {
        val root = content.rootView
        val bounds = host.windowManager.currentWindowMetrics.bounds
        val safe = host.window.decorView.rootWindowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        assertTrue(root.isAttachedToWindow)
        assertTrue(root.width > 0 && root.width <= ((bounds.width() - safe.left - safe.right) * 0.8f).toInt())
        assertEquals(bounds.left + safe.left, location[0])
        assertEquals(bounds.top + safe.top, location[1])
        assertTrue(location[0] + root.width <= bounds.right - safe.right)
        assertTrue(location[1] + root.height <= bounds.bottom - safe.bottom)
    }
}
