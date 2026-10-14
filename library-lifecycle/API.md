# Module KToast Lifecycle

独立可选的 LifecycleOwner 提示作用域。核心不依赖本模块；扩展增加 lifecycle-common 2.5.1（传递依赖 annotation 1.1.0），不引入 Fragment、Compose 或协程。应用可自行解析更高版本，其传递依赖由应用负责。

在主线程调用 KToastLifecycleScope.create(activity, owner)，Fragment 应传入 viewLifecycleOwner。每轮 STARTED 使用独立 Activity 作用域，停止时关闭该轮代理；恢复只接受新请求，旧请求不会复活。Owner 销毁、宿主销毁或显式 close 后永久关闭并移除观察器。

show、showDelayed、cancel 和 close 可从工作线程调用。窗口操作、回调和监听器清理在主线程执行。绑定不改变全库串行队列，也不保证后台送达或自动识别不改变生命周期的 hide/show。
