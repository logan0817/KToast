# 排障指南

安装与最小示例见 [README](README.md)，开发环境和本地候选构建见 [贡献指南](CONTRIBUTING.md)，签名与上传见 [发布指南](docs/RELEASING.md)。本页集中解释失败现象与诊断方法，当前正式版为 1.1.0，1.2.0 仍是未发布候选。

## 提示未显示或队列未推进

先确认调用入口、页面状态和主线程是否正常工作。开启 `KToast.setDebugMode(true)` 查看窗口挂载、内容工厂、渲染和回调异常；日志与 Issue 不应包含业务消息正文或敏感信息。

| 现象 | 检查与处理 |
| --- | --- |
| 提示框架尚未初始化 | 默认 Startup 是否被移除；手动初始化须在 `Application.onCreate` 调用 `KToast.init(this)` |
| 后台全局提示不可见 | 系统可能限流、拦截或受 ROM/通知状态影响；降级不是送达保证 |
| Activity 页面提示取消 | 确认宿主已启动、没有停止/结束；页面作用域不转移到其他页面或系统 Toast |
| 自定义内容失败 | 工厂在主线程执行，View 不能已有父容器；超大布局可能裁剪，需适应宿主窗口 |
| 取消后看不到立即清理 | `ToastHandle.cancel()` 返回首次接受取消，工作线程调用的清理与回调在主线程执行；主线程阻塞会推迟完成 |
| 配置结果不稳定 | 全局样式为可变配置，应在初始化或主线程统一设置，避免边改全局配置边并发提交 |

局部样式 DSL 在调用线程执行，避免在其中操作 View；View 构建放在 `contentFactory`。回调异常被隔离，不能依赖回调抛错阻止后续提示。`onShow` / `SHOWN` 是渲染调用返回，系统 `COMPLETED` 是本地预计时间到达，都不证明实际送达。

Java 使用 `KToast.setDebugMode(boolean)`。1.1.0 已消除旧属性 setter 的源码歧义并保留 JVM 签名；若仍遇到歧义，检查实际解析到的 AAR 是否还是 1.0.5。

## 1.2.0 策略配置失败或消息丢弃

- `IllegalArgumentException`：参数须为 `null` 或正数，零和负数无效。
- `IllegalStateException`：检查主线程、初始化，以及是否已有有效请求或旧延时任务登记；首次登记后冻结，不能运行时重置。
- `CAPACITY_EXCEEDED`：容量统计包括准备、队列、防抖与延时。满额时 `REPLACE` 也拒绝，不先取消已有提示。
- `EXPIRED`：等待有效期从展示资格起算，延时从计划触发、防抖从窗口结束计算；主线程迟到不会重新计时。
- 普通取消的 `dropReason` 为 `null`；两类丢弃仍使用原 `CANCELED` 状态和原因。

