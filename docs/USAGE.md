# 核心用法与样式示例

以下核心 API 已随 1.1.0 发布，适用于当前候选；先完成 [README 安装步骤](../README.md)。页面独有提示优先使用 [Activity 作用域](PAGE_SCOPE.md) 或 [LifecycleOwner 扩展](LIFECYCLE_SCOPE.md)。

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
