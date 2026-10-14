# 🍞 KToast

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Language](https://img.shields.io/badge/language-Kotlin%20%7C%20Java-orange.svg)

English document [README_EN.md](./README_EN.md)

KToast 是一个面向 Android 的现代 Toast 组件库，提供自动初始化、前台自定义渲染、后台系统降级与灵活样式配置能力，适合直接接入正式业务场景。

下面给出依赖、默认行为和常用接入方式。

## 1.2.0 候选：容量治理与页面生命周期（未发布）

当前正式版本仍为 1.1.0。以下能力已加入本地 `1.2.0-SNAPSHOT` 候选，完整验收状态见 [发布检查表](docs/RELEASING.md)。本地仓库 `build/candidate-repository` 提供核心和 `ktoast-lifecycle` 两个坐标；正式发布前不能直接从 Maven Central 安装该候选。

| 场景 | 入口 | 停止后行为 |
| --- | --- | --- |
| 应用全局提示、沿用旧后台降级 | KToast / toast 扩展 | 保留旧全局语义 |
| 提示属于 Activity | KToastScope | 取消本轮请求，恢复后可复用 |
| 提示属于 Fragment View 或其他 Owner | KToastLifecycleScope | 绑定 viewLifecycleOwner；离开 STARTED 关闭旧代理，恢复只接受新请求 |

三个入口共享串行展示队列。扩展坐标为 `io.github.logan0817:ktoast-lifecycle:1.2.0-SNAPSHOT`，传递依赖同版核心和 lifecycle-common 2.5.1，不增加 Fragment、Compose 或协程依赖；核心单独接入不依赖 Lifecycle。详细接入见 [LifecycleOwner 作用域](docs/LIFECYCLE_SCOPE.md)。

需要容量治理时，在 Application 初始化、首个请求登记前显式配置：

```kotlin
import com.logan.ktoast.KToast
import com.logan.ktoast.runtime.KToastRuntimePolicy

KToast.setRuntimePolicy(KToastRuntimePolicy(
    maxPendingRequests = 32,
    maxQueueWaitMillis = 10_000L,
    maxDedupeEntries = 256
))
```

三个参数默认均为 null，升级后未开启策略保持原规则。示例数值仅用于演示。策略必须在主线程设置，首个请求或旧延时任务登记后冻结；后续调整需在下次进程启动前修改初始化代码。

待处理容量含准备、主线程提交、排队、防抖和延时，展示中的请求不占额度。满额保留旧请求，拒绝新请求且不执行其 DSL；REPLACE 同样可能拒绝。等待有效期从展示资格起算，延时从计划触发、防抖从窗口结束计算，迟到执行不重置截止时间。LRU 历史被淘汰后，相同消息可能再次接受。容量是登记数限制，不是进程内存字节上限。

拒绝与过期仍以 CANCELED 结束，新增只读 `handle.dropReason` 分别表示 CAPACITY_EXCEEDED / EXPIRED，普通取消为 null；原状态与结束原因枚举不变。参数、取消、异常和时效边界见 [API 说明](docs/API.md)。

## 30 秒接入

### 1. 添加仓库

`settings.gradle.kts` 或根 `build.gradle.kts`：

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

### 2. 添加依赖

模块 `build.gradle.kts`：
[![Maven Central](https://img.shields.io/maven-central/v/io.github.logan0817/ktoast.svg?label=Latest%20Release)](https://central.sonatype.com/artifact/io.github.logan0817/ktoast)

```kotlin
dependencies {
    implementation("io.github.logan0817:ktoast:1.1.0") // 替换为上方徽章显示的最新版本
}
```

Groovy 写法：

```gradle
dependencies {
    implementation 'io.github.logan0817:ktoast:1.1.0' // 替换为上方徽章显示的最新版本
}
```

### 3. 直接调用

```kotlin
import com.logan.ktoast.toast

"保存成功".toast()
```

> 默认不需要手动初始化。只要你没有关闭 Jetpack App Startup，KToast 会自动完成初始化。

## 默认行为

第一次上手时，最容易关心的是这 4 件事：

1. 初始化：默认自动初始化。只有你主动关闭 App Startup 时，才需要手动 `KToast.init(app)`。
2. 显示策略：默认是 `REPLACE`，新 Toast 会替换旧 Toast。
3. 前后台差异：旧全局入口在前台优先走自定义气泡，后台或拿不到可用 `Activity` 时尝试降级为系统 Toast。1.1 页面作用域停止时取消，不转移到系统 Toast。
4. 线程：窗口操作与回调在主线程执行；局部样式 DSL 在调用线程准备，避免直接操作 View。

## 快速开始

### 最小可用示例

```kotlin
import com.logan.ktoast.toast

fun showSavedToast() {
    "保存成功".toast()
}
```

### 全局样式

```kotlin
import android.app.Application
import android.graphics.Color
import android.view.Gravity
import com.logan.ktoast.KToast

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KToast.setDebugMode(BuildConfig.DEBUG).config {
            textColor = Color.WHITE
            backgroundColor = Color.parseColor("#E6323232")
            backgroundRadius = 24f
            gravity = Gravity.CENTER
            yOffset = 0
            textSize = 14f
        }
    }
}
```

### 单次样式覆盖

```kotlin
import android.graphics.Color
import com.logan.ktoast.toast

"上传成功".toast {
    backgroundColor = Color.parseColor("#2E7D32")
    textColor = Color.WHITE
    icon = android.R.drawable.ic_dialog_info
    backgroundRadius = 50f
}
```

### Java 调用

```java
import android.graphics.Color;
import android.widget.Toast;
import com.logan.ktoast.KToast;
import kotlin.Unit;

KToast.setDebugMode(BuildConfig.DEBUG);

KToast.config(config -> {
    config.setBackgroundColor(Color.RED);
    return Unit.INSTANCE;
});

KToast.show("Hello from Java", Toast.LENGTH_SHORT);
KToast.showDebug("Debug only");
Runnable task = KToast.showDelayed("Show after 2 seconds", 2000L);
KToast.cancelDelayed(task);
```

## 常见场景

### 1. 延时显示与撤回

```kotlin
import com.logan.ktoast.KToast
import com.logan.ktoast.toastDelayed

val task = "2 秒后显示".toastDelayed(2000L)
KToast.cancelDelayed(task)
```

### 2. 点击立即关闭

```kotlin
import com.logan.ktoast.toast

"点击我可立即关闭".toast {
    cancelOnTouch = true
    animationDuration = 500L
}
```

### 3. 队列展示

```kotlin
import com.logan.ktoast.toastHandle
import com.logan.ktoast.runtime.KToastDisplayMode

listOf("上传 1", "上传 2", "上传 3").forEach { message ->
    message.toastHandle(
        behavior = {
            displayMode = KToastDisplayMode.QUEUE
            groupKey = "upload"
        }
    )
}
```

### 4. 按组取消

```kotlin
import com.logan.ktoast.KToast

KToast.cancelGroup("upload")
```

### 5. 窗口内忽略重复消息

```kotlin
import com.logan.ktoast.toastHandle
import com.logan.ktoast.runtime.KToastDisplayMode

"保存成功".toastHandle(
    behavior = {
        displayMode = KToastDisplayMode.IGNORE_IF_SAME
        windowMillis = 1500L
    }
)
```

### 6. 自定义内容 View

纯代码构建 View：

```kotlin
import android.widget.TextView
import com.logan.ktoast.toastHandle
import com.logan.ktoast.runtime.KToastContentFactory

"自定义布局".toastHandle {
    contentFactory = KToastContentFactory { context, message, config ->
        TextView(context).apply {
            text = ">>> $message"
            setTextColor(config.textColor)
            setPadding(32, 20, 32, 20)
        }
    }
}
```

引用 XML 布局：

`res/drawable/bg_toast_custom.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#E6323232" />
    <corners android:radius="20dp" />
</shape>
```

`res/layout/view_toast_custom.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_toast_custom"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/tvToastTitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="上传状态"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/tvToastMessage"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="6dp" />
</LinearLayout>
```

```kotlin
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.widget.TextView
import com.logan.ktoast.toastHandle
import com.logan.ktoast.runtime.KToastContentFactory

"上传成功".toastHandle {
    contentFactory = KToastContentFactory { context, message, config ->
        LayoutInflater.from(context)
            .inflate(R.layout.view_toast_custom, null, false)
            .apply {
                backgroundTintList = ColorStateList.valueOf(config.backgroundColor)
                findViewById<TextView>(R.id.tvToastTitle).text = "上传状态"
                findViewById<TextView>(R.id.tvToastMessage).apply {
                    text = message
                    textSize = config.textSize
                    setTextColor(config.textColor)
                }
            }
    }
}
```

### 7. App 层二次封装

建议把业务语义放在你自己的 App 层，而不是直接写在每个页面里。

可参考 [AppToastExt.kt](./app/src/main/java/com/logan/ktoastapp/AppToastExt.kt)：

```kotlin
import android.graphics.Color
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.toast

fun CharSequence?.toastSuccess(block: (KToastConfig.() -> Unit)? = null) {
    this.toast {
        backgroundColor = Color.parseColor("#4CAF50")
        icon = android.R.drawable.ic_dialog_info
        iconColor = Color.WHITE
        block?.invoke(this)
    }
}

fun CharSequence?.toastError(block: (KToastConfig.() -> Unit)? = null) {
    this.toast {
        backgroundColor = Color.parseColor("#F44336")
        icon = android.R.drawable.ic_dialog_alert
        iconColor = Color.WHITE
        block?.invoke(this)
    }
}

fun CharSequence?.toastWarning(block: (KToastConfig.() -> Unit)? = null) {
    this.toast {
        backgroundColor = Color.parseColor("#FF9800")
        icon = android.R.drawable.ic_dialog_alert
        iconColor = Color.WHITE
        block?.invoke(this)
    }
}
```

这里为了保证示例开箱即编译，图标用了系统内置 drawable；真实项目里替换成你自己的资源即可。

## 常用配置

第一次接入通常只会改这些高频项：

| 配置项 | 类型 | 默认值 | 说明      |
| :--- | :--- | :--- |:--------|
| `textColor` | Int | `White` | 文本颜色    |
| `backgroundColor` | Int | `#E6323232` | 背景色     |
| `backgroundRadius` | Float | `24f` | 圆角      |
| `gravity` | Int | `BOTTOM + CENTER_HORIZONTAL` | 显示位置    |
| `xOffset` | Int | `0` | 横向偏移（px） |
| `yOffset` | Int | 初始化时读取系统值，失败时 64dp | 纵向偏移（px）；配置类初始值为 64px |
| `animationDuration` | Long | `250` | 动画时长    |
| `cancelOnTouch` | Boolean | `false` | 是否点击关闭  |
| `icon` | Int? | `null` | 图标资源    |
| `maxLines` | Int | `4` | 最大行数    |
| `contentFactory` | `KToastContentFactory?` | `null` | 自定义内容工厂 |

完整配置可查看 [KToastConfig.kt](./library-android/src/main/java/com/logan/ktoast/config/KToastConfig.kt)。

## FAQ

### 1. 为什么我没有手动 `init()` 也能用？

因为默认集成了 Jetpack App Startup。只有你主动关闭它，才需要自己调用：

```kotlin
import com.logan.ktoast.KToast

KToast.init(application)
```

### 2. 为什么前台是自定义样式，后台变成系统 Toast？

这是旧全局入口的降级策略：前台优先走自定义渲染，后台或无可用 `Activity` 时尝试系统 Toast。1.1 页面作用域绑定明确宿主，页面停止或渲染失败时取消，不转移页面或系统 Toast。

### 3. Android 11+ 为什么还需要这套实现？

因为 Android 11+ 对自定义 Toast 做了限制，直接 `Toast.setView()` 不再可靠。KToast 前台使用 `WindowManager` 渲染来保持体验一致。

### 4. 后台测试为什么有时看不到？

后台系统 Toast 仍然受通知权限和 ROM 策略影响。可在 Demo 页面查看权限状态后体验后台场景。

### 5. 混淆要额外配置吗？

通常不用。库里已经包含 `consumer-rules.pro`。

## Demo 与源码入口

1. 示例工程在 [`app`](./app) 模块。
2. [MainActivity.kt](./app/src/main/java/com/logan/ktoastapp/MainActivity.kt) 展示了当前 Demo 页的完整能力入口。
3. 业务封装示例在 [AppToastExt.kt](./app/src/main/java/com/logan/ktoastapp/AppToastExt.kt)。
4. 仓库保留了[历史 Demo APK](./app/apk/app-debug.apk)；体验当前 1.1.0 功能请从源码构建，历史 APK 不作为新功能验证依据。
5. 效果展示 <img src="GIF.gif" width="350" />
## 进阶能力

### 1. 生命周期回调

```kotlin
import com.logan.ktoast.toastHandle
import com.logan.ktoast.runtime.KToastCallbacks

"提交中".toastHandle(
    callbacks = KToastCallbacks(
        onShow = { println("shown: ${it.id}") },
        onDismiss = { _, reason -> println("dismissed: $reason") }
    )
)
```

### 2. 调试模式

```kotlin
import com.logan.ktoast.debugShow

"只在 Debug 模式显示".debugShow()
```

### 3. 调用链

调用链如下：

![KToast 调用链](./docs/images/toast-call-flow-zh.svg)

## 1.1.0：页面作用域与稳定性

1.1.0 提供 Activity 页面作用域、延时句柄、窗口约束、可选输入法避让、无障碍时长、主题与诊断能力。使用规则、Java 示例及兼容边界见 [页面作用域](./docs/PAGE_SCOPE.md)；以下稳定性修复包含在该版本中。

1.1.0 已于 2026-10-13 发布到 Maven Central。该版本保留 Android 5.0+、Kotlin 1.9.23、Startup 1.1.1 和原公开接口，不要求使用方引入 Compose、Material 或协程。

库产物使用 JVM 1.8 字节码，仓库仍用 JDK 17 构建，以降低旧 D8/R8 的接入限制。Kotlin metadata 与依赖要求保持不变，具体实测组合和限制见发布检查表。Demo 的基础提示与普通延时按钮使用页面作用域，可验证页面停止后的取消行为。

- 替换和批量取消先完成内部状态转换，再发送业务回调；回调重入不会留下未结束的旧句柄。
- `ToastHandle.cancel()` 可在工作线程调用，也可在请求等待主线程入队时调用。返回 `true` 表示首次接受取消请求，实际清理和回调在主线程执行；重复取消或终态取消返回 `false`。
- `onCancel`、`onDismiss` 等回调异常会被隔离，不中断后续请求；开启 Debug 模式可查看具体异常。终态回调最多执行一次。
- 旧全局入口在自定义内容创建或窗口挂载失败时尝试系统降级；页面作用域失败时取消，不转移页面或系统 Toast。最终渲染异常以已有 `CANCELED` 原因结束，保持枚举兼容。
- 已挂载提示的宿主停止、销毁或内容意外脱离窗口时，取消该提示并释放任务和监听器。未显示的全局队列仍按既有策略继续处理；页面作用域会取消本页面全部待处理请求。
- 显示后立即取消，即使内容尚未附着也会移除已注册窗口；清理完成后可复用同一个自定义 View。
- 全局 `KToast.cancel()` 清理当前、排队、防抖、等待主线程的提交与延时任务；终态回调中新提交的请求保留。

`onShow` 保留原有“渲染调用返回”的语义；系统 Toast 的完成时间仍由本地计时估计。系统可能排队、限流或拦截，降级与完成回调均不代表送达保证。

版本变化见 [CHANGELOG.md](./CHANGELOG.md)，后续路线见 [版本路线](./docs/ROADMAP.md)，验证矩阵与发布步骤见 [发布检查表](./docs/RELEASING.md)，本地构建与排障见 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)。

## 版本兼容与实现说明

1. 平台：Android 5.0+。
2. 旧全局入口前台优先自定义渲染，后台尝试系统降级；页面作用域随宿主停止取消。
3. Kotlin 为主，也支持 Java 静态调用。
4. 当前仓库同时包含库模块和 Demo 模块，适合直接本地验证。

## License

```text
MIT License

Copyright (c) 2026 Logan Gan

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
