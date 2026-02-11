# 🍞 KToast

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Language](https://img.shields.io/badge/language-Kotlin%20%7C%20Java-orange.svg)

Chinese document [中文文档](./README.md)

**KToast** is a modern, lightweight, and highly customizable Toast library designed for Android.

It provides a stunning custom UI experience **across all Android versions (5.0+)**, while elegantly solving the native Toast restrictions introduced in Android 11+.

---
## Documentation

### ✨ Key Features

* 🚀 **Simple API**: Show a toast with a single line of Kotlin code.
* 🎨 **Highly Customizable**: Custom background, radius, icons, animation, position (x/y offset),
  etc.
* 🛡️ **Android 11+ Ready**: Uses a custom Window for foreground (no permissions needed) and
  gracefully downgrades to system Toast for background.
* ⚡ **Lifecycle Aware**: Automatically detects Activity state to prevent memory leaks and crashes.
* 👆 **Interactive**: Supports "Click to Dismiss" (`cancelOnTouch`).
* ⏱️ **Delayed Tasks**: Non-blocking delayed display (`toastDelayed`) with cancellation support.
* 🐞 **Debug Mode**: `debugShow` only displays in dev environments.
* ☕ **Java Compatible**: Friendly static methods for Java callers.

## Integration

### Gradle:

1. Add the remote repository to your Project's **build.gradle** or **setting.gradle**

    ```gradle
    repositories {
        //
        mavenCentral()
    }
    ```

2. Add the dependency to your Module's **build.gradle**
   [![Maven Central](https://img.shields.io/maven-central/v/io.github.logan0817/KToast.svg?label=Latest%20Release)](https://central.sonatype.com/artifact/io.github.logan0817/KToast)

    ```gradle
   implementation 'io.github.logan0817:KToast:1.0.1' // Replace with the latest version shown by the badge above
    ```

## Demo Effect

<img src="GIF.gif" width="350" />

> You can also directly download
> the [Demo App](https://raw.githubusercontent.com/logan0817/KToast/master/app/release/app-debug.apk)
> to experience the effect.

### 🚀 Quick Start

#### 1. Initialization

Initialize it in your `Application` class:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        //✅ 场景一：最简初始化 (什么都不配)
        KToast.init(this)
        //✅ 场景二：只开 Debug (常用)
        KToast.init(this, isDebug = BuildConfig.DEBUG)
        //✅ 场景三：全功能初始化 (一步到位)
        KToast.init(this, isDebug = BuildConfig.DEBUG) {
            textColor = Color.WHITE
            backgroundColor = Color.parseColor("#E6323232")
            backgroundRadius = 24f
            gravity = Gravity.CENTER
        }
    }
}
```

#### 2. Basic Usage

```kotlin
// Show Text
"Hello KToast!".toast()

// Show Resource ID
R.string.app_name.toast()
```

#### 3. Customization (DSL)

```kotlin
"Saved Successfully".toast {
    backgroundColor = Color.parseColor("#4CAF50") // Green
    textColor = Color.WHITE
    icon = R.drawable.ic_success
    gravity = Gravity.CENTER
    backgroundRadius = 50f
}
```

### 🛠️ Advanced Usage

#### Delayed Show

Returns a `Runnable` handle, allowing you to cancel the task before it shows.

```kotlin
// Show after 2 seconds
val task = "Redirecting...".toastDelayed(2000L)

// Cancel it if needed (e.g., user left the page)
KToast.cancelDelayed(task)
KToast.cancel()
```

#### Debug Show

Only shows when `KToast.debugMode = true`.

```kotlin
"API Error: 500".debugShow()
```

#### Click to Dismiss

```kotlin
"Click me to dismiss".toast {
    cancelOnTouch = true
    animationDuration = 500L
}
```

#### Java Usage

```java
// init
KToast.init(application);

KToast.init(this, true);

KToast.init(this, true, config -> {
        config.setBackgroundColor(Color.RED);
    return Unit.INSTANCE;
});

// show
KToast.show("Hello from Java");
```

### ⚙️ Configuration (KToastConfig)

Properties available in `KToast.config {}` or `.toast {}`:

| Property            | Type    | Default   | Description                        |
|:--------------------|:--------|:----------|:-----------------------------------|
| `textColor`         | Int     | White     | Text color                         |
| `backgroundColor`   | Int     | #E6323232 | Background color (Alpha supported) |
| `backgroundRadius`  | Float   | 24f       | Corner radius (dp)                 |
| `gravity`           | Int     | Bottom    | Position gravity                   |
| `xOffset`           | Int     | 0         | X-axis offset (px)                 |
| `yOffset`           | Int     | 64        | Y-axis offset (px)                 |
| `textSize`          | Float   | 14f       | Text size (sp)                     |
| `paddingHorizontal` | Int     | 24        | Horizontal padding (dp)            |
| `paddingVertical`   | Int     | 12        | Vertical padding (dp)              |
| `animationDuration` | Long    | 250       | Animation duration (ms)            |
| `cancelOnTouch`     | Boolean | false     | Dismiss on click                   |
| `icon`              | Int?    | null      | Icon Resource ID                   |
| `iconSize`          | Float   | 24f       | Icon size (dp)                     |

### 💡 Best Practices

**Strongly Recommended**Create an extension file `AppToastExt.kt` in your app module to encapsulate a consistent style according to your UI design guidelines. This will keep the library clean and simplify business logic calls.
#### For APP layer extensions, please refer to [AppToastExt](./app/src/main/java/com/logan/ktoastapp/AppToastExt.kt)
```kotlin
// AppToastExt.kt

/** 显示成功提示 (绿色) */
fun CharSequence.showSuccess() {
    this.toast {
        backgroundColor = Color.parseColor("#4CAF50")
        icon = R.drawable.ic_check_circle
    }
}

/** 显示错误提示 (红色) */
fun CharSequence.showError() {
    this.toast {
        backgroundColor = Color.parseColor("#F44336")
        icon = R.drawable.ic_error
    }
}

// 调用
"登录成功".showSuccess()
```

### License

```

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
