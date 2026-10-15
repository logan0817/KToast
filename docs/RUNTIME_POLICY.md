# 可选运行策略

> 适用于尚未发布的 1.2.0 候选。正式 1.1.0 没有本页 API。[构建本地候选](../CONTRIBUTING.md#构建本地候选与消费者)后使用。

`KToastRuntimePolicy` 为全局、Activity 和 LifecycleOwner 入口设置共享的待处理容量、等待有效期与去重历史容量。三个限制默认均为 `null`，保持 1.1.0 的默认行为；不设置策略即可继续使用旧入口。

## 初始化配置

在主线程、KToast 已初始化、首个有效请求或旧延时任务登记前设置。默认 Startup 已在 `Application.onCreate` 前初始化；关闭 Startup 时先调用 `KToast.init(this)`。

```kotlin
import android.app.Application
import com.logan.ktoast.KToast
import com.logan.ktoast.runtime.KToastRuntimePolicy

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KToast.setRuntimePolicy(
            KToastRuntimePolicy(
                maxPendingRequests = 32,
                maxQueueWaitMillis = 10_000L,
                maxDedupeEntries = 256
            )
        )
    }
}
```

将该 Application 配置到应用 Manifest。示例值仅用于演示，应根据实际消息频率和业务时效选择。

Java 等价配置：

```java
import com.logan.ktoast.KToast;
import com.logan.ktoast.runtime.KToastRuntimePolicy;

KToast.setRuntimePolicy(new KToastRuntimePolicy(32, 10_000L, 256));
```

| 参数 | 默认 | 有效值 | 含义 |
| --- | --- | --- | --- |
| `maxPendingRequests` | `null` | 正整数 | 全库有效待处理请求总数 |
| `maxQueueWaitMillis` | `null` | 正 `Long`，毫秒 | 具备展示资格后的最长等待时间 |
| `maxDedupeEntries` | `null` | 正整数 | `IGNORE_IF_SAME` 历史键的 LRU 条目上限 |

非法值在构造时抛出 `IllegalArgumentException`。首次登记前可以重新配置；首次有效登记与容量预留在同一同步边界冻结配置，之后设置抛出 `IllegalStateException`。关闭的调试入口和扩展函数忽略的空内容不视为有效登记。没有运行时重置接口，改变策略需要修改下次进程启动时的初始化配置。

## 容量与拒绝

待处理额度包含配置准备、等待主线程接纳、队列、防抖及延时请求；正在展示的请求不占额度。同一请求跨阶段只占一个额度，旧延时 `Runnable` 触发时转移已有额度。

满额时保留已接受请求，拒绝新请求且不执行其局部样式 DSL。`REPLACE` 和新重复消息也可能先因容量被拒绝；拒绝不会取消已有有效提示。被拒绝的旧延时入口仍返回可安全撤回的 `Runnable`，但不会显示。

正在执行的 DSL 不会被强行中断。准备阶段遇到取消，退出准备后才归还额度；DSL 抛错会释放额度并继续传播原异常。

## 等待有效期

使用单调时钟计算，不受系统日期调整影响，时间加法有溢出保护：

| 请求类型 | 等待时效起点 |
| --- | --- |
| 普通请求 | 调用时 |
| 延时请求 | 计划触发时 |
| 防抖请求 | 本轮防抖窗口结束时 |
| 延时后防抖 | 实际进入防抖阶段后计算窗口结束时 |

例如延时 30 秒、允许等待 10 秒的请求，不会在调用后第 10 秒过期；其计划触发后等待超过 10 秒才过期。主线程迟到执行不会重新起算。旧 `Runnable` 手动提前 `run()` 保留立即触发语义。

库在准备结束、主线程接纳、触发与展示前检查请求资格，使用一个最早截止任务清理所有到期待处理项。已过期 `REPLACE` 不取消现有提示，也不写入去重历史。开始展示后不再受等待有效期限制，展示时长仍由原有规则决定。

## 去重历史

启用 `maxDedupeEntries` 后，历史按访问顺序淘汰最久未访问键；作用域取消和关闭仍清理对应历史。淘汰意味着同一键即使处于此前时间窗口内，也可能再次接受。

每次请求仍使用自身 `windowMillis` 判断重复，不按旧请求的窗口统一清扫所有键。默认未启用上限时保留旧行为。

## 观察结束原因

```kotlin
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.ToastDropReason
import com.logan.ktoast.toastHandle

"上传状态".toastHandle(
    callbacks = KToastCallbacks(
        onDismiss = { handle, _ ->
            when (handle.dropReason) {
                ToastDropReason.CAPACITY_EXCEEDED -> println("容量已满")
                ToastDropReason.EXPIRED -> println("等待过期")
                null -> Unit
            }
        }
    )
)
```

两类丢弃均以原有 `State.CANCELED` 和 `DismissReason.CANCELED` 结束；不增加旧枚举成员。`dropReason` 在终态回调前可见，可跨线程读取。丢弃不会发送 `onShow`，`onCancel` 和 `onDismiss` 各最多一次，均在主线程执行；普通取消与正常结束的附加原因为 `null`。

容量和 LRU 限制约束登记数与条目数，不是整个进程的内存字节上限；主线程阻塞时也无法保证回调及时执行。验收证据和覆盖边界见 [1.2.0 记录](releases/1.2.0.md)。
