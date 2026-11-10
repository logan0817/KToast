# Compose 页面提示

`ktoast-compose` 是 1.3.0 的独立可选模块，源码已进入正式候选准备，本地验证使用 `1.3.0-SNAPSHOT`，尚未正式发布到 Maven Central。它复用同版本核心和 `ktoast-lifecycle`，提供 Composition 所拥有的页面作用域及自定义 Composable 内容；没有另一套 Host、队列或状态总线。

## 依赖与宿主

本仓库 Demo 通过项目依赖开发：

```kotlin
dependencies {
    implementation(project(":library-compose"))
}
```

独立消费者须先生成本地 Candidate 并指向该候选仓库，不能把开发版本当作 Maven Central 已可安装的版本。构建入口见[贡献指南](../CONTRIBUTING.md)。

本模块最低 Android API 21，库字节码为 JVM 1.8、Kotlin metadata 1.9。当前构建配置采用 Kotlin 1.9.23、Compose Compiler 1.5.13；依赖 Compose Runtime/UI/Foundation 1.7.8、Lifecycle Runtime Compose 2.8.3、Activity 1.9.2 和 SavedState 1.2.1。Runtime/UI 和同版本生命周期模块属于 API 依赖，其余为实现依赖；依赖的实际解析结果仍须由应用确认。核心和生命周期模块单独接入不引入 Compose。Compose 应用还须启用自己的 Compose 构建配置，不能仅依赖库的字节码目标推断编译器兼容性。

当前支持 `ComponentActivity` 及其子类宿主，包括通常的 `AppCompatActivity`。绑定使用调用处真实的 `LocalLifecycleOwner`、`LocalSavedStateRegistryOwner` 和父 Composition。导航或嵌套页面可以提供自己的 Owner；库不伪造生命周期，也不会把未支持的宿主静默转为系统 Toast。非 Preview 的不支持宿主在 Composition 提交绑定时抛出明确异常。

## 事件入口

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.logan.ktoast.compose.rememberKToastScope
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType

@Composable
fun SaveFeedback() {
    val toastScope = rememberKToastScope()
    BasicText(
        text = "保存成功后提示",
        modifier = Modifier.clickable {
            toastScope.showStyled("保存成功", KToastStyle(KToastType.SUCCESS))
        }
    )
}
```

`rememberKToastScope` 在 Composition 提交后的 `DisposableEffect` 中绑定；普通重组复用当前入口，不重复注册监听。不要在 Composable 函数体直接调用 `show`，也不要在 `remember` 的初始化代码中显示。点击事件可以直接调用。

响应受控事件时，也可以在 `LaunchedEffect(eventId)` 中调用；key 应表达需要处理的实际事件。不要用可能长时间保持为 `true` 的成功状态推断“只发送一次”，也不要承诺页面重建后自动去重。应用负责事件的消费语义，库继续遵循显式配置的去重规则。

`show`、`showStyled`、`showDelayed`、`showContent`、`cancel`、`close` 可跨线程调用。局部配置在调用线程执行，Composable 内容、窗口操作、终态回调和观察器清理在主线程执行。配置对象和主题不要与准备请求的线程并发修改；可观察的内容变化使用 Compose State。

## 自定义内容

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import com.logan.ktoast.compose.rememberKToastScope

@Composable
fun CustomFeedback() {
    val toastScope = rememberKToastScope()
    BasicText("显示内容", modifier = Modifier.clickable {
        toastScope.showContent(description = "设置已保存，下一次启动生效") {
            Column {
                BasicText("设置已保存")
                BasicText("下一次启动生效")
            }
        }
    })
}
```

`description` 必须非空白。它会转成不可变字符串快照，作为核心消息、去重依据及窗口无障碍描述；自定义 UI 的可读说明应与实际内容一致。局部配置在前执行，随后本模块设置受管理的 `contentDescription` 与 `contentFactory`。`showContent` 在提交时快照回调函数，不在后续读取调用方可变的回调容器。

请求只有实际开始展示才创建 `ComposeView`，挂载时建立子 Composition，继承调用处的 CompositionLocal 和应用主题。排队、去重、替换、容量拒绝、等待过期及 `ToastHandle` 与其他入口相同。未展示即取消时不创建 Composition；终态释放内容引用，已显示内容在取消、替换或窗口移除时释放 Composition。

自定义内容由应用负责绘制背景、内边距和文字；可使用下方的 `KToastContent`。现有窗口边界、系统栏和可选 IME 避让继续由页面作用域管理。页面窗口不获得焦点，不适合输入框或必须完成的操作；需要用户操作的提示应评估 Snackbar。

## 页面停止、离开与关闭

页面离开 STARTED 时取消本轮请求，恢复后只接受新请求。离开 Composition、Owner 销毁或显式 `close()` 后永久关闭，移除监听并释放宿主、Owner 和父 Composition 引用。Activity、导航 Owner、SavedState Owner、父 Composition 或 options 改变时，旧入口关闭并绑定新入口。

关闭后的 `KToastComposeScope` 新显示或 `cancel()` 调用抛 `IllegalStateException`，应使用当前 Composition 的新入口。这与旧 `KToastLifecycleScope` 关闭后仍返回取消句柄的规则不同；已经返回的句柄仍按核心契约进入终态，`close()` 本身可重复调用。停止期间尚未永久关闭的入口仍返回取消请求，不等待页面恢复。

库不会推断始终保留在 Composition 且未改变 Owner 状态的页面是否可见；这类路由应显式取消请求或提供正确的页面 Owner。不要把作用域保存到 ViewModel、单例或跨 Activity 使用。

## 默认内容与 Preview

```kotlin
import androidx.compose.runtime.Composable
import com.logan.ktoast.compose.KToastContent
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType

@Composable
fun SuccessToastPreviewContent() {
    KToastContent("保存成功", style = KToastStyle(KToastType.SUCCESS))
}
```

`KToastContent` 只绘制内容，不初始化 KToast、不注册监听、不显示窗口，可直接放入应用自己的 `@Preview`。它通过 `KToastStyle.resolve` 复用 View 入口的语义颜色、图标和品牌 XML，顺序为基础 config、语义类型、品牌资源；本模块不强制 Material 依赖。尺寸上限和定位仍属于宿主窗口，单独 Preview 不等价于真实窗口验证。

Preview 中 `rememberKToastScope` 不绑定窗口，也不会因缺少真实 Activity 而在 remember 初始化时抛错；显示或取消操作明确抛出 Preview 禁用异常。预览内容应使用 `KToastContent`，不在预览函数中触发显示。

## 异常与验证边界

局部配置异常向调用方传播并释放本次准备资源。同步内容工厂或窗口挂载失败走核心清理和错误路径；库不把用户 Composable 中的编程错误吞掉。**用户内容的首次组合和后续重组异常都遵循应用 Compose 异常机制**，不能依赖 Toast 自动取消并继续来恢复异常的 UI。`onShow` 只表示渲染调用返回，不保证子 Composition 已执行或用户实际看到了提示。

真实示例见 Demo 的 `ComposeToastActivity`；设备测试覆盖父 CompositionLocal 与真实 Owner、Composition 离开后的取消、Owner 更换和销毁、窗口移除后的释放及队列推进、配置异常、普通重组和 Preview 禁用。实际构建、R8 和设备结果以本版本验收记录为准，示例存在或 APK 编译成功不等于所有场景已经运行通过。
