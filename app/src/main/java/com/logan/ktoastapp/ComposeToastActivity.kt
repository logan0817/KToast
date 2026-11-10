package com.logan.ktoastapp

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxWidth
import com.logan.ktoast.KToastScopeOptions
import com.logan.ktoast.compose.KToastComposeScope
import com.logan.ktoast.compose.KToastContent
import com.logan.ktoast.compose.rememberKToastScope
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.ToastHandle

/** 页面控件直接复用 Demo 原生组件，提示内容与作用域仍由真实 Composition 拥有。 */
class ComposeToastActivity : AppCompatActivity() {
    lateinit var toastScope: KToastComposeScope
        private set
    private var pageIsVisible by mutableStateOf(true)
    private var pageGeneration by mutableIntStateOf(0)
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = DemoPage(this, R.string.demo_compose_title)
        DemoPage.addHero(page.content, R.string.demo_compose_eyebrow,
            R.string.demo_compose_hero_title, R.string.demo_compose_hero_description)
        val content = DemoPage.addSection(page.content, R.string.demo_compose_content_badge,
            R.string.demo_compose_content_title, R.string.demo_compose_content_description)
        content.addView(ComposeView(this).apply {
            id = android.view.View.generateViewId()
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                if (pageIsVisible) key(pageGeneration) { ToastPage() }
                else AndroidView(
                    factory = { context ->
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            DemoPage.addStatus(this, getString(R.string.demo_compose_page_hidden))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val lifecycle = DemoPage.addSection(page.content, R.string.demo_compose_lifecycle_badge,
            R.string.demo_compose_lifecycle_title, R.string.demo_compose_lifecycle_description, alternate = true)
        lateinit var visibilityButton: Button
        visibilityButton = DemoPage.addButton(lifecycle, R.string.demo_compose_leave) {
            pageIsVisible = !pageIsVisible
            visibilityButton.setText(if (pageIsVisible) R.string.demo_compose_leave else R.string.demo_compose_enter)
        }
        DemoPage.addButton(lifecycle, R.string.demo_compose_rebuild) { pageGeneration++ }
        status = DemoPage.addStatus(lifecycle, getString(R.string.demo_compose_status_initial))
    }

    @Composable
    private fun ToastPage() {
        val scope = rememberKToastScope(KToastScopeOptions(avoidIme = true))
        SideEffect { toastScope = scope }
        AndroidView(
            factory = { context ->
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    DemoPage.addButton(this, R.string.demo_compose_custom, R.style.DemoButton_Primary) {
                        report(scope.showContent("自定义 Compose 保存成功", Toast.LENGTH_LONG, callbacks = callbacks()) {
                            KToastContent("自定义 Compose 内容", style = KToastStyle(KToastType.SUCCESS))
                        })
                    }
                    DemoPage.addButton(this, R.string.demo_compose_plain) {
                        report(scope.show("Compose 保存成功", callbacks = callbacks()))
                    }
                    DemoPage.addButton(this, R.string.demo_compose_success) {
                        report(scope.showStyled("保存成功", KToastStyle(KToastType.SUCCESS), callbacks = callbacks()))
                    }
                    DemoPage.addButton(this, R.string.demo_compose_delayed) {
                        report(scope.showDelayed("旧页面的延时请求", 3_000L, callbacks = callbacks()))
                    }
                    DemoPage.addButton(this, R.string.demo_compose_cancel, R.style.DemoButton_Danger) {
                        scope.cancel()
                        status.setText(R.string.demo_compose_status_canceled)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    fun setPageVisible(visible: Boolean) { pageIsVisible = visible }

    private fun callbacks() = KToastCallbacks(
        onShow = { report(it) },
        onDismiss = { handle, _ -> report(handle) }
    )

    private fun report(handle: ToastHandle) {
        status.text = getString(R.string.demo_compose_status_result, handle.id, handle.state)
    }
}

@Preview
@Composable
private fun DefaultToastPreview() {
    KToastContent("保存成功", style = KToastStyle(KToastType.SUCCESS))
}
