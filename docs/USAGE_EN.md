# Core usage and styling

These examples target the published 1.3.0 release. Semantic styles and animation interpolators were introduced in 1.3.0; the other core APIs have been available since 1.1.0. Start with [installation](../README_EN.md). For page-owned messages, see [Activity scopes](PAGE_SCOPE.md) or [LifecycleOwner scopes](LIFECYCLE_SCOPE.md) (Chinese).

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

## Semantic styles and branding (1.3.0)

This section uses APIs introduced in the published 1.3.0 release. See [installation](../README_EN.md#installation) for dependencies, or the [contribution guide](../CONTRIBUTING.md) for source candidates.

```kotlin
import com.logan.ktoast.KToastScope
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType

fun showSaved(scope: KToastScope) = scope.showStyled(
    "Saved",
    KToastStyle(KToastType.SUCCESS)
) {
    animationDuration = 150L
}
```

`NORMAL` preserves the base appearance; `SUCCESS`, `ERROR`, `WARNING`, and `INFO` provide semantic day/night colors and distinct icons. `KToast`, `KToastScope`, and `KToastLifecycleScope` each provide `showStyled`, returning the existing `ToastHandle`. Java can pass `new KToastStyle(KToastType.SUCCESS)` without a Kotlin DSL.

Use `KToastStyle(KToastType.SUCCESS, R.style.BrandToast)` for XML branding. The order is base configuration → enabled host theme → semantic appearance → brand XML → per-call configuration. The global configuration is not changed. Resources, themes, and the DSL are read on the calling thread; rendering and callbacks remain on the main thread. Queued requests retain their resolved configuration snapshot. Existing `show`, delayed APIs, defaults, and scheduling semantics remain unchanged.

See [semantic styles and branding](STYLES.md) (Chinese) for XML attributes, dp/sp/px units, `values-night`, explicit icon hiding, threading, and system fallback limits.

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

## Animation interpolators (1.3.0)

Use `KToastConfig.animation` to configure entry and exit independently. The same configuration works with global defaults, per-call DSL, page scopes, Lifecycle, and Compose. `animationDuration` still controls duration:

```kotlin
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.logan.ktoast.config.KToastAnimation
import com.logan.ktoast.toast

"Saved".toast {
    animationDuration = 350L
    animation = KToastAnimation(
        enterInterpolator = OvershootInterpolator(4f),
        exitInterpolator = DecelerateInterpolator(),
        maxScale = 1.1f
    )
}
```

Set global defaults with `KToast.config { animation = … }`, or override a request in `scope.show`, `showStyled`, or Compose `showContent`. Java uses `config.setAnimation(new KToastAnimation(enter, exit, 1.1f))` with the existing configuration-object overload.

Both phases retain the original bounce by default; a null interpolator selects `OvershootInterpolator`. `maxScale` defaults to `1.05f` and must be finite and within `1f..2f`. The window reserves transparent space for that scale and the shadow. Interpolator output is bounded to keep the scale inside that space; raise the limit for stronger bounce. Interpolators run on the main thread and must return finite values without blocking. Do not mutate an interpolator after submission. If an interpolator throws an Exception or returns a non-finite value, the library reports an `ERROR` diagnostic, cancels the current request with `CANCELED`, and continues the queue. Background system Toasts use platform animations.

To preserve the released constructor, defaults, and `copy` ABI, `animation` is a class-body property. Use `config.snapshot()` for a complete configuration copy. Generated `copy()`, `equals()`, and `hashCode()` retain their original constructor-property semantics. Library inheritance, style resolution, and request snapshots use the complete copy and keep per-call overrides isolated.

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

1.3.0 includes success, error, warning, and information styles. Use `showStyled` directly for these cases instead of defining colors and icons again. To reuse business messages, add small helpers based on a page scope:

```kotlin
import com.logan.ktoast.KToastScope
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType

fun KToastScope.showSaved() =
    showStyled("Saved", KToastStyle(KToastType.SUCCESS))

fun KToastScope.showSaveFailed() =
    showStyled("Could not save. Please retry.", KToastStyle(KToastType.ERROR))
```

Call `toastScope.showSaved()` or `toastScope.showSaveFailed()` when the save result is available. These helpers keep the page's lifecycle behavior. Use [XML branding](STYLES.md) (Chinese) for brand visuals without changing library sources.

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
