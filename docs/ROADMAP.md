# KToast 兼容优先版本路线

当前已发布版本为 1.1.0，包含稳定性修复与页面作用域；安装坐标为 io.github.logan0817:ktoast:1.1.0，旧公开 API 同时保护 1.0.5 和正式 1.1.0 基线。版本变化见 [CHANGELOG.md](../CHANGELOG.md)，验证状态与发版步骤统一维护在 [RELEASING.md](./RELEASING.md)。

下一版建议范围、接口设计、实现顺序与验收标准见 [1.2.0 实现与验收方案](./NEXT_VERSION_PLAN.md)。核心治理和生命周期扩展已进入本地验收，尚未发布；全部 P0 完成状态见发布检查表。

## 兼容约束

| 层次 | 当前基线 | 维护约束 |
| --- | --- | --- |
| Android 运行版本 | minSdk 21，Android 5.0+ | 新平台 API 必须按版本保护，不提高核心最低版本 |
| 核心生产依赖 | Kotlin stdlib 1.9.23、Startup 1.1.1 | 不为可选能力引入 Compose、Material、协程、AppCompat 或 lifecycle |
| Kotlin 编译器与 metadata | 编译器 1.9.23，metadata 1.9 | 不通过依赖升级提高消费者编译器要求 |
| Java/Kotlin 字节码 | 库 JVM 1.8；Demo/buildSrc JVM 17 | 构建 JDK 保持 17；字节码目标不代表 Android 标准库 API 可用性 |
| 构建工具 | AGP 8.6.0、Gradle 8.14.3 | 调整先评估实际需求、官方支持范围和消费者影响 |
| SDK | compileSdk/示例 targetSdk 36 | 本版保留已确认配置及支持范围风险；实测结果与官方支持范围见发布检查表 |
| 公开 API | 1.0.5/1.1.0 类、构造、成员和枚举 | 不改旧 data class 主构造，不增加旧枚举值；对发布基线检查 JVM 签名 |

库仓库的构建工具与消费者的接入要求分别评估。AAR 的 minCompileSdk、最低 AGP 元数据和 JVM 1.8 目标不能证明所有旧工具链兼容；Kotlin metadata、传递依赖、R8 和设备运行均需独立验证。公开 API 基线来源及原发布 AAR SHA-256 保存在 [1.0.5 基线](./api/ktoast-1.0.5.json) 和 [1.1.0 基线](./api/ktoast-1.1.0.json)。

依赖调整必须检查 minSdk、minCompileSdk、metadata、字节码、传递依赖、产物体积及消费者构建。确需提高门槛的可选功能应放在独立模块，显式说明其最低要求。

## 1.1.0：稳定性与页面作用域

稳定性修复已随 1.1.0 发布，不另行发布 1.0.6。范围包括串行调度、回调异常与重入隔离、入队前取消、延时清理、窗口与宿主清理、结构化去重键和初始化状态发布。具体变化见 [版本变更](../CHANGELOG.md)。

页面作用域已实现明确宿主、可取消延时、Java 配置入口、整体窗口约束、系统栏及可选输入法避让、无障碍推荐时长、可选主题和诊断事件。使用方式见 [PAGE_SCOPE.md](./PAGE_SCOPE.md)。

行为约定：

- 页面停止取消全部作用域请求，销毁时关闭，不转移到其他页面或系统 Toast；页面恢复后可复用尚未关闭的作用域。
- 新页面入口默认避让系统栏、采用平台无障碍推荐时长；输入法避让和宿主主题显式开启。
- 旧全局入口保留宽度、偏移、时长和全局控制语义；onShow/SHOWN 与系统完成计时不代表真实送达。
- 队列有效期、容量及旧全局去重历史默认语义保留，避免隐性改变已有业务行为。

发布前完成相关回归、旧 API/消费者/R8 检查、关键 Android 版本运行矩阵、工具链支持范围评估及正式发布产物检查。任何未覆盖项均在 [发布检查表](./RELEASING.md) 标明，不能用构建成功替代运行验证。

## 1.2.0 可选容量策略与生命周期扩展（未发布）

未开启运行策略时队列没有容量上限；旧全局去重历史在相同键再次请求时处理过期，不主动清扫全部历史键。不同请求可指定不同去重窗口，直接按历史窗口清扫可能改变旧行为。

本地候选提供 KToastRuntimePolicy：显式待处理容量、等待有效期和 LRU 历史容量，默认 null 保持兼容。新增独立 ktoast-lifecycle 绑定 LifecycleOwner，Fragment 使用 viewLifecycleOwner。实现与验收范围见 NEXT_VERSION_PLAN.md，接入见 LIFECYCLE_SCOPE.md。

## 后续可选 Compose 集成

独立模块评估 Host、状态与 Composition 清理、Preview 和 View/Compose 混用示例。核心继续支持 View、Java 和 Android 5.0+；Compose、协程及最低版本在扩展选型时单独评估，只有显式依赖扩展模块才承担对应门槛。

现有接口可从点击事件或受控副作用调用，避免在 Composable 函数体中直接显示。等待结果接口在内部状态稳定后设计，不预先引入全局 Flow 总线或复杂优先级体系。扩展版本号待范围确认后确定。
