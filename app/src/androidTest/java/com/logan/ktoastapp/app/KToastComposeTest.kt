package com.logan.ktoastapp.app

import android.app.Activity
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoast.KToast
import com.logan.ktoast.compose.KToastComposeScope
import com.logan.ktoast.compose.KToastContent
import com.logan.ktoast.compose.rememberKToastScope
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoastapp.ComposeToastActivity
import com.logan.ktoastapp.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val LocalToastTestTheme = staticCompositionLocalOf { "missing parent theme" }

@RunWith(AndroidJUnit4::class)
class KToastComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val queue = KToastBehavior(KToastDisplayMode.QUEUE)

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { KToast.cancel() }
    }

    @Test fun childCompositionInheritsParentAndDisposesBeforeQueueContinues() {
        ActivityScenario.launch(ComposeToastActivity::class.java).use { scenario ->
            lateinit var scope: KToastComposeScope
            lateinit var first: ToastHandle
            lateinit var next: ToastHandle
            val theme = AtomicReference<String>()
            val owner = AtomicReference<LifecycleOwner>()
            val registryOwner = AtomicReference<SavedStateRegistryOwner>()
            val contentView = AtomicReference<android.view.View>()
            val disposed = AtomicInteger()
            val label = mutableStateOf("short")
            var initialWidth = 0
            scenario.onActivity { host ->
                host.setContent {
                    CompositionLocalProvider(LocalToastTestTheme provides "brand theme") {
                        val current = rememberKToastScope()
                        SideEffect { scope = current }
                    }
                }
            }
            compose.waitForIdle()
            scenario.onActivity { host ->
                first = scope.showContent("themed content", Toast.LENGTH_LONG, queue, localConfig = {
                    animationDuration = 0L
                    gravity = Gravity.CENTER
                    xOffset = 0
                    yOffset = 0
                }) {
                    theme.set(LocalToastTestTheme.current)
                    owner.set(LocalLifecycleOwner.current)
                    registryOwner.set(LocalSavedStateRegistryOwner.current)
                    contentView.set(LocalView.current)
                    DisposableEffect(Unit) { onDispose { disposed.incrementAndGet() } }
                    BasicText(label.value)
                }
                next = scope.show("next request", behavior = queue) { animationDuration = 0L }
                assertEquals(ToastHandle.State.SHOWING, first.state)
                assertEquals(ToastHandle.State.PENDING, next.state)
            }
            compose.waitForIdle()
            awaitCenteredInsideHost(scenario, contentView)
            scenario.onActivity { host ->
                assertEquals("brand theme", theme.get())
                assertSame(host, owner.get())
                assertSame(host, registryOwner.get())
                assertTrue(contentView.get().isAttachedToWindow)
                val toastRoot = contentView.get().rootView
                assertNotSame(host.window.decorView.rootView, toastRoot)
                assertCenteredInsideHost(host, toastRoot)
                initialWidth = toastRoot.width
                label.value = "longer content after recomposition"
            }
            compose.waitForIdle()
            awaitCenteredInsideHost(scenario, contentView)
            scenario.onActivity { host ->
                val toastRoot = contentView.get().rootView
                assertTrue(toastRoot.width > initialWidth)
                assertCenteredInsideHost(host, toastRoot)
                host.windowManager.removeViewImmediate(toastRoot)
                assertTrue(first.isCanceled)
                assertEquals(1, disposed.get())
                assertFalse(contentView.get().isAttachedToWindow)
                assertNull(contentView.get().findViewTreeLifecycleOwner())
                assertEquals(ToastHandle.State.SHOWING, next.state)
            }
        }
    }

    @Test fun leavingCompositionCancelsPendingWithoutCreatingContentAndRejectsOldScope() {
        ActivityScenario.launch(ComposeToastActivity::class.java).use { scenario ->
            compose.waitForIdle()
            lateinit var old: KToastComposeScope
            lateinit var waiting: ToastHandle
            lateinit var delayed: ToastHandle
            val created = AtomicInteger()
            val dismissed = AtomicInteger()
            scenario.onActivity { host ->
                old = host.toastScope
                old.show("block queue", Toast.LENGTH_LONG, queue) { animationDuration = 0L }
                waiting = old.showContent("waiting custom content", behavior = queue,
                    callbacks = KToastCallbacks(onDismiss = { _, _ -> dismissed.incrementAndGet() })) {
                    created.incrementAndGet()
                    BasicText("must not be created")
                }
                delayed = old.showDelayed("old delay", 60_000L)
                host.setPageVisible(false)
            }
            compose.waitForIdle()
            scenario.onActivity { host ->
                assertTrue(old.isClosed)
                assertTrue(waiting.isCanceled)
                assertTrue(delayed.isCanceled)
                assertEquals(0, created.get())
                assertEquals(1, dismissed.get())
                assertThrows(IllegalStateException::class.java) { old.show("old page") }
                host.setPageVisible(true)
            }
            compose.waitForIdle()
            scenario.onActivity { host ->
                assertNotSame(old, host.toastScope)
                assertEquals(ToastHandle.State.SHOWING, host.toastScope.show("fresh page").state)
            }
        }
    }

    @Test fun ownerReplacementClosesOldBindingAndKeepsRealSavedStateOwner() {
        ActivityScenario.launch(ComposeToastActivity::class.java).use { scenario ->
            val firstOwner = TestOwner()
            val secondOwner = TestOwner()
            val selectedOwner = mutableStateOf<LifecycleOwner>(firstOwner)
            lateinit var scope: KToastComposeScope
            lateinit var oldScope: KToastComposeScope
            lateinit var active: ToastHandle
            lateinit var fresh: ToastHandle
            val disposed = AtomicInteger()
            val seenOwner = AtomicReference<LifecycleOwner>()
            val seenRegistry = AtomicReference<SavedStateRegistryOwner>()
            scenario.onActivity { host ->
                firstOwner.registry.currentState = Lifecycle.State.STARTED
                secondOwner.registry.currentState = Lifecycle.State.STARTED
                host.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides selectedOwner.value) {
                        val current = rememberKToastScope()
                        SideEffect { scope = current }
                    }
                }
            }
            compose.waitForIdle()
            scenario.onActivity {
                oldScope = scope
                active = scope.showContent("old owner", Toast.LENGTH_LONG, localConfig = { animationDuration = 0L }) {
                    DisposableEffect(Unit) { onDispose { disposed.incrementAndGet() } }
                    BasicText("old owner")
                }
            }
            compose.waitForIdle()
            scenario.onActivity { selectedOwner.value = secondOwner }
            compose.waitForIdle()
            scenario.onActivity { host ->
                assertTrue(oldScope.isClosed)
                assertTrue(active.isCanceled)
                assertEquals(1, disposed.get())
                assertEquals(0, firstOwner.registry.observerCount)
                assertNotSame(oldScope, scope)
                fresh = scope.showContent("new owner", localConfig = { animationDuration = 0L }) {
                    seenOwner.set(LocalLifecycleOwner.current)
                    seenRegistry.set(LocalSavedStateRegistryOwner.current)
                    BasicText("new owner")
                }
                assertEquals(ToastHandle.State.SHOWING, fresh.state)
            }
            compose.waitForIdle()
            scenario.onActivity { host ->
                assertSame(secondOwner, seenOwner.get())
                assertSame(host, seenRegistry.get())
                secondOwner.registry.currentState = Lifecycle.State.DESTROYED
                assertTrue(scope.isClosed)
                assertTrue(fresh.isCanceled)
                assertEquals(0, secondOwner.registry.observerCount)
            }
        }
    }

    @Test fun configurationFailurePropagatesWithoutCreatingContentOrBlockingQueue() {
        ActivityScenario.launch(ComposeToastActivity::class.java).use { scenario ->
            compose.waitForIdle()
            val created = AtomicInteger()
            scenario.onActivity { host ->
                val blocker = host.toastScope.show("blocker", Toast.LENGTH_LONG, queue) { animationDuration = 0L }
                repeat(40) {
                    val failure = assertThrows(IllegalStateException::class.java) {
                        host.toastScope.showContent("invalid configuration", behavior = queue,
                            localConfig = { error("intentional configuration failure") }) {
                            created.incrementAndGet()
                            BasicText("must not be composed")
                        }
                    }
                    assertEquals("intentional configuration failure", failure.message)
                }
                val next = host.toastScope.show("after failed configuration", behavior = queue) { animationDuration = 0L }
                blocker.cancel()
                assertEquals(0, created.get())
                assertNull(next.dropReason)
                assertEquals(ToastHandle.State.SHOWING, next.state)
            }
        }
    }

    @Test fun savedStateOwnerReplacementAndDestructionReleaseTheChildComposition() {
        ActivityScenario.launch(ComposeToastActivity::class.java).use { scenario ->
            lateinit var firstOwner: TestSavedStateOwner
            lateinit var secondOwner: TestSavedStateOwner
            lateinit var oldScope: KToastComposeScope
            lateinit var scope: KToastComposeScope
            lateinit var active: ToastHandle
            val selectedOwner = mutableStateOf<SavedStateRegistryOwner?>(null)
            val disposed = AtomicInteger()
            val seenRegistry = AtomicReference<SavedStateRegistryOwner>()
            var initialObserverCount = 0
            scenario.onActivity { host ->
                firstOwner = TestSavedStateOwner()
                secondOwner = TestSavedStateOwner()
                initialObserverCount = firstOwner.registry.observerCount
                selectedOwner.value = firstOwner
                host.setContent {
                    CompositionLocalProvider(LocalSavedStateRegistryOwner provides selectedOwner.value!!) {
                        val current = rememberKToastScope()
                        SideEffect { scope = current }
                    }
                }
            }
            compose.waitForIdle()
            scenario.onActivity {
                oldScope = scope
                active = scope.showContent("saved state child", Toast.LENGTH_LONG,
                    localConfig = { animationDuration = 0L }) {
                    val remembered = rememberSaveable { "temporary state" }
                    seenRegistry.set(LocalSavedStateRegistryOwner.current)
                    DisposableEffect(Unit) { onDispose { disposed.incrementAndGet() } }
                    BasicText(remembered)
                }
            }
            compose.waitForIdle()
            scenario.onActivity {
                assertSame(firstOwner, seenRegistry.get())
                selectedOwner.value = secondOwner
            }
            compose.waitForIdle()
            scenario.onActivity {
                assertTrue(oldScope.isClosed)
                assertTrue(active.isCanceled)
                assertEquals(1, disposed.get())
                assertEquals(initialObserverCount, firstOwner.registry.observerCount)
                assertNotSame(oldScope, scope)
                active = scope.showDelayed("new registry pending", 60_000L)
                secondOwner.registry.currentState = Lifecycle.State.DESTROYED
                assertTrue(scope.isClosed)
                assertTrue(active.isCanceled)
                assertEquals(0, secondOwner.registry.observerCount)
            }
        }
    }

    @Test fun activityRecreationClosesTheOldCompositionAndCreatesAnIndependentScope() {
        ActivityScenario.launch(ComposeToastActivity::class.java).use { scenario ->
            compose.waitForIdle()
            lateinit var old: KToastComposeScope
            lateinit var pending: ToastHandle
            scenario.onActivity { host ->
                old = host.toastScope
                pending = old.showDelayed("before recreation", 60_000L)
            }
            scenario.recreate()
            compose.waitForIdle()
            scenario.onActivity { host ->
                assertTrue(old.isClosed)
                assertTrue(pending.isCanceled)
                assertNotSame(old, host.toastScope)
                assertEquals(ToastHandle.State.SHOWING, host.toastScope.show("after recreation").state)
            }
        }
    }

    @Test fun defaultContentRendersBrandShapeDrawableWithoutNarrowingViewIconSupport() {
        ActivityScenario.launch(ComposeToastActivity::class.java).use { scenario ->
            val root = AtomicReference<android.view.View>()
            val disposed = AtomicInteger()
            lateinit var handle: ToastHandle
            compose.waitForIdle()
            scenario.onActivity { host ->
                val style = KToastStyle(styleRes = R.style.ComposeShapeIconTestStyle)
                handle = host.toastScope.showContent("shape drawable", Toast.LENGTH_LONG,
                    localConfig = { animationDuration = 0L }) {
                    root.set(LocalView.current)
                    DisposableEffect(Unit) { onDispose { disposed.incrementAndGet() } }
                    KToastContent("shape drawable", style = style)
                }
            }
            compose.waitForIdle()
            scenario.onActivity { host ->
                val image = findImageView(root.get())
                assertNotNull(image)
                assertNotNull(image!!.drawable)
                assertTrue(image.drawable is android.graphics.drawable.GradientDrawable)
                assertTrue(image.width > 0)
                assertTrue(image.height > 0)
                assertNull(image.contentDescription)
                handle.cancel()
                assertEquals(1, disposed.get())
                assertEquals(ToastHandle.State.SHOWING, host.toastScope.show("after shape icon").state)
            }
        }
    }

    @Test fun normalRecompositionKeepsScopeAndPreviewDisablesWindowOperations() {
        ActivityScenario.launch(ComposeToastActivity::class.java).use { scenario ->
            val revision = mutableStateOf(0)
            val inspection = mutableStateOf(false)
            lateinit var scope: KToastComposeScope
            lateinit var original: KToastComposeScope
            lateinit var delayed: ToastHandle
            scenario.onActivity { host ->
                host.setContent {
                    CompositionLocalProvider(LocalInspectionMode provides inspection.value) {
                        val current = rememberKToastScope()
                        SideEffect { scope = current }
                        KToastContent("Preview ${revision.value}")
                    }
                }
            }
            compose.waitForIdle()
            scenario.onActivity {
                original = scope
                delayed = scope.showDelayed("survive ordinary recomposition", 60_000L)
                revision.value++
            }
            compose.waitForIdle()
            scenario.onActivity {
                assertSame(original, scope)
                assertFalse(delayed.isCanceled)
                inspection.value = true
            }
            compose.waitForIdle()
            scenario.onActivity {
                assertTrue(original.isClosed)
                assertTrue(delayed.isCanceled)
                assertTrue(scope.isClosed)
                val failure = assertThrows(IllegalStateException::class.java) { scope.show("disabled preview") }
                assertTrue(failure.message!!.contains("Preview"))
            }
        }
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun awaitCenteredInsideHost(
        scenario: ActivityScenario<ComposeToastActivity>,
        content: AtomicReference<View>
    ) {
        // Compose 空闲不代表 WindowManager 已应用 attach 或重组后的窗口坐标。
        var lastGeometry = "No content frame"
        try {
            compose.waitUntil(timeoutMillis = 2_000L) {
                var centered = false
                scenario.onActivity { host ->
                    val root = content.get()?.rootView ?: return@onActivity
                    val decor = host.window.decorView
                    val hostLocation = IntArray(2).also(decor::getLocationOnScreen)
                    val location = IntArray(2).also(root::getLocationOnScreen)
                    val params = root.layoutParams as? WindowManager.LayoutParams
                    lastGeometry = "toast=[${location[0]},${location[1]},${root.width},${root.height}], " +
                        "params=[${params?.x},${params?.y}], " +
                        "host=[${hostLocation[0]},${hostLocation[1]},${decor.width},${decor.height}], " +
                        "attached=${root.isAttachedToWindow}"
                    centered = root.isAttachedToWindow && root.width > 0 && root.height > 0 &&
                        kotlin.math.abs(location[0] + root.width / 2 - hostLocation[0] - decor.width / 2) <= 1 &&
                        kotlin.math.abs(location[1] + root.height / 2 - hostLocation[1] - decor.height / 2) <= 1
                }
                centered
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Window did not settle: $lastGeometry", error)
        }
    }

    private fun assertCenteredInsideHost(host: Activity, root: View) {
        val hostLocation = IntArray(2)
        val location = IntArray(2)
        val decor = host.window.decorView
        decor.getLocationOnScreen(hostLocation)
        root.getLocationOnScreen(location)
        assertTrue(root.width > 0 && root.height > 0)
        val geometry = "toast=[${location[0]},${location[1]},${root.width},${root.height}], " +
            "host=[${hostLocation[0]},${hostLocation[1]},${decor.width},${decor.height}]"
        assertTrue("Horizontal center differs: $geometry",
            kotlin.math.abs(location[0] + root.width / 2 - hostLocation[0] - decor.width / 2) <= 1)
        assertTrue("Vertical center differs: $geometry",
            kotlin.math.abs(location[1] + root.height / 2 - hostLocation[1] - decor.height / 2) <= 1)
        val visible = Rect()
        decor.getWindowVisibleDisplayFrame(visible)
        assertTrue(visible.contains(location[0], location[1], location[0] + root.width, location[1] + root.height))
    }

    private class TestSavedStateOwner : SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.STARTED
        }
    }

    private fun findImageView(root: android.view.View): android.widget.ImageView? {
        if (root is android.widget.ImageView) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) findImageView(root.getChildAt(index))?.let { return it }
        }
        return null
    }
}
