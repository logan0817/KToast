package com.logan.ktoastapp.app

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoastapp.LifecycleCoverActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 避免复用 AndroidX 跨进程辅助 Activity 时，延迟关闭广播打断下一次转场。 */
internal fun <A : ComponentActivity> ActivityScenario<A>.whileActivityStopped(
    block: (resume: () -> Unit) -> Unit
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    lateinit var host: A
    onActivity { host = it }
    val stopped = CountDownLatch(1)
    val resumed = CountDownLatch(1)
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) stopped.countDown()
        if (event == Lifecycle.Event.ON_RESUME && stopped.count == 0L) resumed.countDown()
    }
    instrumentation.runOnMainSync { host.lifecycle.addObserver(observer) }
    try {
        val cover = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, LifecycleCoverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        var restored = false
        val restore = Closeable {
            if (!restored) {
                instrumentation.runOnMainSync { cover.finish() }
                assertTrue("Host did not resume after cover finished", resumed.await(45, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertEquals(Lifecycle.State.RESUMED, state)
                restored = true
            }
        }
        restore.use {
            assertTrue("Cover did not stop host", stopped.await(45, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals(Lifecycle.State.CREATED, state)
            block { restore.close() }
        }
    } finally {
        instrumentation.runOnMainSync { host.lifecycle.removeObserver(observer) }
    }
}
