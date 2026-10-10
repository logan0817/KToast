# Module KToast

KToast 提供 Android 前台自定义提示、旧全局系统降级，以及可选的 Activity 页面作用域。最低运行版本为 Android 5.0/API 21；核心不依赖 Compose、Material 或协程。

## 入口与线程

默认通过 AndroidX Startup 初始化，关闭自动初始化时在 Application.onCreate 调用 KToast.init。全局配置在初始化或主线程统一设置，避免与其他线程的请求并发修改。

KToastScope.create 必须在主线程调用。显示、句柄取消、作用域 cancel/close 可从工作线程调用；局部配置 DSL 在调用线程执行，内容工厂、窗口操作、业务回调和诊断在主线程执行。

## 页面消息

为每个 Activity 实例创建作用域，从按钮事件等页面已启动的阶段调用 show 或 showDelayed。停止时取消本作用域全部当前和待处理请求，销毁时永久关闭；提示不转移到其他页面或系统 Toast。恢复后可复用尚未关闭的作用域，Activity 重建后须创建新作用域。

页面默认避让系统栏，并在 API 29+ 采用无障碍推荐时长；输入法避让和宿主主题由 KToastScopeOptions 显式开启。ToastHandle.cancel 返回首次取消是否被接受，实际完成状态由 state 和 dismissReason 表示。

## 兼容行为

旧全局入口保留原宽度、偏移与时长规则，以及跨作用域的全局取消/替换含义。队列和旧全局去重历史没有默认容量上限，高频调用应主动清理。

onShow 和诊断 SHOWN 表示渲染调用返回，系统 Toast 的完成仍按本地时间估计，不保证用户实际看到提示。诊断 ERROR 的 detail 只包含异常类型，不包含消息正文。

本地生成的文档和签名包不代表对应版本已上传或发布；正式版本以 Maven Central 实际可解析坐标为准。
