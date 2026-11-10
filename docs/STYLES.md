# 语义样式与品牌主题

> 本文 API 从 **1.3.0** 起提供，已发布到 Maven Central。按 [README](../README.md#安装) 使用同版正式依赖；源码与本地 SNAPSHOT 验证见[贡献指南](../CONTRIBUTING.md)。

`KToastStyle` 为现有请求选择语义和品牌视觉，不改变队列、去重、取消或生命周期规则。普通 `show`、`showHandle`、`toast` 及现有配置默认值保持不变；只有显式调用 `showStyled` 才使用新样式。

## 选择语义

```kotlin
import com.logan.ktoast.KToast
import com.logan.ktoast.KToastScope
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType
import com.logan.ktoast.runtime.ToastHandle

val successStyle = KToastStyle(KToastType.SUCCESS)

fun showSaved(scope: KToastScope): ToastHandle =
    scope.showStyled("保存成功", successStyle)

// 应用全局消息；页面消息优先使用上面的明确作用域。
val globalHandle = KToast.showStyled("同步完成", successStyle)
globalHandle.cancel()
```

| 类型 | 默认语义外观 |
| --- | --- |
| `NORMAL` | 保留基础颜色、图标和其他配置，可继续叠加品牌 XML |
| `SUCCESS` | 成功配色与勾形图标 |
| `ERROR` | 错误配色与叉形图标 |
| `WARNING` | 警告配色与三角叹号图标 |
| `INFO` | 信息配色与圆形信息图标 |

四种语义类型根据本次配置解析所用 Context 的日夜模式选择配色，同时设置背景、文字、图标和图标色。它们不修改字号、位置、动画或自定义内容工厂，也不会在消息前自动插入类型文字。提示正文仍须表达完整含义，不能只依赖颜色或图标传达结果。

## 全局、Activity 与 LifecycleOwner

三个入口提供相同的 `showStyled(message, style, duration, behavior, callbacks, localConfig)`，均返回原有 `ToastHandle`；显示时长、行为、回调和单次配置均可使用默认值。Java 有默认重载，无需编写 Kotlin DSL：

```java
import com.logan.ktoast.KToast;
import com.logan.ktoast.KToastScope;
import com.logan.ktoast.config.KToastStyle;
import com.logan.ktoast.config.KToastType;
import com.logan.ktoast.lifecycle.KToastLifecycleScope;
import com.logan.ktoast.runtime.ToastHandle;

KToastStyle style = new KToastStyle(KToastType.INFO);
ToastHandle global = KToast.showStyled("同步完成", style);

// activityScope 与 ownerScope 分别为已创建的页面作用域。
ToastHandle page = activityScope.showStyled("已保存", style);
ToastHandle owner = ownerScope.showStyled("已保存", style);
```

Activity 的创建与停止规则见[页面作用域](PAGE_SCOPE.md)，LifecycleOwner/Fragment View 的绑定见[生命周期扩展](LIFECYCLE_SCOPE.md)。`KToastLifecycleScope` 仍需要同版可选生命周期模块，样式本身不为核心增加 Lifecycle、Compose、Material 或协程依赖。

三个入口共享现有串行调度器。全局替换、全局取消仍有跨作用域控制含义；页面停止时取消其请求，恢复不会复活旧请求。首版没有新增 styled 延时入口；已有延时 API 保持原有契约。

## 品牌 XML 与 values-night

在应用 `res/values/toast_styles.xml` 定义样式；`parent=""` 表示不隐式继承同名父样式：

```xml
<resources>
    <color name="brand_toast_background">#203040</color>
    <color name="brand_toast_content">#FFFFFF</color>

    <style name="BrandToast" parent="">
        <item name="kToastBackgroundColor">@color/brand_toast_background</item>
        <item name="kToastTextColor">@color/brand_toast_content</item>
        <item name="kToastIconColor">@color/brand_toast_content</item>
        <item name="kToastBackgroundRadius">16dp</item>
        <item name="kToastTextSize">15sp</item>
        <item name="kToastPaddingHorizontal">20dp</item>
        <item name="kToastPaddingVertical">12dp</item>
        <item name="kToastMaxWidthRatio">0.8</item>
    </style>
</resources>
```

在 `res/values-night/toast_colors.xml` 覆盖颜色，同一 style 在下一次提交时使用对应资源：

```xml
<resources>
    <color name="brand_toast_background">#DDEEFF</color>
    <color name="brand_toast_content">#0E2238</color>
</resources>
```

```kotlin
import android.graphics.Color
import com.logan.ktoast.KToastScope
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType

fun showBranded(scope: KToastScope) = scope.showStyled(
    "上传完成",
    KToastStyle(KToastType.SUCCESS, R.style.BrandToast)
) {
    // 单次覆盖在品牌 XML 之后执行。
    borderColor = Color.WHITE
    borderWidth = 1
}
```

品牌 XML 只覆盖显式提供的属性。保留语义图标时无需配置 `kToastIcon`；替换时使用 drawable 资源，明确隐藏时设置 `kToastShowIcon=false`。文字色和图标色可以独立配置，修改背景时应一并检查两者的对比度。`kToastShowIcon=true` 不会为没有图标的 `NORMAL` 自动创建图标。

也可以引用 `?android:attr/textColorPrimary` 等主题属性。页面样式使用明确宿主 Activity 的 Context 解析，全局入口使用 Application Context，因此同一个主题属性可能解析为不同值。`followHostTheme` 控制的是品牌解析之前的基础背景/文字色；即使未开启它，品牌 XML 中显式写出的主题属性仍使用相应 Context 解析。

## XML 属性与单位

| XML 属性 | XML 值与解析结果 |
| --- | --- |
| `kToastTextColor`、`kToastBackgroundColor`、`kToastIconColor`、`kToastBorderColor` | Android color，可引用颜色资源或主题属性；透明色是有效值 |
| `kToastBackgroundRadius`、`kToastIconSize` | dimension，转换为配置中的 dp 浮点值 |
| `kToastPaddingHorizontal`、`kToastPaddingVertical`、`kToastIconPadding`、`kToastMinWidth`、`kToastMinHeight`、`kToastBorderWidth` | dimension，转换为配置中的 dp 整数，按最接近的整数取值 |
| `kToastTextSize` | dimension，转换为配置中的 sp；推荐直接使用 sp，保留其原始值以适配系统字体缩放 |
| `kToastIcon` | drawable 资源引用 |
| `kToastShowIcon` | boolean；false 明确清除前级图标 |
| `kToastMaxLines` | integer，沿用 `maxLines` 语义 |
| `kToastMaxWidthRatio` | float，沿用全局/页面 `maxWidthRatio` 语义 |
| `kToastGravity` | `top`、`bottom`、`start`、`end`、`center`、`center_vertical`、`center_horizontal`，可用 `|` 组合 |
| `kToastXOffset`、`kToastYOffset` | dimension，解析为配置中的 px 偏移；例如 `8dp` 会按当前密度换算，不把 8 当作 px |

XML 不设置显示时长、调度策略、回调或内容工厂；这些继续通过已有 API 配置。XML 配置的不是整张自定义布局，自定义 View 仍使用 `KToastContentFactory`。

## 覆盖顺序和配置快照

`showStyled` 的顺序固定为：

1. 复制本次全局基础配置。
2. 页面开启 `followHostTheme` 时，叠加宿主背景和文字色。
3. 应用非 `NORMAL` 的语义颜色与图标。
4. 应用品牌 XML 中显式提供的属性。
5. 执行本次 `localConfig`，然后保存最终配置副本。

不会临时改写全局配置。同一个 `KToastStyle` 只保存不可变的类型和资源 ID，不持有 Context/View；每次提交独立解析，适合重复使用。已接纳的队列请求保留当次配置，即使调用方保存了局部 DSL 接收者并在之后修改，也不会改变该请求。资源或日夜配置之后发生变化，不会自动重新解释已排队/显示的样式。

这里复制的是配置字段，不会深拷贝自定义内容工厂、其闭包或可变 `CharSequence`；调用方仍应管理这些对象的共享状态，避免跨线程并发修改。

需要在 View/Compose 自定义内容中复用解析结果时，可显式调用：

```kotlin
val resolved = style.resolve(context, baseConfig)
```

`resolve` 返回可修改的独立 `KToastConfig`，按传入基础 → 语义 → 品牌 XML 解析，不自动读取 KToast 全局配置或追加宿主主题。它不要求 KToast 初始化，也不创建 View，调用方可以直接用于预览。原始 `baseConfig` 保持不变。

## 线程、失败与系统降级

`showStyled` 与现有显示入口一样可从工作线程提交。配置复制、资源/主题解析和局部 DSL 在调用线程进行；不在这里创建 View。内容工厂、窗口操作和业务回调在主线程执行。创建页面作用域仍须在主线程，配置准备期间不要让业务并发修改同一主题或基础配置。

运行策略的容量检查在样式解析之前完成。满额时返回以 `CAPACITY_EXCEEDED` 结束的句柄，不解析品牌资源、不执行局部 DSL，现有请求不受影响。被接纳请求的非法资源类型、资源缺失或 DSL 异常向调用者传播，并归还该次占用的额度；不会通过忽略 XML 错误伪装为应用成功。[运行策略](RUNTIME_POLICY.md)中的其他规则继续适用。

前台自定义渲染器使用解析后的外观。全局入口降级为系统 Toast 时，图标、颜色、位置等自定义视觉不保证保留，显示仍受 Android 平台限制。页面入口继续绑定明确宿主，不转移到其他页面或后台系统 Toast；`onShow` 仍只表示渲染调用返回，不保证用户实际看见。
