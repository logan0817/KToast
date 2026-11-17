package com.logan.ktoastapp.app

import android.os.Build
import android.os.Handler
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoast.KToast
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType
import com.logan.ktoast.runtime.KToastContentFactory
import com.logan.ktoastapp.MainActivity
import com.logan.ktoastapp.R
import com.logan.ktoastapp.toastError
import com.logan.ktoastapp.toastSuccess
import com.logan.ktoastapp.toastWarning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoToastRegressionTest {
    @get:Rule
    val rule = activityScenarioRule<MainActivity>()

    private val rendered = mutableListOf<Pair<String, KToastConfig>>()
    private var previousFactory: KToastContentFactory? = null
    private var previousAnimationDuration = 0L

    @Before
    fun captureRenderedToasts() {
        rule.scenario.onActivity {
            KToast.cancel()
            KToast.config {
                previousFactory = contentFactory
                previousAnimationDuration = animationDuration
                animationDuration = 0L
                contentFactory = KToastContentFactory { context, message, config ->
                    rendered += message.toString() to config.snapshot()
                    TextView(context).apply { text = message }
                }
            }
        }
    }

    @After
    fun cleanup() {
        rule.scenario.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            KToast.cancel()
            KToast.config {
                contentFactory = previousFactory
                animationDuration = previousAnimationDuration
            }
        }
    }

    @Test
    fun repeatedClick_cancelsOldRound_andOnlyCurrentRoundReportsSuccess() {
        rule.scenario.onActivity { activity ->
            val businessTask = KToast.showDelayed("other business", 60_000L)
            val (firstTask, firstCallback) = startRound(activity)
            val (secondTask, secondCallback) = startRound(activity)
            assertCallbackRemoved(activity, firstCallback)

            // 提前执行真实任务，避免依赖三秒等待和设备调度时序。
            firstTask.run()
            firstCallback.run()
            assertTrue(rendered.isEmpty())
            assertSame(secondTask, field(activity, "delayedTask"))
            assertSame(secondCallback, field(activity, "delayedCancelCallback"))

            secondCallback.run()
            secondTask.run()
            firstCallback.run()
            secondCallback.run()
            assertEquals(
                listOf(activity.getString(R.string.demo_toast_delayed_cancel_success)),
                rendered.map { it.first }
            )
            assertRoundCleared(activity)

            businessTask.run()
            assertEquals("other business", rendered.last().first)
        }
    }

    @Test
    fun cancelAll_clearsCurrentRound_andPreventsLateFeedback() {
        rule.scenario.onActivity { activity ->
            val first = startRound(activity)
            val second = startRound(activity)
            activity.findViewById<View>(R.id.btnCancelAll).performClick()
            assertRoundCleared(activity)
            for ((task, callback) in listOf(first, second)) {
                assertCallbackRemoved(activity, callback)
                task.run()
                callback.run()
            }
            assertTrue(rendered.isEmpty())
            val action = activity.findViewById<TextView>(R.id.tvLastAction).text.toString()
            assertEquals(
                activity.getString(
                    R.string.demo_status_action_template,
                    activity.getString(R.string.demo_action_cancel_all)
                ),
                action
            )
        }
    }

    @Test
    fun destroyAfterRepeatedClick_clearsBothRounds_withoutCancelingBusinessTask() {
        lateinit var destroyedActivity: MainActivity
        lateinit var first: Pair<Runnable, Runnable>
        lateinit var second: Pair<Runnable, Runnable>
        lateinit var businessTask: Runnable
        rule.scenario.onActivity { activity ->
            destroyedActivity = activity
            businessTask = KToast.showDelayed("survives demo destruction", 60_000L)
            first = startRound(activity)
            second = startRound(activity)
        }
        rule.scenario.recreate()
        rule.scenario.onActivity {
            assertTrue(destroyedActivity.isDestroyed)
            assertRoundCleared(destroyedActivity)
            for ((task, callback) in listOf(first, second)) {
                assertCallbackRemoved(destroyedActivity, callback)
                task.run()
                callback.run()
            }
            assertTrue(rendered.isEmpty())
            businessTask.run()
            assertEquals(listOf("survives demo destruction"), rendered.map { it.first })
        }
    }

    @Test
    fun appWrappers_useSemanticColorsAndIcons() {
        rule.scenario.onActivity {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            "success".toastSuccess()
            "error".toastError()
            "warning".toastWarning()
            assertEquals(listOf("success", "error", "warning"), rendered.map { it.first })
            listOf(KToastType.SUCCESS, KToastType.ERROR, KToastType.WARNING).forEachIndexed { index, type ->
                val expected = KToastStyle(type).resolve(context)
                val actual = rendered[index].second
                assertEquals(expected.backgroundColor, actual.backgroundColor)
                assertEquals(expected.textColor, actual.textColor)
                assertEquals(expected.icon, actual.icon)
                assertEquals(expected.iconColor, actual.iconColor)
            }
        }
    }

    @Test
    fun appWrappers_keepLocalConfigPriority() {
        rule.scenario.onActivity {
            val customize: KToastConfig.() -> Unit = {
                backgroundColor = 0xFF123456.toInt()
                textColor = 0xFFABCDEF.toInt()
                icon = R.drawable.ktoast_ic_warning
                iconColor = 0xFF654321.toInt()
                backgroundRadius = 37f
            }
            "success".toastSuccess(customize)
            "error".toastError(customize)
            "warning".toastWarning(customize)
            assertEquals(3, rendered.size)
            rendered.forEach { (_, config) ->
                assertEquals(0xFF123456.toInt(), config.backgroundColor)
                assertEquals(0xFFABCDEF.toInt(), config.textColor)
                assertEquals(R.drawable.ktoast_ic_warning, config.icon)
                assertEquals(0xFF654321.toInt(), config.iconColor)
                assertEquals(37f, config.backgroundRadius, 0f)
            }
        }
    }

    @Test
    fun appWrappers_ignoreNullEmptyAndBlank_withoutRunningLocalConfig() {
        rule.scenario.onActivity {
            var localCalls = 0
            listOf<CharSequence?>(null, "", " \t\n").forEach { message ->
                message.toastSuccess { localCalls++ }
                message.toastError { localCalls++ }
                message.toastWarning { localCalls++ }
            }
            assertEquals(0, localCalls)
            assertTrue(rendered.isEmpty())
        }
    }

    private fun startRound(activity: MainActivity): Pair<Runnable, Runnable> {
        activity.findViewById<View>(R.id.btnDelayedCancel).performClick()
        return (field(activity, "delayedTask") as Runnable) to
            (field(activity, "delayedCancelCallback") as Runnable)
    }

    private fun assertRoundCleared(activity: MainActivity) {
        assertNull(field(activity, "delayedTask"))
        assertNull(field(activity, "delayedCancelCallback"))
    }

    private fun assertCallbackRemoved(activity: MainActivity, callback: Runnable) {
        if (Build.VERSION.SDK_INT >= 29) {
            assertFalse((field(activity, "demoHandler") as Handler).hasCallbacks(callback))
        }
    }

    // 仅测试访问演示轮次，避免为驱动延时回归给 Activity 增加公开接口。
    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
}
