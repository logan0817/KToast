package com.logan.ktoast.composeconsumer

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import com.logan.ktoast.compose.KToastContent
import com.logan.ktoast.compose.rememberKToastScope
import com.logan.ktoast.runtime.ToastHandle
import com.logan.ktoast.config.KToastAnimation
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** 独立从候选仓库解析，在 R8 后验证子窗口内容创建及页面移除清理。 */
class ConsumerActivity : ComponentActivity() {
    private var created = false
    private var disposed = false
    private var handle: ToastHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var visible by remember { mutableStateOf(true) }
            if (visible) {
                val scope = rememberKToastScope()
                LaunchedEffect(scope) {
                    withTimeout(10_000L) {
                        while (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) delay(16L)
                    }
                    scope.show("plain consumer").cancel()
                    handle = scope.showContent("compose consumer", localConfig = {
                        animation = KToastAnimation(OvershootInterpolator(4f), DecelerateInterpolator(), 1.1f)
                    }) {
                        DisposableEffect(Unit) {
                            created = true
                            onDispose { disposed = true }
                        }
                        KToastContent("compose consumer")
                    }
                    withTimeout(10_000L) { while (!created) delay(16L) }
                    visible = false
                }
            }
            LaunchedEffect(visible) {
                if (!visible) {
                    withTimeout(10_000L) { while (!disposed || handle?.isFinished != true) delay(16L) }
                    check(handle?.isCanceled == true) { "Page removal did not cancel the request" }
                    Log.i("KToastComposeConsumer", "PASS: created=$created disposed=$disposed canceled=${handle?.isCanceled}")
                }
            }
        }
    }
}
