package com.logan.ktoastapp.app

import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.ktoastapp.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

@RunWith(AndroidJUnit4::class)
class WindowFailureDiagnosticsTest {
    @get:Rule val host = activityScenarioRule<MainActivity>()

    @Test fun failurePreservesOriginalAndWritesShellReadableSnapshots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires dedicated diagnostics matrix",
            arguments.getString("windowFailureDiagnostics") == "true")
        val runId = requireNotNull(arguments.getString("windowFailureRunId"))
        val description = Description.createTestDescription(javaClass, "syntheticFailure")
        val output = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "window-failures/$runId/${javaClass.name}#syntheticFailure")
        val original = AssertionError("diagnostics device regression")
        val failing = object : Statement() {
            override fun evaluate() { throw original }
        }
        var received: Throwable? = null
        try {
            WindowFailureDiagnosticsRule().apply(failing, description).evaluate()
        } catch (error: Throwable) {
            received = error
        }
        assertSame(original, received)
        host.scenario.onActivity { assertFalse(it.isDestroyed) }
        for (name in listOf("failure", "window", "input", "activity", "display")) {
            val file = File(output, "$name.txt")
            assertTrue("Missing or empty diagnostics: $file", file.isFile && file.length() > 0)
        }
        assertTrue(File(output, "failure.txt").readText().contains(original.message!!))
        // adb shell 与 UIAutomation 使用相同 shell 身份，检查目录确实可从外部读取。
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cat ${File(output, "failure.txt").absolutePath}")
        ).bufferedReader().use { assertTrue(it.readText().contains(original.message!!)) }
        // 专用矩阵保留自检证据供主机 adb pull；模拟器随后销毁。
    }
}
