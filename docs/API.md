# Module KToast

KToast 提供 Android 前台自定义提示、旧全局系统降级，以及可选的 Activity 页面作用域。最低运行版本为 Android 5.0/API 21；核心不依赖 Compose、Material 或协程。

## 入口与线程

默认通过 AndroidX Startup 初始化，关闭自动初始化时在 Application.onCreate 调用 KToast.init。全局配置在初始化或主线程统一设置，避免与其他线程的请求并发修改。

Java 使用静态 KToast.setDebugMode(boolean) 设置调试开关；Kotlin 也可读写 debugMode 属性。属性 setter 保留旧 JVM 签名，但对 Java 源码隐藏，避免与静态方法产生歧义。

KToastScope.create 必须在主线程调用。显示、句柄取消、作用域 cancel/close 可从工作线程调用；局部配置 DSL 在调用线程执行，内容工厂、窗口操作、业务回调和诊断在主线程执行。

## 页面消息

为每个 Activity 实例创建作用域，从按钮事件等页面已启动的阶段调用 show 或 showDelayed。停止时取消本作用域全部当前和待处理请求，销毁时永久关闭；提示不转移到其他页面或系统 Toast。恢复后可复用尚未关闭的作用域，Activity 重建后须创建新作用域。

页面默认避让系统栏，并在 API 29+ 采用无障碍推荐时长；输入法避让和宿主主题由 KToastScopeOptions 显式开启。ToastHandle.cancel 返回首次取消是否被接受，实际完成状态由 state 和 dismissReason 表示。

## 兼容行为

旧全局入口保留原宽度、偏移与时长规则，以及跨作用域的全局取消/替换含义。队列和旧全局去重历史没有默认容量上限，高频调用应主动清理。

onShow 和诊断 SHOWN 表示渲染调用返回，系统 Toast 的完成仍按本地时间估计，不保证用户实际看到提示。诊断 ERROR 的 detail 只包含异常类型，不包含消息正文。

本地生成的文档和签名包不代表对应版本已上传或发布；正式版本以 Maven Central 实际可解析坐标为准。

## 1.2.0 可选运行策略（未发布）

KToast.setRuntimePolicy(KToastRuntimePolicy) 必须在主线程、初始化后、首个请求或旧延时任务登记前调用。第一次登记原子冻结配置，之后配置抛 IllegalStateException；参数必须为 null 或正数。未设置时三个限制全部为 null，保留 1.1.0 默认行为。

- maxPendingRequests：全库有效待处理总数，含配置准备、待主线程接纳、队列、防抖和延时；展示中的请求不占额度。满额保留已接纳请求，拒绝新请求，不执行拒绝请求的 DSL。REPLACE 在满额时同样拒绝，不取消现有提示。
- maxQueueWaitMillis：从具备展示资格起算等待有效期。普通请求从调用、延时从计划触发、防抖从窗口结束计算；迟到执行不重新计时。使用 uptime 单调时钟和饱和加法，向 Handler 提交绝对截止点避免平台相对延时加法溢出；一个最早截止任务清理所有到期等待项；展示开始后不受等待有效期约束。
- maxDedupeEntries：访问顺序 LRU 条目上限。淘汰后相同消息可能再次接受；不按历史窗口清扫所有键，保留各请求可采用不同窗口的语义。

ToastHandle.dropReason 是可跨线程读取的只读附加原因。容量拒绝为 CAPACITY_EXCEEDED，等待过期为 EXPIRED，两者仍以旧 State.CANCELED / DismissReason.CANCELED 结束；普通取消和正常完成为 null。主线程唯一终态先设置原因、清理任务再发送 onCancel/onDismiss，各最多一次；不发送 onShow。正在执行的 DSL 不强制中断，取消后退出准备才归还额度；DSL 抛错释放额度并继续传播异常。

限制约束库的登记数和历史条目数，不是进程内存字节上限；主线程阻塞时不能保证回调及时送达。首个登记后不提供动态策略切换。

## 1.2.0 生命周期扩展（未发布）

KToastLifecycleScope 位于独立 ktoast-lifecycle 模块，通过每轮 STARTED 的核心作用域实现页面门禁。Fragment 应绑定 viewLifecycleOwner，停止后关闭旧代理，恢复只接受新请求；Owner/宿主结束或显式 close 永久关闭。核心不引入 Lifecycle 依赖。完整示例见 LIFECYCLE_SCOPE.md；扩展另有自己的 API 模块文档。
