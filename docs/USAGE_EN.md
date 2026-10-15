# Core usage and styling

These core APIs are available in 1.1.0 and the current candidate. Start with [installation](../README_EN.md). For page-owned messages, see [Activity scopes](PAGE_SCOPE.md) or [LifecycleOwner scopes](LIFECYCLE_SCOPE.md) (Chinese).

[简体中文](USAGE.md) · [Documentation index](README.md)

## Global style

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

## Per-call style override

```kotlin
import android.graphics.Color
import com.logan.ktoast.toast

"Uploaded".toast {
    backgroundColor = Color.parseColor("#2E7D32")
    textColor = Color.WHITE
    icon = android.R.drawable.ic_dialog_info
    backgroundRadius = 50f
}
```

## Java usage

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

## Common Scenarios

### 1. Delayed show and revoke

```kotlin
import com.logan.ktoast.KToast
import com.logan.ktoast.toastDelayed

val task = "Show after 2 seconds".toastDelayed(2000L)
KToast.cancelDelayed(task)
```

### 2. Tap to dismiss

```kotlin
import com.logan.ktoast.toast

"Tap me to dismiss".toast {
    cancelOnTouch = true
    animationDuration = 500L
}
```

### 3. Queue mode

```kotlin
import com.logan.ktoast.toastHandle
import com.logan.ktoast.runtime.KToastDisplayMode

listOf("Upload 1", "Upload 2", "Upload 3").forEach { message ->
    message.toastHandle(
        behavior = {
            displayMode = KToastDisplayMode.QUEUE
            groupKey = "upload"
        }
    )
}
```

### 4. Cancel by group

```kotlin
import com.logan.ktoast.KToast

KToast.cancelGroup("upload")
```

### 5. Ignore duplicate messages within a window

```kotlin
import com.logan.ktoast.toastHandle
import com.logan.ktoast.runtime.KToastDisplayMode

"Saved".toastHandle(
    behavior = {
        displayMode = KToastDisplayMode.IGNORE_IF_SAME
        windowMillis = 1500L
    }
)
```

### 6. Custom content View

Build the view directly in code:

```kotlin
import android.widget.TextView
import com.logan.ktoast.toastHandle
import com.logan.ktoast.runtime.KToastContentFactory

"Custom layout".toastHandle {
    contentFactory = KToastContentFactory { context, message, config ->
        TextView(context).apply {
            text = ">>> $message"
            setTextColor(config.textColor)
            setPadding(32, 20, 32, 20)
        }
    }
}
```

Reference an XML layout:

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
        android:text="Upload status"
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

"Uploaded".toastHandle {
    contentFactory = KToastContentFactory { context, message, config ->
        LayoutInflater.from(context)
            .inflate(R.layout.view_toast_custom, null, false)
            .apply {
                backgroundTintList = ColorStateList.valueOf(config.backgroundColor)
                findViewById<TextView>(R.id.tvToastTitle).text = "Upload status"
                findViewById<TextView>(R.id.tvToastMessage).apply {
                    text = message
                    textSize = config.textSize
                    setTextColor(config.textColor)
                }
            }
    }
}
```

### 7. App-level wrapper

You should keep business semantics inside your app layer instead of hard-coding them into every screen.

See [AppToastExt.kt](../app/src/main/java/com/logan/ktoastapp/AppToastExt.kt):

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

The example uses built-in Android drawables so it compiles out of the box. Replace them with your own brand assets in a real app.

## Debouncing and request handles

`DEBOUNCE` keeps the last call in the same scope and submits it when the window ends. The default window is 300ms; set `windowMillis` to change it.

```kotlin
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.toastHandle

val handle = "Search complete".toastHandle(
    behavior = {
        displayMode = KToastDisplayMode.DEBOUNCE
        windowMillis = 500L
    }
)
handle?.cancel()
```

`CharSequence?` extensions ignore null or empty content, so `toastHandle` / `toastDelayed` return nullable handles/tasks. Page `scope.show` and `showDelayed` always return a handle. Global `KToast.cancel()` and global replacement keep their control across scopes; prefer scopes for page-owned messages.

## Common Configs

These are the high-frequency options most users tweak first:

| Option | Type | Default | Description            |
| :--- | :--- | :--- |:-----------------------|
| `textColor` | Int | `0xFFFFFFFF` | Text color             |
| `backgroundColor` | Int | `#E6323232` | Background color       |
| `backgroundRadius` | Float | `24f` | Corner radius in dp |
| `gravity` | Int | `BOTTOM or CENTER_HORIZONTAL` | Position               |
| `xOffset` | Int | `0` | Horizontal offset in px |
| `yOffset` | Int | System value at initialization; 64dp fallback | Offset in px; raw config starts at 64px |
| `animationDuration` | Long | `250` | Animation duration in ms |
| `cancelOnTouch` | Boolean | `false` | Dismiss on tap         |
| `icon` | Int? | `null` | Icon resource          |
| `maxLines` | Int | `4` | Max text lines         |
| `contentFactory` | `KToastContentFactory?` | `null` | Custom content factory |

For the full set, see [KToastConfig.kt](../library-android/src/main/java/com/logan/ktoast/config/KToastConfig.kt).

## Lifecycle callbacks

```kotlin
import com.logan.ktoast.toastHandle
import com.logan.ktoast.runtime.KToastCallbacks

"Submitting".toastHandle(
    callbacks = KToastCallbacks(
        onShow = { println("shown: ${it.id}") },
        onDismiss = { _, reason -> println("dismissed: $reason") }
    )
)
```

## Debug-only toasts

```kotlin
import com.logan.ktoast.debugShow

"Shown only in debug mode".debugShow()
```
