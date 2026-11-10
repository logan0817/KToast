# 核心用法与样式示例

除明确标注的 1.3.0-SNAPSHOT 候选章节外，以下核心 API 从 1.1.0 起可用，适用于当前正式 1.2.0；先完成 [README 安装步骤](../README.md)。页面独有提示优先使用 [Activity 作用域](PAGE_SCOPE.md) 或 [LifecycleOwner 扩展](LIFECYCLE_SCOPE.md)。

[English](USAGE_EN.md) · [文档导航](README.md)

## 全局样式

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

## 单次样式覆盖

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

## 语义样式与品牌主题（1.3.0-SNAPSHOT 候选，未发布）

公开安装版本仍为 1.2.0，不能直接调用本节新增 API。下面示例用于 1.3.0-SNAPSHOT 源码或本地候选；候选构建见[贡献指南](../CONTRIBUTING.md)。

```kotlin
import com.logan.ktoast.KToastScope
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType

fun showSaved(scope: KToastScope) = scope.showStyled(
    "保存成功",
    KToastStyle(KToastType.SUCCESS)
) {
    animationDuration = 150L
}
```

`NORMAL` 保留基础外观，`SUCCESS`、`ERROR`、`WARNING`、`INFO` 提供对应日夜配色和独立图标。`KToast`、`KToastScope`、`KToastLifecycleScope` 均提供返回原 `ToastHandle` 的 `showStyled`；Java 可以传 `new KToastStyle(KToastType.SUCCESS)`，无需 DSL。

品牌使用 `KToastStyle(KToastType.SUCCESS, R.style.BrandToast)`。解析顺序为基础配置 → 启用的宿主主题 → 语义 → 品牌 XML → 单次配置；不会修改全局配置。资源/主题及 DSL 在调用线程准备，渲染与回调仍在主线程；已排队请求保存当次配置副本。旧 `show`、延时入口、默认行为和调度规则保持不变。

XML 属性、dp/sp/px 单位、`values-night`、图标隐藏、线程和系统降级边界见[语义样式与品牌主题](STYLES.md)。

## Java 调用

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

## 动画插值器（1.3.0-SNAPSHOT 候选，未发布）

前台自定义 Toast 支持分别设置入场和退场插值器。全局配置、单次 DSL、页面作用域、Lifecycle 和 Compose 的配置方式相同，时长仍使用 `animationDuration`：

```kotlin
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.logan.ktoast.config.KToastAnimation
import com.logan.ktoast.toast

"保存成功".toast {
    animationDuration = 350L
    animation = KToastAnimation(
        enterInterpolator = OvershootInterpolator(4f),
        exitInterpolator = DecelerateInterpolator(),
        maxScale = 1.1f
    )
}
```

`KToast.config { animation = … }` 设置全局默认；`scope.show`、`showStyled` 和 Compose `showContent` 的 DSL 可单次覆盖。Java 使用 `config.setAnimation(new KToastAnimation(enter, exit, 1.1f))`，再将配置传入已有配置对象入口。

默认入场和退场均保留原有回弹，插值器为 `null` 表示使用默认 `OvershootInterpolator`。`maxScale` 默认 `1.05f`，取值为有限的 `1f..2f`：窗口会为该放大上限及阴影预留透明边距。自定义插值器超过此缩放范围时按边界截断，避免圆角、边框在回弹峰值被裁剪；较强回弹可同时调大上限。插值器在主线程执行，必须返回有限数值，不能执行耗时工作；请求提交后不要修改其内部状态。如果插值器抛出 Exception 或返回非有限值，库报告 `ERROR` 诊断并将当前请求取消为 `CANCELED`，随后继续队列。后台系统 Toast 使用系统动画，不读取这些配置。

为保持已发布的 `KToastConfig` 主构造、默认参数及 `copy` 二进制接口，`animation` 是类体属性。复制完整配置请调用 `config.snapshot()`；Kotlin 生成的 `copy()`、`equals()`、`hashCode()` 仍只处理原有主构造属性。库内部的全局继承、样式解析和请求快照均使用完整复制，单次覆盖不会修改全局或已排队请求。

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

可参考 [AppToastExt.kt](../app/src/main/java/com/logan/ktoastapp/AppToastExt.kt)：

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

## 防抖与取消句柄

`DEBOUNCE` 在同一作用域连续调用时保留最后一条，等待窗口结束再提交展示；默认窗口为 300ms，可通过 `windowMillis` 设置。

```kotlin
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.toastHandle

val handle = "搜索完成".toastHandle(
    behavior = {
        displayMode = KToastDisplayMode.DEBOUNCE
        windowMillis = 500L
    }
)
handle?.cancel()
```

`CharSequence?` 扩展忽略 `null` 和空内容，`toastHandle` / `toastDelayed` 因此返回可空句柄/任务。页面 `scope.show` 和 `showDelayed` 始终返回句柄。全局 `KToast.cancel()` 与全局替换保留跨作用域控制含义；页面独有消息优先使用作用域。

## 常用配置

第一次接入通常只会改这些高频项：

| 配置项 | 类型 | 默认值 | 说明      |
| :--- | :--- | :--- |:--------|
| `textColor` | Int | `0xFFFFFFFF` | 文本颜色    |
| `backgroundColor` | Int | `#E6323232` | 背景色     |
| `backgroundRadius` | Float | `24f` | 圆角（dp） |
| `gravity` | Int | `BOTTOM or CENTER_HORIZONTAL` | 显示位置    |
| `xOffset` | Int | `0` | 横向偏移（px） |
| `yOffset` | Int | 初始化时读取系统值，失败时 64dp | 纵向偏移（px）；配置类初始值为 64px |
| `animationDuration` | Long | `250` | 动画时长（ms） |
| `cancelOnTouch` | Boolean | `false` | 是否点击关闭  |
| `icon` | Int? | `null` | 图标资源    |
| `maxLines` | Int | `4` | 最大行数    |
| `contentFactory` | `KToastContentFactory?` | `null` | 自定义内容工厂 |

完整配置可查看 [KToastConfig.kt](../library-android/src/main/java/com/logan/ktoast/config/KToastConfig.kt)。

## 生命周期回调

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

## 调试模式

```kotlin
import com.logan.ktoast.debugShow

"只在 Debug 模式显示".debugShow()
```
