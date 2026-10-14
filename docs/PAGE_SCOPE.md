# Activity 页面作用域（1.1.0）

这些接口已随 1.1.0 发布到 Maven Central，正式安装坐标为 io.github.logan0817:ktoast:1.1.0。接入示例见 [README](../README.md)，验证证据与兼容限制见 [发布检查表](./RELEASING.md)。

## 接入与生命周期

`KToastScope.create(activity)` 必须在主线程创建，建议在 Activity 的 onCreate 中保存，在按钮事件等页面已启动的阶段显示。默认通过已有 Startup 初始化，关闭 Startup 时须先在 Application.onCreate 调用 KToast.init。

```kotlin
import android.app.Activity
import android.os.Bundle
import com.logan.ktoast.KToastScope
import com.logan.ktoast.runtime.ToastHandle

class EditorActivity : Activity() {
    private lateinit var toastScope: KToastScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toastScope = KToastScope.create(this)
    }

    fun onSaved() {
        toastScope.show("保存成功") {
            animationDuration = 150L
        }
    }

    fun showLater(): ToastHandle =
        toastScope.showDelayed("稍后提示", 2_000L)
}
```

示例中的 onSaved/showLater 由页面已启动后的点击等事件调用。作用域自动观察 Activity 停止和销毁，无需为了自动清理再覆盖 onDestroy；业务需要提前结束时可显式 close。

如果仅关闭本库的自动初始化，在应用 Manifest 合并规则中移除对应 metadata，并在 Application.onCreate 调用 KToast.init(this)，不要移除其他库需要的 Startup 初始化项：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application>
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="com.logan.ktoast.initializer.KToastInitializer"
                tools:node="remove" />
        </provider>
    </application>
</manifest>
```

页面作用域使用明确的 Activity，持有宿主弱引用，不通过全局栈顶选择其他页面。Activity 停止时，取消该作用域当前、排队、防抖、待主线程提交和延时请求；销毁时永久关闭并注销监听器。停止期间、关闭后、宿主结束后的新请求以 CANCELED 结束。停止后再回到前台可继续使用尚未关闭的作用域；Activity 重建后必须为新实例创建新作用域。

显示、cancel 和 close 可从工作线程调用。实际窗口操作、终态回调与诊断回调均在主线程执行。close 会立即标记关闭，后续主线程执行清理；可重复调用。cancel 清理该作用域全部当前和待处理请求及去重历史，不关闭作用域，取消回调中新提交的请求可保留。close 后回调中新提交的请求也会取消。

请求在调用时记录页面启动状态及取消代次。工作线程准备配置期间如果页面停止或主线程执行了 cancel，即使主线程处理请求时页面已恢复，也会以 CANCELED 结束；停止期间发起的请求也不会在恢复后重新显示。取消边界先更新，再执行请求清理，取消回调中发起的新请求属于新的代次，可继续显示。局部配置 DSL 在调用线程执行，请保持轻量并避免操作 View；需要构建 View 时使用在主线程执行的 contentFactory。

所有页面作用域共享同一串行队列，保证同一时刻只呈现一个请求：

- 作用域 REPLACE 仅替换本作用域当前、排队和防抖请求；其他作用域正在显示时，新请求进入队列。
- 每个作用域有独立防抖槽位与去重历史。cancel/停止/关闭清理本作用域历史。
- 旧全局 REPLACE 和 KToast.cancel 仍可影响全局及页面请求，保留全局控制含义。
- KToast.cancelGroup 按 groupKey 清理所有入口的匹配请求，包括尚未提交的作用域延时请求。
- 新延时请求返回 ToastHandle，延时从调用时开始调度；页面变化不会转移宿主。旧 toastDelayed 仍返回 Runnable。

队列和旧全局去重历史没有新增默认容量或过期上限。长期高频业务应主动取消请求，后续容量策略另行设计。

## 现代窗口与无障碍适配

```kotlin
import com.logan.ktoast.KToastScope
import com.logan.ktoast.KToastScopeOptions

