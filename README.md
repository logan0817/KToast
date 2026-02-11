# 🍞 KToast

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Language](https://img.shields.io/badge/language-Kotlin%20%7C%20Java-orange.svg)

英文文档 [English Document](./README_EN.md)

**KToast** 是一个专为 Android 设计的现代化、轻量级且高度可定制的 Toast 框架。基于 Kotlin 编写，完美适配 Android 11+。

### ✨ 核心特性

* 🚀 **极简调用**：Kotlin 扩展函数支持，一行代码搞定显示。
* 🎨 **高度定制**：支持自定义背景、圆角、图标、动画时长、位置偏移等。
* 🛡️ **系统兼容**：完美适配 **Android 11+**。前台使用自定义 Window 渲染（无权限），后台自动降级为系统原生 Toast。
* ⚡ **生命周期感知**：自动感知 Activity 状态，防止内存泄漏和崩溃。
* 👆 **交互增强**：支持点击气泡立即消失 (`cancelOnTouch`)。
* ⏱️ **延时任务**：支持非阻塞的延时显示 (`toastDelayed`) 和撤回机制。
* 🐞 **调试模式**：提供 `debugShow` 仅在开发环境显示，不污染线上环境。
* ☕ **Java 兼容**：对 Java 项目友好，提供静态方法调用。                                                              
---

## 引入

### Gradle:

1. 在Project的 **build.gradle** 或 **setting.gradle** 中添加远程仓库

    ```gradle
    repositories {
        //
        mavenCentral()
    }
    ```

2. 在Module的 **build.gradle** 中添加依赖项
   [![Maven Central](https://img.shields.io/maven-central/v/io.github.logan0817/ktoast.svg?label=Latest%20Release)](https://central.sonatype.com/artifact/io.github.logan0817/ktoast)

    ```gradle
   implementation 'io.github.logan0817:ktoast:1.0.0' // 替换为上方徽章显示的最新版本
    ```

## 效果展示
<img src="GIF.gif" width="350" />

> 你也可以直接下载 [演示App](https://raw.githubusercontent.com/logan0817/KToast/master/app/release/app-debug.apk) 体验效果


### 🚀 快速开始

#### 1. 初始化
在你的 `Application` 类中进行初始化：

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 1. 初始化
        KToast.init(this)
        
        // 2. (可选) 开启 Debug 模式
        KToast.debugMode = BuildConfig.DEBUG
        
        // 3. (可选) 配置全局默认样式
        KToast.config {
            backgroundColor = Color.parseColor("#333333")
            backgroundRadius = 24f
        }
    }
}
```

#### 2. 基础调用

```kotlin
// 显示文本
"Hello KToast!".toast()

// 显示资源 ID
R.string.app_name.toast()
```

#### 3. DSL 个性化配置

```kotlin
"保存成功".toast {
    backgroundColor = Color.parseColor("#4CAF50") // 绿色背景
    textColor = Color.WHITE
    icon = R.drawable.ic_success // 设置图标
    gravity = Gravity.CENTER // 居中显示
    backgroundRadius = 50f // 全圆角
}
```

### 🛠️ 进阶功能

#### 延时显示 (Delayed Show)
支持延时显示，且返回任务句柄，可随时撤回。

```kotlin
// 2秒后显示
val task = "稍后跳转...".toastDelayed(2000L)

// 如果用户提前退出了页面，可以撤回
KToast.cancelDelayed(task)
KToast.cancel()
```

#### 调试模式 (Debug Show)
仅当 `KToast.debugMode = true` 时才会显示，适合打印接口错误或埋点信息。

```kotlin
"API Error: 500".debugShow()
```

#### 可交互 (点击消失)

```kotlin
"点击我可以立即关闭".toast {
    cancelOnTouch = true
    animationDuration = 500L // 慢动作动画
}
```

#### Java 调用

```java
// 初始化
KToast.init(application);
// 显示
KToast.show("来自 Java 的调用");
```

### ⚙️ 配置项详解 (KToastConfig)

你可以在 `KToast.config {}` (全局) 或 `.toast {}` (局部) 中设置以下属性：

| 属性名 | 类型 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `textColor` | Int | White | 文本颜色 |
| `backgroundColor` | Int | #E6323232 | 背景颜色 (支持 Alpha) |
| `backgroundRadius` | Float | 24f | 背景圆角 (dp) |
| `gravity` | Int | Bottom | 显示位置 |
| `xOffset` | Int | 0 | X 轴偏移量 (px) |
| `yOffset` | Int | 64 | Y 轴偏移量 (px) |
| `textSize` | Float | 14f | 字体大小 (sp) |
| `paddingHorizontal`| Int | 24 | 水平内边距 (dp) |
| `paddingVertical` | Int | 12 | 垂直内边距 (dp) |
| `animationDuration`| Long | 250 | 弹出/消失动画时长 (ms) |
| `cancelOnTouch` | Boolean | false | 是否允许点击消失 |
| `icon` | Int? | null | 图标资源 ID |
| `iconSize` | Float | 24f | 图标大小 (dp) |

### 💡 最佳实践 (Best Practices)

**强烈建议**在你的 App 模块中创建一个扩展文件 `AppToastExt.kt`，根据你的 UI 设计规范封装统一的样式。这能保持 Library 的纯净，同时让业务调用更简单。

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

### 🔒 混淆配置 (Proguard)

库内部已经包含了 `consumer-rules.pro`，通常情况下你**不需要**手动添加任何规则。

---

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
