# LifecycleOwner 页面作用域

`ktoast-lifecycle` 是独立可选模块，通过 `api` 暴露同版本核心和 `lifecycle-common:2.5.1`。该 Lifecycle 版本仅传递依赖 annotation，不为观察生命周期引入协程、Fragment 或 Compose。应用解析更高 Lifecycle 版本时，其传递依赖可能改变；核心单独接入仍只有 stdlib 1.9.23、Startup 1.1.1。

正式扩展坐标为 `io.github.logan0817:ktoast-lifecycle:1.2.0`，从 Maven Central 安装并传递依赖同版核心；核心与扩展版本须保持一致。安装示例见 [README](../README.md#lifecycleowner-扩展)，源码开发与本地候选构建见[贡献指南](../CONTRIBUTING.md#构建本地候选与消费者)。

## 选择入口

| 入口 | 生命周期边界 | 停止与恢复 | 后台行为 |
| --- | --- | --- | --- |
| KToast / toast 扩展 | 应用全局 | 保留旧全局取消和替换规则 | 可尝试系统 Toast，不能保证送达 |
| KToastScope | 明确的 Activity 实例 | 停止取消；恢复可复用 | 不转移宿主或降级为系统提示 |
| KToastLifecycleScope | 宿主 Activity 与独立 Owner | 离开 STARTED 关闭当轮代理；恢复新建代理 | 停止期调用取消，不等待恢复 |

三个入口共享全库串行队列，以及显式开启的容量、等待有效期和去重历史策略。扩展不提供多窗口独立并行展示。

## Fragment View 接入

在现有 Fragment 的 `onViewCreated` 中创建并保存作用域，Owner 使用 `viewLifecycleOwner`。这条调用路径已纳入 Demo 编译和设备测试。

```kotlin
import android.os.Bundle
import android.view.View
import android.widget.Button
import com.logan.ktoast.lifecycle.KToastLifecycleScope

private lateinit var toastScope: KToastLifecycleScope

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    toastScope = KToastLifecycleScope.create(requireActivity(), viewLifecycleOwner)
    view.findViewById<Button>(R.id.save).setOnClickListener {
        toastScope.show("保存成功")
    }
}
```

View Owner 销毁时自动永久关闭并注销观察器、宿主监听与弱引用。View 重建后在新的 `onViewCreated` 创建新作用域；旧作用域不能复用到新 Activity 或 View，旧作用域的调用仍返回句柄，但以取消结束。

Fragment 自身生命周期可能长于 View，绑定 Fragment 会保留错误的页面边界。hide/show 和自定义路由若未使 Owner 离开 STARTED，扩展不会推断页面不可见；业务应显式 `cancel()` 或提供符合页面可用状态的 Owner。

## 状态、线程与关闭

创建必须在主线程，宿主已结束或 Owner 已 DESTROYED 时抛出 `IllegalStateException`。Owner 初始未 STARTED 时允许绑定，但显示和延时请求取消；之后 STARTED 只接受新请求。初始已 STARTED 时立即建立当轮代理。

`show`、`showDelayed`、`cancel`、`close` 可以跨线程调用。局部配置 DSL 在调用线程执行，窗口、观察器清理和业务回调在主线程。工作线程捕获旧代理后，即使 Owner 停止再恢复，旧代理仍关闭，准备中的请求不会进入新活动期。

`cancel()` 清理当前活动期请求，不关闭 Owner 绑定；回调中新提交仍遵守 STARTED 门槛。`close()` 立即标记永久关闭，主线程完成监听与窗口清理，可重复调用。销毁、宿主先结束与回调重入均只产生一次终态。

Java 可使用静态创建及完整配置对象入口，无需 Kotlin DSL：

```java
import com.logan.ktoast.config.KToastConfig;
import com.logan.ktoast.lifecycle.KToastLifecycleScope;
import com.logan.ktoast.runtime.ToastHandle;

KToastLifecycleScope scope = KToastLifecycleScope.create(activity, owner);
KToastConfig config = new KToastConfig();
ToastHandle handle = scope.show("保存成功", config);
ToastHandle delayed = scope.showDelayed("稍后提示", 3000L);
delayed.cancel();
scope.close();
```

Java/Kotlin 独立消费者位于 `tools/compatibility/lifecycle-consumer`，使用 AGP 7.2.2、Gradle 7.4.2、Kotlin 1.9.22、compileSdk 31、JVM 1.8 验证源码和 R8。旧分析器的 metadata 提示仍存在，这一组合的构建和运行结果不代表任意旧编译器可用。设备范围、正式制品摘要和消费者结果见 [1.2.0 验收记录](releases/1.2.0.md)。

## Demo 与回退

首页“生命周期与容量策略示例”打开双 Fragment 页面。先在 A 登记延时消息，再切 B；旧消息应取消。返回 A 或重建 View 后，新请求可显示。容量演示登记 40 条延时请求，超过示例上限显示 `CAPACITY_EXCEEDED`；串行长提示演示 `EXPIRED`。这些数值用于演示，不是生产配置或性能建议。

迁移可按页面逐步进行。撤销扩展时恢复 Activity 作用域或业务显式清理，并重新构建应用。扩展依赖同版核心，不能保留 1.2 扩展而只将核心降为 1.1.0。运行策略在首个登记后冻结，调整或移除策略需在下一次进程启动前修改初始化配置。
