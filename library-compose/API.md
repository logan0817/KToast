# Module KToast Compose

独立可选的 Compose 页面提示模块。依赖同版核心与生命周期扩展，以及 Compose Runtime/UI/Foundation 1.7.8、Lifecycle Runtime Compose 2.8.3、Activity 1.9.2 和 SavedState 1.2.1。核心和生命周期扩展单独接入不承担这些依赖。

在 ComponentActivity 或其 Compose 页面中调用 rememberKToastScope。该调用只记录当前 Activity、LocalLifecycleOwner、LocalSavedStateRegistryOwner 和父 Composition；实际注册在 Composition 提交后的 DisposableEffect 中完成。重组复用同一作用域，绑定对象或 options 改变时关闭旧作用域。

show、showStyled、showDelayed、cancel 仍委托 KToastLifecycleScope，使用现有唯一调度队列、句柄、容量和过期策略。停止取消旧活动期请求，恢复只接受新请求。离开 Composition、Owner 销毁或 close 永久关闭作用域。关闭后对本 Compose 入口的新显示或取消调用抛 IllegalStateException，以避免保留已结束 Owner；这与旧 KToastLifecycleScope 关闭后返回取消句柄的约定不同。已提交句柄按核心契约取消。

showContent 必须提供非空白 description，它用于核心消息、去重和窗口无障碍描述。局部配置先执行，随后本模块设置 contentDescription 与 contentFactory；不要用局部配置替换这两个受管理字段。只有请求实际开始展示才创建 ComposeView，设置真实 ViewTree Owner 并继承父 CompositionLocal；子 Composition 随 View 挂载创建。取消、替换和窗口移除均释放子 Composition；未展示取消不会创建 Composition。配置异常继续向调用方传播并释放本次准备资源；同步工厂或窗口挂载失败沿核心错误路径清理。用户内容的首次组合和后续重组异常均遵循应用 Compose 异常机制，不拦截或静默转换为成功；onShow 只表示渲染调用返回，不保证子 Composition 已执行或用户实际看到提示。

KToastContent 是可单独 Preview 的默认内容，通过 KToastStyle.resolve 与 View 入口复用语义、品牌和基础配置的解析；不注册监听、不初始化或显示窗口，不引入 Material。Preview 中 rememberKToastScope 不绑定真实宿主，显示操作抛出明确的禁用异常。
