package com.logan.ktoastapp.app

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 仅专用矩阵启用：失败后、ActivityScenario 销毁宿主前保存系统窗口状态。
 * 此时用例的 @After 已执行，证据不能等同于失焦最初发生时的状态。
 */
class WindowFailureDiagnosticsRule : TestWatcher() {
    override fun failed(error: Throwable, description: Description) {
        if (InstrumentationRegistry.getArguments().getString("windowFailureDiagnostics") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            val runId = requireNotNull(
                InstrumentationRegistry.getArguments().getString("windowFailureRunId")
            )
            require(runId.matches(Regex("[0-9]+")))
            // 私有目录不依赖外部存储挂载，主机通过调试宿主的 run-as 导出。
            val base = instrumentation.targetContext.filesDir
            val test = (description.className + "#" + description.methodName)
                .replace(Regex("[^A-Za-z0-9_.#-]"), "_")
            val output = File(base, "window-failures/$runId/$test")
            check(output.mkdirs() || output.isDirectory)
            File(output, "failure.txt").writeText(
                "test=$test" + System.lineSeparator() +
                    "capturedAtMillis=${System.currentTimeMillis()}" + System.lineSeparator() +
                    "failure=${error.javaClass.name}: ${error.message}" + System.lineSeparator()
            )
            for ((name, service) in listOf(
                "window" to "window", "input" to "input",
                "activity" to "activity activities", "display" to "display",
                // 输入层的 NOT_VISIBLE 需要合成层状态解释，窗口焦点快照不足以定位。
                "surfaceflinger" to "SurfaceFlinger"
            )) {
                try {
                    // 旧系统不支持 dumpsys -t；读取端限时，超时关闭管道并保留原始失败。
                    val reader = Executors.newSingleThreadExecutor { task ->
                        Thread(task, "KToastFailureCapture").apply { isDaemon = true }
                    }
                    try {
                        instrumentation.uiAutomation.executeShellCommand("dumpsys $service").use { descriptor ->
                            val capture = reader.submit {
                                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                                    File(output, "$name.txt").outputStream().use { sink -> input.copyTo(sink) }
                                }
                            }
                            try { capture.get(5, TimeUnit.SECONDS) } finally { capture.cancel(true) }
                        }
                    } finally {
                        reader.shutdownNow()
                    }
                } catch (captureError: Exception) {
                    Log.e("KToastDeviceFailure", "Capture failed: $test $name", captureError)
                }
            }
            Log.e("KToastDeviceFailure", "Failure diagnostics: $test at ${output.absolutePath}")
        } catch (captureError: Exception) {
            // 采证失败只记录错误，不替换原始断言或触发重试。
            Log.e("KToastDeviceFailure", "Cannot capture failure diagnostics", captureError)
        }
    }
}