val scope = KToastScope.create(
    this,
    KToastScopeOptions(
        avoidSystemBars = true,
        avoidIme = true,
        useAccessibilityTimeout = true,
        followHostTheme = true
    )
)
```

| 设置 | 默认值 | 行为 |
| --- | --- | --- |
| avoidSystemBars | true | 使用宿主窗口尺寸，避让系统栏；API 30+ 同时考虑显示缺口 |
| avoidIme | false | 显式开启后避让可见输入法；显示期间随宿主 Insets/布局变化重算 |
| useAccessibilityTimeout | true | API 29+ 使用平台推荐时长，按文字、图标及显示时可见且启用的点击/长按控件标记计算，包含自定义子控件；较低版本保留 2,000/3,500ms |
| followHostTheme | false | 每次提交时读取宿主主题的 colorBackground/textColorPrimary；局部 DSL 或完整配置可覆盖 |
| diagnostics | null | 可选诊断监听器，不添加全局事件总线 |

页面提示的根容器约束整个内容的宽高，包含图标、内边距和自定义 View。`maxWidthRatio` 相对宿主可用宽度；页面入口将非有限值回退为 0.8，其他值限制在 0.1 至 1。Gravity 先按宿主布局方向处理 START/END，然后把完整内容及偏移约束在安全区域中。显示期间宿主布局方向、窗口尺寸或 Insets 变化会重新计算位置；观察器不覆盖宿主自己的 Insets 监听器。极大内容可能被裁剪，内容工厂应提供能适应窗口的布局。

默认气泡也由这个整体约束分配文字宽度，不再给文字重复套用原始比例，避免 NaN 等值导致文字宽度为零。极小宽度或过大图标、内边距仍可能挤占文字空间，调用方需要提供适合窗口的样式；旧全局入口的文字宽度规则保持不变。

API 30+ 读取当前窗口指标和 Insets，旧版本使用宿主 DecorView 与可见区域作为保守适配。旧平台无法始终准确区分所有系统栏、输入法和厂商浮动窗口场景，需要设备验证。旧全局入口继续使用原有宽度、偏移和时长规则。

自定义 View 不得已有父容器。页面提示会加入内部根容器，清理时从该容器移除，以便复用。cancelOnTouch=true 会由库接管根内容的点击监听器，结束后清除库监听器；需要复杂业务交互时建议在子 View 安装监听器。cancelOnTouch=false 时窗口透传普通触摸，但自定义控件仍可能提供无障碍点击操作，因此推荐时长不会仅凭该开关判断是否含控件。时长在显示时计算，后续内容交互状态变化不会重置计时。创建、测量、挂载和更新失败时，页面请求取消，不转移到其他页面或系统 Toast。

## Java 与诊断

```java
import android.app.Activity;
import android.os.Bundle;
import com.logan.ktoast.KToastScope;
import com.logan.ktoast.config.KToastConfig;
import com.logan.ktoast.runtime.ToastHandle;

public final class JavaEditorActivity extends Activity {
    private KToastScope scope;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        scope = KToastScope.create(this);
    }

    public ToastHandle onSaved() {
        KToastConfig style = new KToastConfig();
        style.setTextSize(16f);
        return scope.show("保存成功", style);
    }

    public void cancelDelayedExample() {
        ToastHandle delayed = scope.showDelayed("稍后提示", 2000L);
        delayed.cancel();
    }
}
```

传入的完整样式配置会被复制；该重载不应用主题默认色。公开配置类的原有构造、copy 和枚举没有改变。

```kotlin
import com.logan.ktoast.KToastScopeOptions
import com.logan.ktoast.runtime.KToastDiagnosticListener

val options = KToastScopeOptions(
    diagnostics = KToastDiagnosticListener { handle, event, detail ->
        // 可按 handle.id 关联业务日志；避免记录敏感消息。
    }
)
```

诊断事件为 SHOWN、DISMISSED、ERROR。SHOWN 表示渲染调用返回，DISMISSED 的 detail 是已有结束原因，ERROR 的 detail 只包含异常类型，不包含消息正文或异常消息。监听器异常被隔离。诊断是可选观察能力，不保证真正送达，也不改变业务回调的兼容语义。

当前验证矩阵与发布前未覆盖项见 [RELEASING.md](./RELEASING.md)。Compose 扩展仍属于后续独立模块，本版核心没有新增 Compose、协程或 lifecycle 生产依赖。

## 1.2.0 可选扩展（未发布）

Activity 作用域的停止/恢复语义保持不变。Fragment View 应使用独立 [LifecycleOwner 扩展](LIFECYCLE_SCOPE.md)，绑定 viewLifecycleOwner；Owner 每次 STARTED 使用新代理，停止后旧请求不复活。所有入口共享显式设置的 KToastRuntimePolicy，容量拒绝和等待过期通过 ToastHandle.dropReason 表达，详见 [API 说明](API.md)。未设置策略保持旧默认行为。