若 1.1.0 找不到这些符号，说明误用了候选示例；先按[贡献指南](CONTRIBUTING.md#构建本地候选与消费者)构建同版候选。完整规则见[运行策略](docs/RUNTIME_POLICY.md)。

## LifecycleOwner 提示不显示

确认同时满足宿主 Activity 有效、Owner 已达到 STARTED、当前作用域未关闭。Fragment 在 `onViewCreated` 中绑定 `viewLifecycleOwner`；View 重建后重新创建，不复用旧作用域。初始未 STARTED 的调用会取消，之后恢复不重放旧请求。

hide/show 或自定义路由若不改变 Owner 的 STARTED 状态，库不会自动推断不可见；业务须显式 `cancel()` 或提供合适的 Owner。`close()` 永久关闭，不能用作临时暂停；应用升级 Lifecycle 后也要检查实际依赖解析图。详见 [LifecycleOwner 作用域](docs/LIFECYCLE_SCOPE.md)。

## JDK、SDK 与依赖下载

仓库使用 JDK 17；Gradle 8.14.3 不支持以 JDK 25 运行。Android Studio 与终端选同一 Gradle JDK，独立消费者也使用 JDK 17，但使用自己的 Gradle 7.4.2 Wrapper。库字节码 JVM 1.8 不要求构建环境安装 JDK 8，也不能据此推断 Android 标准库 API 可用。

[AGP 8.6 官方说明](https://developer.android.com/build/releases/past-releases/agp-8-6-0-release-notes)最高支持 API 35，当前 compileSdk 36 超出范围。`android.suppressUnsupportedCompileSdk` 仅压制提示，不代表官方认证。实际版本、SDK 和命令见[开发环境](CONTRIBUTING.md#开发环境)。

不要先执行 `clean`、删除共享缓存或盲目升级工具链；分析首个有效错误。依赖网络握手失败时，检查现有代理是否被 Gradle 使用。必要时为单次命令传入现有代理的 `-Dhttp.proxyHost`、`-Dhttp.proxyPort`、`-Dhttps.proxyHost`、`-Dhttps.proxyPort`，不要提交个人代理、关闭 TLS 验证或更换为不可信仓库。

## 候选与消费者不一致

生成双模块候选后运行两个独立消费者，命令见[贡献指南](CONTRIBUTING.md#构建本地候选与消费者)。显式使用 `-PktoastVersion=1.2.0-SNAPSHOT`；`ktoastPublication` 仅接受 `candidate` 和 `staging`，两者都是仓库 `build/` 下的本地目录。

消费者 preBuild 分别核对 Debug 与 Release 实际 AAR SHA，不能只检查文件存在。Staging 构建会覆盖 publication 元数据；测试 SNAPSHOT 前重新生成 Candidate，不混用正式版本和 SNAPSHOT 报告。扩展必须和核心同版。

AGP 7.2.2 的旧 Lint 可能输出 Kotlin metadata 1.9 与分析器期望 1.6 的诊断；JVM 1.8 消除了原高字节码警告，不代表该分析器获得完整支持。仅记录实际构建、运行与限制。

候选报告按模块命名为 `ktoast-candidate.json` 和 `ktoast-lifecycle-candidate.json`。不要使用旧单模块 `candidate.json` 或其他版本的报告代替。正式 API 扫描、冻结字节码、源码消费者、R8 与设备分别验证，单项不能替代整体。

## 设备回归与专用场景

在允许测试安装的专用设备上执行：

```bash
./gradlew :app:connectedDebugAndroidTest -PVERSION=1.2.0-SNAPSHOT --console=plain
```

测试会安装并启动 Demo，设备须解锁、允许前台 Activity 显示。构建通过不等于设备通过，结果应按 API、提交和产物分别记录。云端入口与报告核验见[设备矩阵](docs/RELEASING.md#云端设备矩阵)。普通矩阵不启用下述专用 fixture。

`KToastLifecycleTest` 与 `KToastScopeTest` 覆盖取消、重入、页面停止恢复、清理和窗口行为；`KToastRuntimePolicyDeviceTest` 与 `KToastLifecycleOwnerTest` 覆盖本版容量、过期、延时转移、Owner 门禁和 View 重建。缺少 fixture 的明确跳过不计成功；SDK 过滤未报告与显式跳过分开记录。

`KToastPlatformTest` 的输入法测试只在 API 30+ 执行，无障碍推荐时长测试只在 API 29+ 执行。后者需要专用测试模拟器把 accessibility_non_interactive_ui_timeout_ms 设为 10000，并给 AndroidJUnitRunner 传入 `-e accessibilityTimeoutFixture true`；缺少该参数时明确跳过，不能计为通过。不要修改使用者日常设备的无障碍设置。测试完成后移除专用模拟器设置或销毁该模拟器。

自定义交互控件的两项时长回归使用另一套独立配置：专用模拟器 accessibility_interactive_ui_timeout_ms=10000，accessibility_non_interactive_ui_timeout_ms 保持默认，Runner 参数为 `-e interactiveTimeoutFixture true`。分别运行 customButtonUsesInteractiveRecommendedTimeout_withoutBubbleCancel 与 customPlainTextDoesNotUseInteractiveTimeout，验证按钮采用交互时长、可执行无障碍点击，而普通自定义文字按普通时长结束。测试会先断言平台对文字推荐 2000ms、对控件至少推荐 5000ms，防止全局延长文字掩盖控件判断问题；不要与上一项的非交互 10000ms 配置混用。记录原设置后再配置，结束时恢复原值。

`KToastWindowTest` 在 API 30+ 验证显示期间 START 随宿主 RTL/LTR 切换定位，以及底部提示在输入法出现后移动到键盘上方。输入法场景必须显式使用底部 Gravity，不能沿用 Demo 的全局居中样式后仅断言“未重叠”，否则可能没有真正验证避让逻辑。宿主与提示子窗口分别观察绘制，避免只在宿主绘制前读取尚未更新的子窗口位置；失败信息包含可见键盘、实际屏幕位置与请求定位参数。界面变化以实际窗口位置为准，不能把初次显示位置正确等同于动态适配正确。

显示缺口测试需给 Runner 传入 `-e displayCutoutFixture true`，并在专用模拟器上启用非零顶部缺口；测试会检查真实 DisplayCutout，再验证极大负偏移也不会让整个内容越过安全区域。没有该参数时明确跳过。可先用 `adb -s <专用设备> shell cmd overlay list` 确认系统已有的 display.cutout.emulation.tall，再用 cmd overlay enable/disable 配置和恢复；不要在日常设备上修改显示设置，也不要把未启用缺口的普通屏幕结果计为此项通过。

`KToastMultiWindowTest` 需要 API 30+，仅在支持自由窗口的专用模拟器启用 `-e multiWindowFixture true`，没有该参数时明确跳过。测试检查实际进入多窗口、宿主边界小于整屏且起点非零，再验证超大自定义内容的整体尺寸、安全坐标和任务调整大小后的作用域行为；不能用全屏测试替代。

专用模拟器可先记录 `settings get global enable_freeform_support` 与 `settings get global force_resizable_activities` 的原值，再分别用 settings put global 将两项设为 1。测试使用 am start 的 --windowingMode 5 与 am task resize，并在清理阶段恢复 --windowingMode 1；结束后还须恢复原开发者设置。原值为 null 时使用 settings delete global 删除对应项，原值非 null 时写回原值。不要在日常设备上强制改变窗口设置。不同平台可能不支持这些 shell 参数，必须以实际进入模式和改变尺寸的断言为准。

## 设备安装、失焦与采证

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` 表示同名包签名不兼容，不直接卸载使用者已有应用。改用专用模拟器或独立测试包；若通过临时 init script 改包名，须同步 Runner/消费者预期并记录偏离标准矩阵的范围，不能直接当作标准矩阵证据。

connected 测试因额外平台组件下载受限时，可在 APK 编译后使用 adb 安装并运行对应 AndroidJUnitRunner。锁屏、USB 安装策略或系统确认未完成时，记录未验证，不把 APK 编译成功写成运行成功。

Espresso 出现 `INJECT_EVENTS` 时检查实际点击目标；若命中系统导航栏，检查 Demo 视口和坐标，不给应用增加系统注入权限。Demo 根布局处理底部、侧边与缺口，Toolbar 处理顶部，重复 Insets 不应累加 padding。

`RootViewWithoutFocusException`、IME 未显示或 ActivityScenario 状态超时须保留首个有效错误、Runner 与窗口日志；不能默认归因于库或模拟器，也不能通过增加重试、放宽断言或跳过用例掩盖问题。真实窗口位置、焦点、Insets 与宿主生命周期应分别核对。

矩阵先检查 `sys.boot_completed`、当前焦点和锁屏标志；`startupPreflight.success=false` 时先检查 `preflight-window.txt` 与 `device-logcat.txt`。ANR 对话框或锁屏会阻止 Runner 启动，启动未完成或无焦点有 60 秒等待期限；单次命令另有超时。此检查不会关闭系统对话框，也不保证测试过程中不再失焦。并发准备测试由停止/恢复转场的驱动线程在 `finally` 中放行并回收工作线程，避免独立准备倒计时在慢设备上先结束；ActivityScenario 和 Runner 原有超时仍有效。

矩阵的 `WindowFailureDiagnosticsTest` 验证真实规则与私有目录导出。诊断使用 `adb shell run-as` / `adb exec-out run-as` 读取当前运行文件；外部存储在旧系统可能未挂载或无权限。旧 `dumpsys` 不支持 `-t`，因此由读取端或主机命令设置超时。命令错误文本或空文件不能作为有效快照。

收尾快照与首次失败并非同一时刻，不能仅凭收尾焦点判断完整根因。采证错误只作为附加错误，保留原失败。各轮已确认事实与未解决限制见[验收记录](docs/releases/1.2.0.md)。
