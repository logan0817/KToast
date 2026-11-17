package com.logan.ktoastapp.app

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoast.KToast
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoastapp.ComposeToastActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class KToastComposeAccessibilityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { KToast.cancel() }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun declaredComposeControlUsesInteractiveTimeoutAndReleasesComposition() {
        requireInteractiveFixture()
        ActivityScenario.launch(ComposeToastActivity::class.java).use { scenario ->
            compose.waitForIdle()
            val dismissed = CountDownLatch(1)
            val terminals = AtomicInteger()
            val disposed = AtomicInteger()
            val clicks = AtomicInteger()
            lateinit var handle: ToastHandle
            lateinit var next: ToastHandle
            scenario.onActivity { host ->
                val queue = KToastBehavior(KToastDisplayMode.QUEUE)
                handle = host.toastScope.showContent("可撤销的操作", behavior = queue,
                    callbacks = KToastCallbacks(onDismiss = { _, _ ->
                        terminals.incrementAndGet()
                        dismissed.countDown()
                    }),
                    localConfig = {
                        animationDuration = 0L
                        cancelOnTouch = false
                        accessibilityContainsControls = true
                    }
                ) {
                    DisposableEffect(Unit) { onDispose { disposed.incrementAndGet() } }
                    BasicText("撤销", Modifier.testTag("toast-action").clickable { clicks.incrementAndGet() })
                }
                next = host.toastScope.show("next", behavior = queue) { animationDuration = 0L }
            }
            compose.waitForIdle()
            assertFalse("Compose control ignored interactive timeout", dismissed.await(2500L, TimeUnit.MILLISECONDS))
            compose.onNodeWithTag("toast-action").performSemanticsAction(SemanticsActions.OnClick) { action ->
                assertTrue(action())
            }
            scenario.onActivity {
                assertEquals(1, clicks.get())
                assertEquals(ToastHandle.State.SHOWING, handle.state)
                assertEquals(ToastHandle.State.PENDING, next.state)
                assertTrue(handle.cancel())
                assertFalse(handle.cancel())
                assertEquals(1, disposed.get())
                assertEquals(1, terminals.get())
                assertEquals(ToastHandle.State.SHOWING, next.state)
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun plainComposeTextDoesNotUseInteractiveTimeout() {
        requireInteractiveFixture()
        ActivityScenario.launch(ComposeToastActivity::class.java).use { scenario ->
            compose.waitForIdle()
            val dismissed = CountDownLatch(1)
            lateinit var handle: ToastHandle
            scenario.onActivity { host ->
                handle = host.toastScope.showContent("普通文本",
                    callbacks = KToastCallbacks(onDismiss = { _, _ -> dismissed.countDown() }),
                    localConfig = { animationDuration = 0L }
                ) { BasicText("普通文本") }
            }
            assertTrue("Plain Compose text incorrectly used interactive timeout", dismissed.await(5L, TimeUnit.SECONDS))
            assertEquals(ToastHandle.DismissReason.COMPLETED, handle.dismissReason)
        }
    }

    @android.annotation.TargetApi(29)
    private fun requireInteractiveFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("interactiveTimeoutFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        assertEquals(2000, manager.getRecommendedTimeoutMillis(2000, AccessibilityManager.FLAG_CONTENT_TEXT))
        assertTrue(manager.getRecommendedTimeoutMillis(2000, AccessibilityManager.FLAG_CONTENT_CONTROLS) >= 5000)
    }
}
