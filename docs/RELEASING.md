# 1.2.0-SNAPSHOT 本地实施验收（未发布）

更新时间：2026-10-14。当前正式版本仍为 1.1.0。核心容量治理、LifecycleOwner 扩展、Demo、双模块候选和发布门禁已实施；首轮云端矩阵 API 21/30/31/36 通过，API 35 因窗口失焦失败；采证目录修复与真实设备自检已补齐，修复后的最终产物矩阵待运行，完整 P0 验收仍未完成。

## 本版真实验证证据

| 项目 | 本地结果 | 证据与边界 |
| --- | --- | --- |
| 核心 JVM | 48 项通过，含默认回归、准入/冻结竞态、LRU、队列尾部过期、计时溢出、回调重入异常、5000 混合请求 | library-android/build/test-results/testDebugUnitTest；可控时钟，不作为性能或进程内存证明 |
| 生命周期 JVM | 7 项通过，含 STARTED 新代理、停止/恢复、销毁清理、工厂与关闭交错、主线程清理回调 | library-lifecycle/build/test-results/testDebugUnitTest |
| 双模块库 Lint | 通过 | 各库 lint-results-debug；无工具链升级 |
| 双模块候选 | AAR、POM、Gradle metadata、sources、真实 Dokka 文档与精确 API 校验通过 | build/reports/compatibility/ktoast-candidate.json、ktoast-lifecycle-candidate.json；核心保护正式 1.0.5/1.1.0，新增两类和两个旧类成员 |
| 旧字节码消费者 | 正式 1.0.5、1.1.0 Java/Kotlin 编译后保持字节码不变，替换当前候选运行通过 | build/reports/compatibility/binary-consumers/result.json；不执行 Android 窗口 |
| 独立源码消费者 | 核心和扩展均通过 Debug、R8 Release；实际解析 AAR 摘要核对 | AGP 7.2.2、Gradle 7.4.2、Kotlin 1.9.22、compileSdk 31、JVM 1.8；旧 Lint/R8 metadata 提示保留，不承诺任意旧组合 |
| Python 工具 | 98 项通过，新增双模块文件集合、缺失/篡改拒绝、原子校验和清理与公开类型扫描 | tools/tests；夹具不冒充真实 PGP 或 Portal |
| Central 模型/任务图 | 23 项真实 Gradle 回归通过 | build/reports/central-publishing/configuration.json；dry-run，不签名或联网上传；保护两个模块与人工发布模式 |
| 本地双模块签名演练 | 10 类实际产物签名有效，生成 60 文件 ZIP；扩展 AAR 篡改、坏签名、错误指纹均拒绝 | build/reports/dual-signing；临时测试密钥已移除，不访问个人密钥环，不上传 |
| API 24 arm64 | 最终同批产物 37 项通过、0 项显式跳过、8 项高版本 SDK 过滤未报告、0 项失败；核心/扩展 R8 新进程均 PASS | build/reports/device-matrix/api-24；含最终四项策略设备回归 |
| API 37 arm64，16KB 系统镜像 | 最终同批产物 40 项通过、5 项专用 fixture 跳过、0 项失败；核心/扩展 R8 新进程均 PASS | build/reports/device-matrix/api-37；含最终四项策略设备回归 |
| 策略设备回归（API 37） | 4 项通过：容量拒绝、10 秒准备过期、旧 Runnable 转移/重复触发与 DSL 抛错、Long.MAX_VALUE 延时不溢出 Handler 时钟 | 纳入最终完整矩阵；回调在主线程且各一次 |
| Central service 隔离回归 | 双模块验收失败不上传、成功将两个模块合并只上传一次且 USER_MANAGED，两项通过 | build/reports/central-publishing/service.json；仅本机假 Portal 与临时项目 |
| Demo 与设备 APK | 构建通过；双 Fragment 切换、View 重建、停止恢复实际运行通过 | 公开 create 的初始 STARTED、已 DESTROYED 与结束宿主门禁均在 API 24/37 验证，关闭后 observerCount 为 0 |
| Demo Lint（额外检查） | 剩余 9 项既有主页面问题；新页面 SetTextI18n 已修正 | InflateParams、7 项未用资源、TooManyViews；该额外检查失败，不声明全项目 Lint 通过 |
| 关键五版本矩阵 | 首轮四版本通过、API 35 失败；修复后待运行 | CI 新增采证回归后要求 38/41 项必需回归及两份 R8 消费者，同批 APK/AAR 摘要关联；历史 1.1.0 成功不认证本版 |

依赖边界：核心保持 stdlib 1.9.23、Startup 1.1.1、minSdk 21、JVM 1.8、metadata 1.9。扩展同版依赖核心和 lifecycle-common 2.5.1，仅后者传递 annotation；实际独立消费者拒绝协程、Fragment、Compose。2.6.2 的真实 POM 会增加协程，因此本版使用提供所需观察 API 的 2.5.1，不通过排除依赖掩盖问题。应用主动升级 Lifecycle 时需重新检查解析图。

AGP 8.6 与 compileSdk 36 的既有官方支持范围风险保持记录。当前 SDK 只有可复用的 API 24/37 arm64 镜像；旧 avdmanager 与 JDK 不兼容，验证使用忽略的 build/validation-avds 临时配置直接复用镜像，没有安装或升级 SDK。设备验证完毕后关闭本任务模拟器。

本地未裁剪 AAR 与完整 R8 消费者 APK 测量如下；未建立同条件基线增量，不宣传更轻或更快：

- coreAar：164772 bytes。
- lifecycleAar：16890 bytes。
- coreR8Apk：72215 bytes。
- lifecycleR8Apk：74828 bytes。

测量摘要保存在 build/reports/compatibility/artifact-sizes.json。两份消费者 APK 具有不同页面代码，总 APK 差值不能当作生命周期库的纯增量。

API 24/37 最终运行复用以下同批产物；这些本地运行发生在候选提交前，报告 buildCommit/commit 为 null，源码与候选由实际 sources/API/产物校验关联，不冒充提交级 CI 证据。

| 产物 | SHA-256 |
| --- | --- |
| 核心 Release AAR | 0b3b3d6979099d4ac86ed36b70da5a4981f04d71047bd31ca4c0f125c948cabf |
| 生命周期 Release AAR | 163fca78a0dd7dfcbd157178cbaf37afda8040c8163eb5f6bf4998a88db08321 |
| Demo Debug APK | bf38be09e02f596b292030cf3275706394fb3c8b5483e50b3864421781c434c7 |
| 设备测试 APK | 399f52125fa13fb6a3934164bca94248143d124ce1edb0e85684f0e3287d3f12 |
| 核心 R8 消费者 APK | b1c6d8130236b67b6e145e57b4cb74b0ab1499f3d58f8851c9cd722946240d9a |
| 生命周期 R8 消费者 APK | 87ccf962ea8c54576452c0d0376db562361d9dcee3f08869341e1cd1a14c818e |

API 37 最终运行前曾出现消费者入口缺类：移动源码目录后的增量构建将 Java 编译判为 UP-TO-DATE，但实际输出缺少 ConsumerActivity.class，设备报 ClassNotFoundException。已通过临时 init 脚本仅重新编译消费者 Debug/Release Java 任务恢复输出，随后常规构建、R8 映射和新进程运行均验证成功；未修改永久构建策略。增量失效的具体机制未确定，失败证据保留于 build/reports/device-matrix-history/api-37-consumer-missing-activity。

## 本版入口与发布门槛

```bash
bash ./gradlew verifyReleaseCandidate :app:assembleDebug :app:assembleDebugAndroidTest -PVERSION=1.2.0-SNAPSHOT --console=plain
bash ./tools/compatibility/android-consumer/gradlew -p tools/compatibility/android-consumer assembleDebug assembleRelease -PktoastVersion=1.2.0-SNAPSHOT --console=plain
bash ./tools/compatibility/lifecycle-consumer/gradlew -p tools/compatibility/lifecycle-consumer assembleDebug assembleRelease -PktoastVersion=1.2.0-SNAPSHOT --console=plain
python3 -B tools/run_device_matrix.py --prepare
```

维护者本机 Gradle 验证仍通过 Android Studio build environment Skill 启动，复用 JDK17、SDK、共享 Gradle 缓存。候选报告文件改为按模块命名，矩阵和工作流同步引用；旧 candidate.json 可能属于历史构建，不用于本版验收。

根 prepareCentralBundle 聚合两个 Staging publication；签名、源代码关联和 ZIP 逐文件验收均覆盖两个模块。正式 Central 上传要求干净源码、完整双模块报告、非演练包、指定公钥、人工发布模式以及根入口，禁止直接模块上传、跳过检查、--continue、自动 release/drop。本地候选、测试签名和 dry-run 不代表已上传或发布。

候选实现已按授权提交为 8652fe39d6dc574a2596212ef6b56043d57d9f0b，推送 codex/1.2.0-candidate 并手动触发远端矩阵。正式签名、上传和发布尚未执行；完成修复后最终产物的验证才能关闭本版完整验收门槛。厂商真机、分屏、无障碍/显示缺口专用 fixture 未补测，不将普通矩阵推广为这些场景已通过。

## 首轮云端候选及采证修复

[第 21 轮矩阵](https://github.com/logan0817/KToast/actions/runs/38570915604) 首尝试对应 8652fe3，整体失败，未重跑。构建、API 21/30/31/36 成功；API 21 为 37 项通过、8 项 SDK 过滤未报告，API 30/31/36 各为 40 项通过、5 项 fixture 跳过。四个版本的核心与扩展 R8 新进程均 PASS。七份原始 ZIP 的 SHA-256 与官方摘要一致，原始 Runner 重解析、提交与六产物摘要逐项核验；归档为 build/reports/device-matrix-cloud/38570915604，verified-summary.json 的 allFiveVerified=false。

API 35 为 36 项通过、5 项跳过、4 项失败，R8 未执行。失败均为 RootViewWithoutFocusException，涉及两项 IME 场景与两项主页面交互。完整 logcat 显示 Launcher 于 13:30:43 ANR，首个失焦用例于 13:32:39 开始；收尾 window/input 仍指向该 ANR 对话框。这支持本次存在环境故障，不证明历史所有失焦均有同一原因，也不以其他版本成功替代 API 35 验收。

本次真实失败暴露测试进程的 instrumentation.context.getExternalFilesDir(null) 返回 null，三个失败用例的诊断规则未保存文件。现改为 targetContext 的宿主包外部目录，并同步主机拉取路径。新增 WindowFailureDiagnosticsTest 在存活宿主中用真实 JUnit 规则触发可控异常，验证原异常对象保留、五份非空文件及 shell 可读；矩阵再从主机 adb pull 并检查五文件，证据单列 diagnostics-selfcheck。自检内部捕获预期异常，正常 Runner 不产生伪造失败，原业务断言、超时及用例集合均保留。98 项 Python 回归与最终测试 APK 编译通过；真实设备自检和修复后的完整矩阵尚待运行。

以下为 1.1.0 历史发布证据，保留原日期与原制品范围，不能替代 1.2.0 验证。

---

# 1.1.0 发布检查表

## 当前状态与产物

Java 调试入口兼容修复、发布配置拆分、标准 GPG 配置、Gradle 命令上传流程及窗口测试清理修正已纳入 main。[PR #3](https://github.com/logan0817/KToast/pull/3) 合并提交为 3d0abe5886e3b89e97ebd1b1132cc1fc0ae4c51e，文件树与已授权的 f9146fd 完全一致。2026-10-13（北京时间）的 [第 18 轮 main CI](https://github.com/logan0817/KToast/actions/runs/38273388599) 首尝试整体成功，五版本设备回归、R8 消费者及构建检查通过，七份原始 ZIP 与官方摘要一致。第 15 轮失败证据继续保留；此前持续失焦的根因仍未确定，不以新一轮成功宣称该问题已彻底消除。

正式签名包已在该干净 main 提交下通过本地验收，并通过 Gradle 根 :publishToMavenCentral 上传 USER_MANAGED 批次。上传阶段 Central 状态为 VALIDATED、errors 与 warnings 为空；2026-10-13 经独立授权执行官方 API 发布，状态转为 PUBLISHED，已发布查询为 true。正式 Maven Central 下载的 30 个文件与验收包逐字节一致，五份签名有效。deployment ID 为 a956146e-2061-4eb0-9319-85fc7cc534f0，批次名为 io.github.logan0817-KToast-1.1.0，具体摘要与证据见文末。全新消费者从正式仓库解析 1.1.0，Java/Kotlin 编译及 Debug、R8 Release 构建通过。发布后 status 接口仍返回 Deployment components info not found；此诊断与 PUBLISHED、published=true 及正式下载验收分别记录，不声称查询诊断已消除。附注标签 1.1.0 与正式 GitHub Release 已创建并验收，README 安装坐标已同步为 1.1.0。新增设备失败采证分支没有触发，普通矩阵不替代专用 fixture 或厂商真机覆盖。这里集中维护验证结果与发布待办，不保留沟通过程文档。

产物路径为 library-android/build/outputs/aar/library-android-release.aar，本地仓库为 build/candidate-repository。最新 AAR、sources、发布坐标、文件大小与 SHA-256 由 tools/verify_candidate.py 写入 build/reports/compatibility/candidate.json，以该报告和实际文件一致性为准。构建缓存、设备日志和临时测试配置不进入版本库。

API 文档由 Dokka 1.9.20 从 Kotlin 源码、KDoc 和 [API 模块说明](./API.md) 生成，输出为 library-android/build/docs/api/index.html，并作为 javadoc 分类 JAR 加入发布。该插件采用 Apache 2.0 许可证，仅属于构建依赖，不进入消费者 POM 或库生产依赖。本地文档不请求远程 Android/JDK/Kotlin 索引，可离线浏览。

## 工具链与兼容边界

构建使用 JDK 17、Gradle 8.14.3、AGP 8.6.0、Kotlin 1.9.23。库产物目标为 JVM 1.8，Demo/buildSrc 为 JVM 17；minSdk 21，生产依赖仍为 stdlib 1.9.23 与 Startup 1.1.1。

- AGP 8.6 官方最高支持 API 35，当前 compileSdk 36 超出该范围。android.suppressUnsupportedCompileSdk 只压制提示；维护者已选择本版保留当前配置并记录风险；已完成本地构建及消费者验证，但不以压制提示或设备运行作为官方支持认证。
- AGP 7.2.2 消费者实测 Debug/R8 Release 构建通过，但内置 Lint 对 metadata 1.9 有解析诊断。JVM 1.8 消除了原高字节码警告，不代表修复该分析器限制，不承诺任意旧 AGP 或 Kotlin 编译器可用。
- AAR minCompileSdk=1、minAndroidGradlePluginVersion=1.0.0、无需 core library desugaring 是元数据检查结果，不等于全部消费者组合得到认证。
- 未裁剪 AAR 体积包含新增功能成本，不能直接等同于消费者最终 APK 增量；消费者体积评估应以相同构建条件下 R8 后结果为准。

## 验证矩阵

以下当前关键矩阵证据来自 2026-10-13（北京时间）的第 18 轮 main CI，包含 Java 调试入口兼容修复、命令发布配置及窗口测试清理修正；API 24/37 及专用 fixture 结果单独保留为此前功能候选的覆盖记录。源码或构建设置影响行为、API、依赖或产物目标后，须重跑对应验证；仅文档和注释调整须核对 sources 和产物关系，不把历史运行结果写成新产物已运行。

当前冻结候选的基线构建源码为 811c2535cf0a2d07dcb3229d72d7b3df8e755c6a；最新 PR 与 main 的实际源码、运行尝试及验收状态见下方合并检查。本地与云端生成的 AAR、sources、SNAPSHOT javadoc 及完整 candidate.json 一致，32 项 JVM 测试、库 Lint、83 项工具回归、旧公开 API 与消费者验证通过。五个关键版本均运行同一批 APK/AAR；API 24/37、16KB 页面大小及专用场景属于此前候选的历史证据，未对本轮产物重复运行，不用于认证本轮新 AAR。

| 验证项 | 已有结果 | 限制 |
| --- | --- | --- |
| JVM 测试、库 Lint | 32 项通过；No issues found | 不替代设备运行 |
| Release AAR、候选发布、Demo 与测试 APK | 构建通过；候选额外包含真实 javadoc 包 | 本地 publish 不等于正式远程发布 |
| 发布与矩阵校验工具 | 当前本地及第 18 轮 main CI：候选校验 15 项、发布包 13 项、远端离线验收 14 项、矩阵工具 19 项、上传暂存验收 22 项，共 83 项回归通过 | 设备失败采证分支未触发；工具测试不替代设备或服务端验收 |
| 本地签名演练 | 五类产物签名通过，生成 30 文件 Central ZIP；错误指纹、产物篡改、签名损坏均拒绝 | 临时测试密钥已移除；未使用正式密钥、未上传或服务端校验 |
| 签名配置边界 | 当前本地及第 18 轮 main CI 的 19 项真实 Gradle 配置回归通过；复用标准 keyName，短/缺失指纹、冲突密钥、Gradle 口令、旧 GPG、configuration cache 均拒绝 | 配置回归不签名、不访问个人密钥环；历史 12 项仅对应旧配置 |
| 正式签名、Central 发布与消费者 | 干净 main 上传成功；独立授权后 PUBLISHED、published=true；正式仓库 30 文件一致、五份签名有效；全新旧 AGP 消费者 Debug/R8 Release 构建通过 | status 组件查询诊断仍存在；发布后未再次运行设备矩阵，既有矩阵对应相同 AAR 字节 |
| 旧公开 API 与 metadata | 15 个旧公开类基线一致；major=52；关键 metadata=1.9.0 | 不承诺所有旧编译器 |
| 已编译 1.0.5 消费者 | Java/Kotlin 保持原字节码，替换本轮候选运行通过 | JVM 样例不执行窗口 |
| Kotlin 源码消费 | 1.9.22、2.0.21 配置样例编译运行通过；页面源码编译通过 | 此前源码样例验证；本轮旧 AGP 消费者使用 1.9.22，完整仓库使用 1.9.23 |
| AGP 7.2.2 消费者 | Gradle 7.4.2、Kotlin 1.9.22、compileSdk 31、JVM 1.8；Debug/R8 Release 通过 | metadata 分析限制保留；compileSdk 31 不等于 API 31 运行 |
| API 24 | 28 项核心设备回归通过；R8 Release 新进程 PASS | 此前候选结果；7 项高版本专用用例由 SDK 过滤，不计成功 |
| API 37、16KB 页面大小 | 原 33 项、交互时长 2 项、自由窗口 1 项分三次 Runner 通过；R8 Release 新进程 PASS | 此前候选结果；缺口与自由窗口仅覆盖专用模拟器场景 |
| 超大内容复核 | 修正为显式 6000px 布局宽度的同一用例在 API 24/37 各通过 | 重复复核不计新增独立用例 |
| API 21 | 云端专用模拟器 28 项必需回归通过；R8 Release 新进程 PASS | 8 项由 SDK 过滤、无逐项状态，不计成功 |
| API 30/31 | 第 18 轮 main：两个版本各 31 项必需回归通过；R8 Release 新进程均 PASS | 每个版本 5 项专用 fixture 明确跳过；API 31 全量 logcat 导出有编码错误，instrumentation/R8 专用日志完整 |
| API 35 | 第 18 轮 main：31 项必需回归通过；R8 Release 新进程 PASS | 5 项 fixture 跳过；历史持续失焦根因未确定，失败采证分支未触发 |
| API 36 | 第 18 轮 main：31 项必需回归通过；R8 Release 新进程 PASS | 5 项专用 fixture 明确跳过；不替代厂商真机 |
| 更多厂商与分屏 | 未验证 | 不将自由窗口结果推广到全部厂商和窗口模式 |

API 33 的旧验证仅对应更早稳定性阶段，不用于认证当前完整 1.1 候选。设备测试的 SDK 过滤、fixture 跳过、失败和成功必须分别记录。无障碍、显示缺口、自由窗口的专用配置及恢复要求见 [TROUBLESHOOTING.md](../TROUBLESHOOTING.md)。

当前本地不下载缺失测试镜像，不调整 AGP/compileSdk。属性 setter 的 JVM synthetic 兼容修复已包含于本轮产物；已完成下述云端关键矩阵与真实 Central 上传验收。设备项按原始 Runner、R8 日志和产物报告核对；官方支持范围风险及专用场景覆盖限制继续保留。

## 云端设备矩阵

[Candidate device matrix](../.github/workflows/device-matrix.yml) 使用 GitHub 托管 Ubuntu Runner，为 API 21、30、31、35、36 分别启动临时模拟器。镜像只在云端 Runner 下载，不占用或修改维护者本机 SDK。API 21 使用官方 default/x86 镜像，其余使用 google_apis/x86_64；官方仓库目录已确认包含这些镜像，目录存在不代表模拟器已成功运行。

构建任务沿用 JDK 17、两套现有 Gradle Wrapper 和现有依赖，先完成 JVM 测试、库 Lint、Demo/测试 APK、SNAPSHOT 发布校验及旧 AGP 消费者 Debug/R8 Release 构建。所有设备任务下载同一批 APK/AAR，校验 SHA-256 与构建提交后再安装，避免每个 API 重建或使用旧候选。Actions 使用固定提交，工作流仅有 contents: read 权限，不签名正式产物、不上传 Maven Central。

SDK 准备沿用 Action 的 cmdline-tools，显式设置 packages: platform-tools，避免默认安装已下架的旧 tools 包；编译平台与 Build Tools 由工作流后续步骤明确指定。

- API 21 必须通过 Demo、生命周期与作用域共 28 项核心回归，并在新进程运行 R8 Release 消费者。
- API 30、31、35、36 另要求首次输入法避让、显示期间输入法移动及 RTL 动态定位，共 31 项必需回归，并运行同一 R8 APK。
- 无障碍时长、显示缺口和自由窗口仍使用专用 fixture；普通矩阵未配置这些 fixture，不能替代已有专用场景或厂商真机验证。

工具将 passed、skipped、failed 和 notReported 分开记录。SDK 过滤可能不产生逐项状态，未报告项不计为成功，也不猜测为已跳过。任一必需用例未通过、Runner 中断、空运行、安装失败、API 不一致、产物变化或新进程未输出 PASS，任务均失败；失败报告仍上传。矩阵 fail-fast 关闭，一个 API 失败不会取消其他 API 的运行。

矩阵入口显式传入 windowFailureDiagnostics=true 和本次唯一的 windowFailureRunId。在三个窗口相关测试类内，诊断规则位于 ActivityScenario 规则内部；失败时于 @After 执行后、宿主销毁前保存 window、input、activity activities 和 display 的 dumpsys 输出。每条系统采证命令使用五秒上限；采证失败只记录错误，不替换测试失败、不重试和不放宽断言。文件保存在宿主包的专用外部文件目录 /sdcard/Android/data/com.logan.ktoastapp/files/window-failures，按运行 ID 与用例区分；只供临时模拟器诊断使用。

运行入口失败时另保存 after-run-*.txt，并拉取本次用例的 window-failures 目录；result.json 的 failureDiagnostics 分别记录成功取得的文件与采证错误。收尾快照属于 Runner 结束后的状态；用例快照也已执行测试清理，两者都不能直接证明失焦首次发生的时刻或原因。Runner 崩溃、安装失败或诊断文件缺失仍保留原始失败，不将缺失诊断标记为取得成功，不读取其他运行的目录。普通成功用例不执行失败采证；专用自检会执行真实采证并由主机拉取。第 16 轮 PR CI 五版本无失败，采证分支未触发；1.2 首轮真实失败暴露目录问题，修复与后续验收见本版记录。

工作流支持 Pull Request、手动触发，以及 main 和 feature/device-matrix 分支推送。自动触发按代码、构建、校验工具、API 文档输入及工作流路径过滤，main 上仅推送发布结果或 README 文档不会重复构建设备矩阵；Pull Request 按整个 PR 相对目标分支的差异筛选路径，已有代码差异的 PR 即使仅追加发布结果文档，也可能重新运行。工作流已合入默认分支，手动入口现可使用。创建提交、推送、创建 PR 和触发远程运行须按维护者授权分别执行。推送成功只表示代码已上传，设备结论以对应提交的 Actions 结果和产物报告为准。

Actions 保留 14 天的 device-matrix-input、构建诊断及逐 API 报告。发布评估应保存工作流 URL、提交 SHA、各 result.json、instrumentation.txt、device-logcat.txt 与 R8 日志，核对 success=true、必需用例集合、实际 API、指纹、ABI、AAR/APK SHA 与新进程 PID。模拟器启动或下载失败只表示环境验证失败，不能改为通过；14 天后会过期，发版前应归档必要证据。远程运行通过后再更新上表，不能提前勾选发布门槛。发布前须核对最终合并提交的 main CI；候选分支的通过结果不能直接写成合并后 CI 已通过。若合并改变制品输入，应重新验证；仅文档结果记录变化仍须核对源码与产物关系。本版保留当前 AGP/compileSdk 的决策已记录，设备矩阵不会改变其官方支持状态。

### 已核验的云端结果（当前冻结候选）

2026-10-12 的 [矩阵运行 #6](https://github.com/logan0817/KToast/actions/runs/38208800274) 整体成功，耗时 14 分 17 秒。运行源码提交为 811c2535cf0a2d07dcb3229d72d7b3df8e755c6a，验证分支为 feature/device-matrix。后续仅更新发布结果文档的提交不改变该运行对应的代码与产物。构建任务完成 32 项 JVM 测试、库 Lint（No issues found）、57 项 Python 工具回归、12 项真实 Gradle 签名配置回归、候选发布校验及旧 AGP 消费者 Debug/R8 Release 构建；同一源码的本地 Python 工具回归为 57 项通过。该轮运行时工作流尚未合入 main，不能将候选分支结果作为合并后的 main CI 结果。

| 实际 API | 系统镜像构建标识 | ABI | 通过 | 显式跳过 | 未报告 | R8 新进程 PID |
| --- | --- | --- | --- | --- | --- | --- |
| 21 | Android 5.0.2 / LSY66K / 4174711 | x86 | 28 | 0 | 8 | 2722 |
| 30 | Android 11 / RSR1.240422.006 / 12134477 | x86_64 | 31 | 5 | 0 | 6270 |
| 31 | Android 12 / SE1A.220826.008 / 10564458 | x86_64 | 31 | 5 | 0 | 5086 |
| 35 | Android 15 / AE3A.240806.043 / 12960925 | x86_64 | 31 | 5 | 0 | 4660 |
| 36 | Android 16 / BE2A.250530.026.F3 / 13894323 | x86_64 | 31 | 5 | 0 | 5158 |

五个版本均无失败、无必需用例缺失，Runner 正常返回 -1，独立 R8 消费者新进程均输出 PASS，覆盖 Java 静态调试入口、Kotlin debugMode 属性读写、旧 Java、页面 Java/Kotlin、队列及延时取消。普通矩阵不启用无障碍时长、显示缺口、自由窗口 fixture；不能将这些跳过项写成已验证。API 35 的动态输入法用例同时观察宿主与独立提示子窗口，保持实际位置上移及完整避让断言，本轮未修改库定位实现。修复前的第 3 轮结果保留在本地历史归档，不作为当前 AAR 的运行证据。

所有设备复用以下同批产物，报告中的构建/运行提交及 SHA-256 已与下载字节核对：

| 产物 | SHA-256 |
| --- | --- |
| Release AAR（141900 字节） | e599259a80368161214c4e5961c2e804629d048e067585a920884b2ac4b633f1 |
| Demo Debug APK | 1bf064c31ed32d9d5d987e4ec8bf16076697fa0ec6d8db6b00542e9021932876 |
| 设备测试 APK | e7c34b6a0c3ac26e78efca3f5343b2bb3b36b5db53a742e7626f9c91c911272c |
| 旧 AGP R8 Release 消费者 APK | a33b947cdfb54bec1152a12e7f0d0055b6ea4ea41ee25fbdd9ab3d194c39af12 |
| sources JAR | 571832f360e25cac0fda90300949185d1963cc509e23d83b57c91c2e1fc8a8df |
| javadoc JAR | b0172b4335d454e929334ba87a46162845c48f4414dc7ab8d97e484f6db35680 |

本次原始 ZIP（同批输入、构建诊断、五个设备报告）通过 nightly.link 的公开制品中转下载，七个 ZIP 均与 GitHub 官方运行页的 digest 一致；官方页面确认整体 Success 和五个设备任务完成，逐版本结果另以原始报告核验，归档在维护者本地 build/reports/device-matrix-cloud/38208800274；verified-summary.json 汇总核验结果，download-provenance.json 记录下载方式与摘要来源，各 ZIP 保留完整设备指纹和原始日志。AAR、sources、javadoc 与第 5 轮及配置拆分前一致，APK 为本轮重新构建，按上表实际 SHA 验收。该目录受忽略规则保护，不进入源码提交；本地归档仍需维护者保留，不能依赖 Actions 14 天保留期作为永久存档。

本次 Actions 提示部分固定版本使用已弃用的 Node 20，并由托管 Runner 强制使用 Node 24；setup-java v4 也提示弃用。构建和设备任务已实际成功，这些提示不改变 Android 消费者门槛；后续维护 CI 时须单独验证 Action 迁移，不能与库生产依赖升级混为一谈。

### PR 与 main 合并检查

[PR #1](https://github.com/logan0817/KToast/pull/1) 已于 2026-10-12 以 merge commit 从 feature/device-matrix 合入 main，保留验证分支。合并提交为 8d415953a3d576ca85a9755c05c8ffd25cbc6a83，PR head 为 2063c7ca0ba10544e4b84e48ebf8b38133dfb4e0；合并提交与 PR head 文件树一致。

合并前最新的 [第 8 轮 PR 矩阵](https://github.com/logan0817/KToast/actions/runs/38218947884) 首尝试整体成功，耗时 13 分 11 秒。实际 checkout 为测试合并提交 565a3929cc8fd8db55a64a4fb3e6bd7f7960920f，文件树与 PR head 一致。API 21 为 28 项通过、0 项显式跳过、8 项未报告；API 30、31、35、36 各 31 项通过、5 项显式跳过、0 项未报告，均无失败。五个 R8 新进程 PID 分别为 2688、5836、5278、4426、4879，均输出 PASS；原始 ZIP 摘要与官方 digest 一致，完整 candidate.json 与本地冻结候选一致。归档为 build/reports/device-matrix-cloud/38218947884/verified-summary.json。

此前 [第 7 轮 PR 矩阵](https://github.com/logan0817/KToast/actions/runs/38214815247) 首尝试的 API 35 在 ActivityScenario.moveToState(CREATED) 等待停止状态时超时，未到库取消断言；日志中框架 EmptyActivity 被复用后关闭、重复 finish，宿主从 STOPPED 恢复到 RESUMED。一次原样重跑后成功，但广播来源和夹具潜在竞态未确定。首轮失败、重跑结果及各版本制品证据仍保留在对应归档，不能把第 8 轮成功视为消除了该竞态。

合并后的 [第 9 轮 main CI](https://github.com/logan0817/KToast/actions/runs/38221177164) 实际构建与运行提交均为 8d415953a3d576ca85a9755c05c8ffd25cbc6a83。首尝试整体 Failure，耗时 13 分 59 秒；构建成功，32 项 JVM 测试、库 Lint 无问题、12 项签名配置回归的原始报告已核对。工作流中的 57 项 Python 工具回归与旧 AGP 消费者构建也完成。AAR、sources、javadoc 和完整 candidate.json 与冻结候选一致，同批输入 ZIP 中 AAR/APK 实际字节均与 input.json SHA-256 一致。

API 35 首尝试为 27 项通过、5 项显式跳过、4 项失败、0 项未报告，未进入 R8 消费者运行。首次输入法用例在创建库页面作用域前报 IME did not become visible；显示期间输入法用例记录 finalIme=0；两个 Demo 用例出现 RootViewWithoutFocusException，宿主窗口持续无焦点。设备日志中两次 IME 请求在 PHASE_CLIENT_REQUEST_IME_SHOW 拒绝，并有 13 次 SurfaceComposerClient 图层事务提交回调超时；第 8 轮 API 35 无这类超时。库子窗口设置 FLAG_NOT_FOCUSABLE。现有证据支持进一步检查模拟器窗口同步异常，但未确定具体失焦原因，不能宣称已修复或确认产品无问题。

已保留首尝试七份原始 ZIP、官方 digest、逐版本日志及 failed-attempt-1-summary.json，仅对 API 35 原样重跑一次作诊断比较；其余成功任务及构建沿用首尝试。不改变代码、超时、断言或必需用例。第二次尝试于同日成功，耗时 4 分 40 秒；API 35 为 31 项通过、5 项显式跳过、0 项失败、0 项未报告，Runner 正常返回 -1，R8 新进程 PID 4675 输出 PASS。新设备日志没有首尝试的图层事务提交回调超时或客户端 IME 请求失败。官方运行状态为 Success，重跑 ZIP SHA-256 为 2cdd487677bb74dc84caaaa852ad083b4507ea3644365566d08c4546fbdf8e0a，与官方 digest 一致。两次使用相同构建提交和同批 APK/AAR；支持首尝试存在窗口同步偶发异常，但没有确定根因或消除潜在夹具问题。后续同类失败须补齐焦点归属和窗口状态证据并修复根因，不能持续靠重跑验收。

| API | 首尝试通过 | 显式跳过 | 失败 | 未报告 | R8 新进程 PID |
| --- | --- | --- | --- | --- | --- |
| 21 | 28 | 0 | 0 | 8 | 2701 |
| 30 | 31 | 5 | 0 | 0 | 6230 |
| 31 | 31 | 5 | 0 | 0 | 5332 |
| 35 | 27 | 5 | 4 | 0 | 未执行 |
| 36 | 31 | 5 | 0 | 0 | 4804 |

本轮 Demo APK SHA-256 为 ed429ea1fbf1c8438fb2c04bb37dca3b6c0117abc60df905365d2162cb1b20fa，设备测试 APK 为 4329be420b8bcf0e9d4cadddf0dfc074be1c0d4949d9ef357a265b4a81c90bb8，R8 消费者 APK 为 9e193c2652304ca1b809d640051432392d577882a6c5a9f9f4794bb72b7efb7b。AAR、sources、javadoc 摘要仍为第 6 轮表中对应值。证据归档为 build/reports/device-matrix-cloud/38221177164，首尝试额外保留于 attempt-1 子目录；verified-summary.json 汇总各 API 的实际验收尝试、官方状态、完整候选及原始摘要，同时保留 firstAttempt 失败记录。归档不进入源码提交。main CI 通过不等于正式签名与 Central 发布完成。

### 最新签名配置的 main CI 验收

2026-10-12 的 [第 10 轮 main CI](https://github.com/logan0817/KToast/actions/runs/38229044372) 首尝试整体 Success，耗时 21 分 46 秒；实际构建与运行提交均为 0b1a79c8a4edf2b90b1cc65b5133cb19ac7f5dde，未重跑任务。构建完成候选聚合校验、57 项 Python 工具回归和旧 AGP 消费者 Debug/R8 Release 构建；原始报告确认 32 项 JVM 测试无失败、错误或跳过，库 Lint 为 No issues found，19 项签名配置用例及结果与本地完整一致。API 35 较长耗时期间日志停在云端系统镜像安装，最终设备运行成功，未修改本机 SDK。

| API | 通过 | 显式跳过 | 失败 | 未报告 | R8 新进程 PID |
| --- | --- | --- | --- | --- | --- |
| 21 | 28 | 0 | 0 | 8 | 2702 |
| 30 | 31 | 5 | 0 | 0 | 6073 |
| 31 | 31 | 5 | 0 | 0 | 5394 |
| 35 | 31 | 5 | 0 | 0 | 4465 |
| 36 | 31 | 5 | 0 | 0 | 4805 |

五个版本均无必需用例缺失，原始 instrumentation.txt 重解析成功、Runner 返回 -1，R8 新进程日志与对应 PID 的 PASS 一致。普通矩阵的 fixture 跳过和 API 21 的 SDK 过滤未报告项继续不计成功。本轮 API 35 设备日志未出现此前 RootViewWithoutFocusException、IME did not become visible、PHASE_CLIENT_REQUEST_IME_SHOW 拒绝或 SurfaceComposerClient 图层同步超时；单次成功不等于此前偶发问题的根因已消除。

七份 ZIP 通过公开制品中转下载，SHA-256 均与 GitHub 官方运行页 digest 一致；同批输入的 APK/AAR 字节、构建及运行提交均与报告一致，完整 candidate.json 与本地冻结候选一致。逐版本指纹、ABI、原始日志、官方页面与下载来源归档于 build/reports/device-matrix-cloud/38229044372，汇总为 verified-summary.json 和 download-provenance.json；这些受忽略规则保护的本地证据需维护者保留，不依赖 Actions 14 天保留期。

本轮 Demo APK SHA-256 为 1a06c1bb8a13af4e6bed816792af0b538b4f2ed4891d94d9cafad3c07e4bdb68，设备测试 APK 为 9c04f95991f3091aaeec79e788955da364a5518d0e48bf1211a274041a2c6472，R8 消费者 APK 为 31812c22e16db71e5a129f6f356838d8e442dd5a2b340642683f53432a088291。AAR、sources 与 SNAPSHOT javadoc 摘要仍与冻结候选一致。后续仅发布结果文档提交不触发路径过滤后的矩阵，也不改变库源码或制品输入；该文档提交的正式包报告仍须重新生成并核对干净状态。现有 Action 弃用提示、AGP/compileSdk 支持风险及厂商真机/专用场景限制继续保留。

## 文档与兼容修复终审

README 中英文与页面说明的实际代码片段使用候选 AAR 编译：33 段 Kotlin、3 段 Java、5 段 XML 校验通过。补齐缺失 import、完整 Activity 示例及仅移除本库 Startup metadata 的配置，修正无效 Android drawable 和历史 Demo 链接；说明局部 DSL 的线程、页面取消与旧全局降级边界。八份权威 Markdown 的本地链接与敏感模式检查通过，没有新增沟通过程文档。

真实 Java 示例编译发现 debugMode 属性 setter 与静态 setDebugMode(boolean) 歧义。本轮使用 @set:JvmSynthetic 对 Java 源码隐藏属性 setter，保留原 JVM 签名和 Kotlin 属性读写；独立消费者新增 Java 静态调用与 Kotlin 属性切换断言，Debug 与 R8 Release 构建均使用当前候选 AAR。旧公开类签名、major=52、metadata=1.9.0 和生产依赖校验通过，设备矩阵已针对修复后同批产物重新运行并核验通过。

维护者已选择本机 GPG/keyring 签名，并保留环境变量内存签名供受控自动化使用。本地正式签名包已在干净源码下完成验收，具体提交与摘要见下方正式包记录；本机模式复用标准 signing.gnupg.keyName 中独立核对过的完整指纹，由 gpg-agent 解锁对应私钥；KTOAST_SIGNING_FINGERPRINT 可选。包验收需显式提供对应的导出公钥文件，不要求新建项目专用密钥。私钥与口令不发送到聊天或保存到仓库；公钥可获取性、命名空间权限和 Portal 服务端校验仍须在正式发布阶段确认。

## 本地检查顺序

发布配置按职责拆分：library-android/build.gradle.kts 保留 Android 编译、依赖、发布变体与 Dokka 的类型配置，应用 gradle/release-publishing.gradle.kts 管理文档打包、双模式签名、Publication 与本地仓库；根 build.gradle.kts 应用 gradle/release-verification.gradle.kts 提供本地验证入口。根脚本另应用 gradle/central-publishing.gradle.kts，提供受保护的命令上传入口；本地候选聚合同时执行 Central 模型检查，不签名、不读取上传凭据、不联网。

公开坐标与 POM 元数据集中在 gradle.properties：GROUP、VERSION、POM_ARTIFACT_ID、POM_NAME、POM_DESCRIPTION、POM_URL、许可证、开发者和 SCM 字段。VERSION 及现有 -PVERSION 覆盖方式保留；Demo 版本和版本目录中的已发布依赖版本按用途独立维护。依赖、插件和 SDK 版本继续由 libs.versions.toml 管理；已移除无调用方的 USE_SNAPSHOT；命令上传采用 Vanniktech Maven Publish 0.33.0，并只在库模块应用 base 插件，保留原有 Publication、Dokka 和双模式签名。该 Apache 2.0 构建插件不进入消费者 POM，不改变 minSdk、JVM 1.8 或生产依赖。校验工具中的固定坐标、生产依赖与旧 API 是独立兼容基线，不随构建配置自动改变，防止配置与预期一起变化而掩盖回归。

发布脚本拆分后的本地聚合构建、双模式实际签名与 12 项签名配置回归通过；五类 Staging 未签名产物及完整 SNAPSHOT Candidate 与拆分前逐字节一致。签名配置回归已加入云端构建并归档，本轮构建配置变动后的第 6 轮云端矩阵已完成验收，见上方当前设备证据。

选择 Android Studio 项目相同的 JDK 17 和 SDK，在项目根目录执行：

```bash
./gradlew verifyReleaseCandidate :app:assembleDebug :app:assembleDebugAndroidTest -PVERSION=1.1.0-SNAPSHOT --console=plain
```

verifyReleaseCandidate 聚合 JVM 测试、库 Lint、verifyPublicationTools 和 verifyCandidateArtifacts；后者先生成本地 Candidate，再使用构建 JDK 的 javap 和实际版本对应的文档包校验。原库发布任务和 Python 命令继续可用，直接运行 Python 时 PATH 中的 javap 须来自现有 JDK，也可传 --javap。签名配置回归单独执行，避免在正在运行的 Gradle 任务中启动嵌套 Gradle。Gradle 使用共享缓存及 daemon，不需要 clean。生成本地候选后，按 [独立消费者步骤](../TROUBLESHOOTING.md#本地候选发布与真实消费者) 验证 Debug/R8 Release，并确认日志 SHA 与 candidate.json 一致；安装 R8 APK 后检查新进程 KToastConsumer 的 PASS。

在专用且允许测试安装的设备执行核心回归，再按平台能力分别执行输入法、无障碍、缺口与窗口用例。安装遇到同名签名冲突时使用独立测试包，不卸载使用者已有应用；系统拒绝安装时记录为未验证，不绕过设备策略。

## 正式发布门槛

- [x] Java 调试入口修复后的 API 21、API 30/31、API 35/36 关键运行回归完成，记录真实设备、结果和未覆盖项。
- [x] AGP/compileSdk 支持范围完成评估；维护者选择保留 AGP 8.6/compileSdk 36，明确记录超出官方支持范围的风险。
- [x] 最终候选的 API、metadata、依赖、源码包、R8 消费者及相关运行结果可追溯，验证报告与产物 SHA 一致。
- [x] README 中英文、页面说明、CHANGELOG、注释完成终审；过程记录、敏感信息和临时文件不进入提交。
- [x] API 文档产物、内存/GPG 双模式签名、本地签名仓库及 Central ZIP 生成流程完成，两条路径的测试密钥演练通过。
- [x] 已验收候选源码通过 PR #1 纳入默认分支，合并提交与 PR head 文件树一致。
- [x] 最新签名配置的第 10 轮 main CI 首尝试成功，19 项配置回归及五版本原始报告已验收；此前 API 35 失败和单次重跑证据保留。
- [x] 正式签名包与已验收候选一致，完成正式版本与签名制品校验；干净源码报告、五份签名和 30 文件 ZIP 已核对。
- [ ] 新增命令发布流程纳入干净提交，通过合并后 main CI；重新准备可追溯报告并核对实际上传包与授权范围。
- [ ] 正式发布密钥、公钥可获取性和 Central 命名空间权限确认；获上传授权后执行 Portal 服务端校验及远端文件验收。
- [ ] 发布审批后统一更新正式版本号、Demo 版本及安装说明，记录最终发布坐标和校验和。

仓库提供本地 SNAPSHOT Candidate 和本地非 SNAPSHOT Staging 仓库，包含 AAR、sources、真实 javadoc、POM 和 module metadata。Staging 必须显式开启签名。远程上传只能通过根 :publishToMavenCentral 入口执行，并固定采用 USER_MANAGED；本地签名包不等于正式发布完成。内存签名的私钥和口令仅通过受控环境注入，本机 GPG 的口令由 gpg-agent/pinentry 管理；均不保存到仓库，也不放进命令行参数或公开构建日志。

## 签名与本地正式产物

签名默认关闭，只有 -PsignPublication=true 才开启。signingMode 只接受 memory 或 gpg，默认 memory，保留原有调用方式；选择模式本身不会开启签名。两种签名均须使用 --no-configuration-cache，避免将签名配置写入磁盘缓存；签名环境不使用公开 build scan 或调试日志。签名失败不会自动切换到另一种模式。

本机 GPG 模式使用已有 GnuPG 2 和维护者指定的密钥环，口令由 gpg-agent/pinentry 解锁，无需导出私钥或提供 KTOAST_SIGNING_KEY、KTOAST_SIGNING_PASSWORD。优先复用用户级 ~/.gradle/gradle.properties 中的 signing.gnupg.keyName，接受完整的 40 或 64 位十六进制主公钥或签名子密钥指纹，不接受短 ID。KTOAST_SIGNING_FINGERPRINT 是可选环境覆盖入口，不必为 KToast 重复配置；同时提供两项时必须相同（忽略大小写），任一已提供值不是完整指纹均拒绝。空白 keyName 视为未配置，显式空环境变量则拒绝；两项均未提供时停止，不自动使用 GPG 默认密钥。拒绝 signing.gnupg.passphrase 和旧版 GPG 设置，避免口令进入 Gradle 配置。

维护者可按 Gradle GPG 约定在受控本地配置中指定 signing.gnupg.executable 和 signing.gnupg.homeDir；未指定时沿用 GPG 自身的执行路径和密钥环选择。该模式只在签名任务执行时调用本机 GPG；普通候选构建不调用 GPG。首次使用或 agent 缓存失效时，维护者须在本机处理 pinentry；无法解锁、缺少私钥或指纹选择失败均终止签名，不改用其他密钥。

获正式版本安排并核对指纹后，在保持已发布版本配置不变的情况下，用命令参数准备本地 1.1.0 产物：

```bash
./gradlew :library-android:publishReleasePublicationToStagingRepository -PVERSION=1.1.0 -PsignPublication=true -PsigningMode=gpg --no-configuration-cache --console=plain
```

受控自动化继续使用内存 PGP 签名，环境注入 KTOAST_SIGNING_KEY（ASCII-armored 私钥内容）和 KTOAST_SIGNING_PASSWORD（口令；无口令测试密钥也须显式提供空值）。缺少任一项时配置失败，该模式不调用个人 GPG 密钥环。原命令仍有效，也可显式指定 -PsigningMode=memory：

```bash
./gradlew :library-android:publishReleasePublicationToStagingRepository -PVERSION=1.1.0 -PsignPublication=true --no-configuration-cache --console=plain
```

输出为 build/staging-repository/io/github/logan0817/ktoast/1.1.0。五类产物各有 .asc 签名及 MD5、SHA1、SHA256、SHA512 校验文件。Candidate 只接受 SNAPSHOT；Staging 拒绝未签名或 SNAPSHOT 版本。不要执行聚合 publish 代替上述明确目标。

在独立消费者中使用 -PktoastPublication=staging -PktoastVersion=1.1.0 执行 Debug/R8 Release 构建，并对 R8 APK 运行设备冒烟，具体命令见 [消费者说明](../TROUBLESHOOTING.md#本地候选发布与真实消费者)。SHA 校验覆盖两个变体，不能只检查文件存在。完成演练后重新生成 SNAPSHOT Candidate 和对应校验报告，再继续常规候选消费。

用受控位置的导出公钥验证。工具通过 --public-key 和 --fingerprint 接收文件路径和预期完整指纹；下面两个环境变量只是示例中传递参数的便捷方式，并非必须新建的配置。公钥来自维护者明确选定的现有发布密钥，公钥导出不包含私钥或口令；预期指纹须与签名所选密钥一致，不能仅从待验签名推导预期值：

```bash
python3 tools/prepare_central_bundle.py --version 1.1.0 --public-key "$KTOAST_PUBLIC_KEY_FILE" --fingerprint "$KTOAST_SIGNING_FINGERPRINT" --output build/central/ktoast-1.1.0.zip
```

该工具需要 Python 3.9+、现有 JDK 的 javap、GnuPG gpg。只在临时密钥环导入明确指定的公钥，不自动从网络取钥；核对五个签名、四类校验和、POM 必需信息、旧 API、字节码、依赖、当前源码包及真实 API 页面后才生成 ZIP 与相邻 .report.json。错误指纹、过期/撤销签名、产物篡改或缺项均拒绝；ZIP 不包含私钥、公钥、报告或 maven-metadata.xml。相邻报告额外记录批次名称、实际坐标、Git 提交、工作区是否有未提交文件，以及全部 30 个 ZIP 文件的大小和 SHA-256；源码未提交时可本地预验，但必须在源码收尾后重新生成可追溯的正式报告。

本地测试密钥演练的输出位于 build/reports/release-rehearsal/ktoast-1.1.0-TEST-KEY.zip，报告 rehearsal=true，临时私钥已删除。该包禁止上传。复现测试演练可在独立临时 GPG 目录生成短期测试签名密钥，将其私钥与口令仅注入本次 Gradle 进程，再给工具添加 --rehearsal，并使用以 -TEST-KEY.zip 结尾的文件名；结束时销毁测试私钥和密钥环，不修改个人密钥环。

2026-10-12 复核时，上述短期测试密钥已过期，校验工具按预期拒绝 KEYEXPIRED、EXPKEYSIG 状态。当时的成功结果仅作为流程演练记录，不代表旧演练包现在仍可通过签名验收；不得修改系统时间、放宽过期检查或将旧测试包作为正式包使用。

2026-10-12 新双模式演练使用 /tmp 下的隔离密钥环，先生成默认测试密钥，再明确选择另一把带口令测试密钥，验证 GPG/agent 没有使用默认密钥。原内存签名命令也实际执行签名；两条路径的 AAR、sources、javadoc、POM、module 未签名字节一致，分别通过五份签名、完整指纹、校验和、API/依赖检查并生成 30 文件 ZIP。缺少指定私钥时 GPG 正确失败。两份包均标记 rehearsal=true、文件名以 -TEST-KEY.zip 结尾，位于 build/reports/signing-modes/gpg 和 memory；禁止上传，测试私钥及临时 agent 已清理，保留的测试公钥仍受短期有效期限制。

本地 12 项真实 Gradle 配置回归、57 项 Python 工具回归、32 项 JVM 测试与库 Lint 通过；本地 Staging 消费者 Debug/R8 Release 构建通过（仅构建，运行证据以云端矩阵为准）。结束后已恢复 SNAPSHOT Candidate，AAR 与上表已验收候选一致。配置回归可运行 python3 -B tools/verify_signing_configuration.py -- ./gradlew；它使用空临时密钥环、不执行签名，并由云端构建阶段自动执行和归档。完整本地演练结果位于 build/reports/signing-modes/verified-rehearsal.json；正式密钥的后续本地预验见下节，不以演练结果认证正式发布。

### 标准本机 GPG 配置复用

本机入口读取标准 signing.gnupg.*，不强制新增 KToast 专用环境变量，不新建或复制私钥。受控用户级配置可使用：

```properties
signing.gnupg.keyName=<已核对的完整发布密钥指纹>
```

signing.gnupg.executable 和 signing.gnupg.homeDir 沿用已有设置，口令继续交给 gpg-agent。KToast 额外要求明确的完整指纹，避免默认选钥；不会读取 signing.keyId 作为替代，不自动改写用户级配置。内存签名入口保持不变。新增标准属性、64 位指纹、大小写一致、无效属性和空环境变量边界的配置回归；这些配置检查不签名、不访问个人密钥环。历史云端 12 项结果只对应当时版本；当前本地 19 项配置回归与 verifyReleaseCandidate 已通过，57 项工具回归实际运行，JVM/Lint 沿用有效增量结果，完整 candidate.json 与 main CI 冻结候选一致。本次配置改动已通过第 10 轮 main CI，云端 19 项原始配置结果与本地完整一致，未沿用历史 12 项结果。

### 现有发布密钥的本地正式包预验

2026-10-12 已核对维护者现有发布密钥的完整签名指纹，使用公开密钥服务器获取的公钥，在隔离密钥环中完成验签。随后以标准 -Psigning.gnupg.keyName 显式选择已核对的签名子密钥，实际执行 KToast 的 signReleasePublication 与本地 Staging 发布，不新建密钥、不改写用户级配置。

本次五份产物签名均有效，签名子密钥完整指纹为 2A426FA2812C7A8EF353F8E0DE5A09752A91006E，主密钥为 8BB25F160F45FD8919DC985A152B32281C3E9240。公钥可获取性已对该服务器实际核对；不代表 Central 账号权限或服务端发布校验已通过。公开公钥和签名可用于验收，私钥与口令不写入报告。

本地正式 Staging 的 AAR 与 sources 和 main CI 冻结候选逐字节一致。javadoc 包的 137 个差异文件均仅由版本标签 1.1.0-SNAPSHOT 改为 1.1.0，文件集合与其他内容一致。独立 AGP 7.2.2 消费者使用 staging/1.1.0 的 Debug/R8 Release 构建通过，两变体实际 AAR SHA 均为 e599259a80368161214c4e5961c2e804629d048e067585a920884b2ac4b633f1；本轮未重新运行消费者设备冒烟，历史设备证据仅对应同字节 AAR 的已验收批次。

build/central/ktoast-1.1.0.zip 已通过五份签名、四类校验和、30 个 ZIP 文件、旧 API、字节码、依赖、源码及真实 API 文档检查。签名配置提交 0b1a79c8a4edf2b90b1cc65b5133cb19ac7f5dde 后，在干净源码下重新生成包与报告，rehearsal=false、source.dirty=false；ZIP SHA-256 为 8aa71f9f4953aa39c07db7fd28b54717780ce0b90de75418abdcab67d726a762。本地 Staging 更新成功，签名任务复用此前实际生成且本次验签有效的五份签名。后续仅发布结果文档收尾时重新生成报告，最新 source.commit 以实际报告为准；文档收尾不改变库源码或制品输入。此前 source.dirty=true 的预验包保留于 build/reports/signing-modes/precommit-bundle，禁止上传。常规 SNAPSHOT Candidate 已恢复并与冻结候选完整一致；后续重新打包仍须核对实际摘要。

签名配置回归报告位于 build/reports/signing-modes/configuration.json，包报告为 build/central/ktoast-1.1.0.report.json，均不提交。此次没有上传、创建标签或正式发布，仓库常规安装坐标继续为 1.0.5。

## Maven Central 命令上传与人工审核

采用 Gradle 根 :publishToMavenCentral 命令上传 Central Portal，批次保持 USER_MANAGED，正式发布仍需单独操作和授权。本地 ZIP 遵循 [官方产物、签名、校验和与 POM 要求](https://central.sonatype.org/publish/requirements/)。上传和发布属于外部操作，须分别获得授权。

1. 确认最终发布提交的 main CI 成功，核对签名制品与已验收候选的关系；确认 Central 账号对 io.github.logan0817 命名空间有发布权限，正式公钥可按 Central 要求获取且指纹正确。公开公钥到密钥服务器需单独授权。
2. 使用正式密钥生成 Staging 与 ZIP，核对版本、完整签名指纹及 ZIP SHA-256；不要复用 TEST-KEY 演练包。
3. 上传批次名称固定为 io.github.logan0817-KToast-1.1.0，实际 Maven 坐标仍为 io.github.logan0817:ktoast:1.1.0。名称用于人工识别；所有查询、release 或 drop 都依据本次上传返回的唯一 deployment ID，并核对内部坐标。
4. 执行下述 Gradle 根上传命令，等待整个构建和 build service 结束；上传网络调用在任务完成后的 service close 阶段发生，叶任务成功不能代表上传成功。该插件版本不保证在日志中打印 deployment ID；应从官方 API/Portal 按批次名、上传时间及内部坐标取得唯一 ID，再查询该 ID，保存 deploymentId、deploymentName、deploymentState、purls、errors；VALIDATED 只表示服务端校验通过，不表示正式上线。
5. 从该 ID 对应的 Portal 下载 AAR、sources、javadoc、POM、module 及五份 .asc 签名，保留 Maven 原文件名。使用下述离线工具核对下载字节、签名、批次状态与正式包报告；校验文件如已下载，也必须与 ZIP 一致。下载报告不包含 token、私钥或口令。
6. 正式发布前重新查询该 ID 的实时状态，只有远端验收、发布门槛及发布授权齐备后才执行 release。离线报告不能认证快照来源、账号权限或实时状态。

Central 登录网页的密码、GPG 私钥口令和 Central User Token 是三种不同的配置。上传复用受控用户级 Gradle 配置或受控环境中的标准 mavenCentralUsername、mavenCentralPassword，对应 Central User Token 的两部分；不把值写入项目、命令参数、报告或公开日志。现有 Chrome 登录仅用于查看账号和批次状态，不替代 Gradle token 配置。签名继续读取现有 signing.gnupg.* 或内存签名环境，不要求创建新密钥。

本地准备命令将原本的签名 Staging 和 Python 验收串成一个入口，不上传，可在源码修改期间预验；报告中的 dirty 标记仍真实记录：

```bash
./gradlew prepareCentralBundle -PVERSION=1.1.0 -PsignPublication=true -PsigningMode=gpg -PcentralPublicKeyFile="$KTOAST_PUBLIC_KEY_FILE" --no-configuration-cache --console=plain
```

在最终代码提交、main CI 验收和上传授权齐备后执行：

```bash
./gradlew :publishToMavenCentral -PVERSION=1.1.0 -PsignPublication=true -PsigningMode=gpg -PcentralPublicKeyFile="$KTOAST_PUBLIC_KEY_FILE" --no-configuration-cache --console=plain
```

根入口依次核对批次模型、干净源码及受控配置，生成并验收本地签名 ZIP，再让插件写入 build/publish/staging 下的本地仓库。网络上传前，tools/verify_central_staging.py 必须逐项核对暂存的 30 个文件与已验收 ZIP、报告、当前干净 HEAD，并再次验签；缺项、额外文件、符号链接、测试包、版本/坐标变化或任一字节差异均使构建失败。只允许当前 artifact 目录中 Gradle 的精确 Maven 元数据文件名，这些文件不会上传。插件版本固定为 0.33.0；批次名从现有 POM_NAME 取 KToast，校验其 staging 标识，不改变工程名 ktoast 或 Maven 坐标。升级插件前必须重新验证内部模型字段与失败时不上传的行为。

Gradle 发布签名文件时还会为五份 .asc 生成四类校验和，版本目录实际有 50 个文件。根入口显式传入 --normalize-signature-checksums：验收工具先完成全部 30 个冻结文件比对、真实验签及 20 个签名派生校验和验证，再仅从 build/publish/staging 的插件临时子目录移除这 20 个派生文件。实际上传仍精确包含冻结包的 30 个文件，报告中的 normalizedSignatureChecksums 留存被验证并移除的文件摘要。派生文件缺项、校验错误或验签失败时不移除；未知文件继续拒绝。直接调用验收工具默认只读，不接受这 20 个额外文件；显式规范化也禁止修改 Candidate/Staging 等其他仓库。此步骤不改变签名、制品或本地冻结 ZIP。

门禁拒绝直接执行模块上传、createStagingRepository、通用 publish、publishAndReleaseToMavenCentral、releaseRepository 和 dropRepository；也拒绝上传时附带 clean、跳过任务、--continue 或 configuration cache。离线任务图请同时使用 --dry-run；单独 --offline 不保证第三方插件不联网，因此实际离线上传被门禁拒绝。本轮不提供正式 release/drop 自动入口，后续按具体 ID 与独立授权执行。

常规候选校验含 verifyCentralDeploymentName。上传任务图回归由 tools/verify_central_publishing_configuration.py 使用空临时密钥环和虚构指纹执行，不签名、不上传；CI 归档 build/reports/central-publishing/configuration.json：

```bash
python3 -B tools/verify_central_publishing_configuration.py -- ./gradlew
```

tools/verify_central_service.py 在临时工程内将插件服务地址明确替换为 127.0.0.1 假 Portal，并使用虚构 token，验证验收失败时零请求、成功构建仅一次 USER_MANAGED 上传且无 release/drop。它不使用正式签名密钥，不联系 Central，也不证明真实账号权限或线上校验已通过；CI 同时执行并归档 service.json。

网络异常或响应丢失后，先在官方 API/Portal 按名称、时间、内部坐标核对是否已创建批次，确认后记录唯一 ID；不得直接重跑上传。上传成功后仍须按 ID 下载并验收文件。若官方组件查询报 Deployment components info not found，应保留错误并用状态及实际文件核验，不能仅凭该提示断言产物坏损或重复上传；发布后以 Maven Central 正式解析和真实下载字节为准。

插件自行生成 build/publish/staging/<UUID>.zip，其 ZIP 顺序、压缩和时间戳可能与本地验收 ZIP 不同；门禁要求 30 个内部文件逐字节一致。上传后需核对插件实际 ZIP、记录摘要及 deployment ID，不能把本地 ZIP 摘要当成实际网络 ZIP 摘要。若授权绑定了特定 ZIP 的 SHA，执行命令上传前应明确展示本次传输包的关系并确认授权覆盖范围。

验收目录按本次 deployment ID 区分，官方状态响应保存为 status.json，十个制品/签名文件保存到 artifacts 子目录。给 KTOAST_DEPLOYMENT_ID 设置上传返回的 ID 后执行：

```bash
python3 -B tools/verify_central_deployment.py \
  --bundle build/central/ktoast-1.1.0.zip \
  --bundle-report build/central/ktoast-1.1.0.report.json \
  --deployment-id "$KTOAST_DEPLOYMENT_ID" \
  --status "build/central/$KTOAST_DEPLOYMENT_ID/status.json" \
  --downloads "build/central/$KTOAST_DEPLOYMENT_ID/artifacts" \
  --public-key "$KTOAST_PUBLIC_KEY_FILE" \
  --fingerprint "$KTOAST_SIGNING_FINGERPRINT" \
  --output "build/central/$KTOAST_DEPLOYMENT_ID/verification.json"
```

该工具不读取 Central token、不联网、不上传、不 release/drop。只接受正式包、干净源码报告、匹配的 ID/名称/坐标、VALIDATED 且 errors 为空的快照；拒绝缺文件、符号链接、字节不一致、错误签名及测试演练包。报告记录核验时间、源码提交、状态快照与 ZIP SHA、逐文件结果和完整签名指纹，不把样例测试或本地快照核对写成实际远端运行。

如需替换未发布批次，先获得单独授权，按原 ID 核对状态及坐标后 drop，再上传和验收替换批次。替换后必须使用新返回的 ID；改名或修正文档不自动要求重新上传。已正式发布的坐标不能覆盖。

正式版本需核对 gradle.properties 的 VERSION/APP_VERSION_NAME/APP_VERSION_CODE、gradle/libs.versions.toml 的 ktoast-version、README 中英文坐标及 CHANGELOG 状态。Candidate 任务只允许 SNAPSHOT，不能用于正式上传。版本未上线前，README 的常规安装入口继续指向已发布版本。

发布后使用全新消费者解析正式坐标，确认实际下载产物的校验和、源码与 R8 运行结果，再更新发布状态。Maven Central 已发布版本不可按本地文件覆盖；发现问题时优先停止推广，使用者回退到 1.0.5，后续以修复版本重新发布。使用新页面 API 的消费者回退前需撤回相关调用。

### 命令发布流程本地验证与合并验收

PR #2 的第 11 轮 CI（38240427479）首尝试构建、32 项 JVM 测试、Lint、19 项签名配置、21 项发布配置及 2 项假 Portal 回归通过，完整 Candidate 与冻结产物一致；API 21、30、31、35 设备回归及 R8 消费者通过。API 36 在 KToastPlatformTest 的输入法前置条件失败，30 项通过、5 项跳过、1 项失败，R8 未执行，整轮不得作为发布验收通过的证据。原始日志显示显示请求在 PHASE_WM_ABORT_SHOW_IME_POST_LAYOUT 被中止，失败发生于创建作用域之前，不能据此确认库避让行为有缺陷。

第 12 轮 PR CI（38243306864）七份原始 ZIP 的官方摘要一致。API 21、30、31、36 的设备测试及 R8 消费者通过；API 35 为 30 项通过、5 项跳过、1 项失败，R8 未执行。失败仍发生于首次避让用例的真实键盘前置条件：编辑框已附着且窗口与编辑框均有焦点，但 IME Insets 为零。该用例日志记录了 Espresso 点击及输入服务绑定编辑框，未记录 IME 显示请求；不能把 API 36 本轮通过视为整个矩阵完成。

两个输入法用例使用现有 Espresso 点击编辑框，等待可交互窗口后断言编辑框已附着且具有窗口和编辑焦点，再明确调用 InsetsController 发起一次 IME 显示请求。首次避让仍要求五秒内取得非零 IME Insets，保留内容完整位于键盘上方的断言，并增加几何检查时键盘仍可见的断言及窗口/编辑框焦点诊断。没有增加重试、跳过或固定休眠，也未改库源码、生产依赖和发布产物。该调整须重新构建测试 APK 并通过五版本矩阵，不能沿用此前各轮的部分成功作为完成证明。

真实 Gradle 本地签名仓库复核确认版本目录为 50 文件，旧的 30 文件门禁会拒绝签名派生校验和。本轮加入临时目录规范化与 6 项边界回归，总计 79 项 Python 回归通过。实际 50 文件仓库的隔离副本也完成五份真实签名验证，20 个派生校验和全部核对后移除，剩余 30 个文件与冻结包一致；该格式预验显式替代源码门禁，不是上传验收。源码门禁、未知文件拒绝及校验/签名失败前不修改文件分别由回归覆盖，正式上传仍须在当前干净 HEAD 下完整执行根入口。

feature/release-pipeline 新增根上传入口、任务图门禁、插件暂存逐文件验收以及 CI 配置回归。prepareCentralBundle 已使用现有正式签名完成本地预验，五份签名有效，30 个内部文件与此前冻结正式包逐字节一致；AAR SHA-256 仍为 e599259a80368161214c4e5961c2e804629d048e067585a920884b2ac4b633f1。最初的未提交源码预验归档于 build/reports/central-publishing/local-preview，禁止上传；旧冻结包保存在 build/reports/central-publishing/frozen-before-ime-fixture。提交 12e97dd 下的干净源码正式包 SHA-256 为 a60ec7129f7ef207f15bbc247b77d1e45296d8cf63e1be9bf0f84d6113b94618，内部 30 文件与旧包一致。后续源码或配置变更后必须重新准备报告，不得复用旧提交报告上传。

本地 verifyReleaseCandidate 已通过，包含 32 项 JVM 测试、无问题 Lint、79 项 Python 回归及 Central 模型检查；完整 candidate.json 与第 10 轮 main CI 冻结报告一致。21 项真实 Gradle 发布配置/任务图回归、19 项签名配置回归及 2 项本机假 Portal 的 build service 回归通过；规范化配置修改后重新执行的 21 项发布配置与 2 项服务回归也通过。AGP 7.2.2 独立消费者 Debug/R8 Release 构建通过，两个变体解析到的 AAR SHA 均与冻结候选一致；旧 Lint 的 Kotlin metadata 1.9 分析诊断仍存在。离线任务图或假服务不代表真实 Central 上传结果。

修正后的测试 APK 已在既有 Android Studio 构建环境成功编译；79 项 Python 回归再次通过。随后第 13 轮 PR CI（38245652907）首尝试整体成功，实际验收源码为 74fd48f71ae9d3238b96f15b22a4e03ceb540730，测试合并提交为 3453a003db435e62510acc937fba8a84157492ce，其文件树与 PR 源码一致。七份原始 ZIP 均与 GitHub 官方摘要一致，完整 Candidate 与本地及第 10 轮冻结候选一致，所有设备使用同批输入且原始 Runner 结果与 result.json 一致。

API 21 为 28 项必需用例通过、8 项 SDK 过滤用例未报告；API 30、31、35、36 各 31 项通过、5 项专用 fixture 用例跳过。五版本均无失败或必需用例缺失，R8 消费者新进程 PID 依次为 2699、6241、5057、4451、4969，均输出 PASS。API 30/31/35/36 的两个 IME 用例均通过；不把专用 fixture 的跳过计为成功，也不据单轮通过断言此前所有时序异常根因已消除。原始构建报告确认 32 项 JVM 测试、无问题 Lint、19 项签名配置、21 项发布配置和 2 项隔离服务回归通过。

在同一干净提交上重新生成的本地正式 ZIP SHA-256 为 bf02feeac641ddae93b486fad048cdf7bd1ca46f4bdf9a26e47ae27eaeab4b0a。真实 Gradle 50 文件仓库的隔离副本通过完整源码门禁、五份真实签名和 20 个派生校验和验证后规范化为 30 文件，与旧冻结包逐字节一致；本次未替代或跳过源码门禁。报告位于 build/reports/central-publishing/normalization-clean-head.json，仅证明本地上传前验收，尚未联系真实 Central 上传服务。

PR #2 已合并到 main，合并提交为 5be2c22bf1110e2f551c67c7b36c270444cbb34d，文件树与已授权的 ed8eb26 完全一致。[第 15 轮 main CI](https://github.com/logan0817/KToast/actions/runs/38258471248) 首尝试整体 Failure，未原样重跑；构建与运行提交均为该合并提交。七份原始 ZIP 均与 GitHub 官方摘要一致，同批输入的 APK/AAR 字节与报告一致，完整 candidate.json 与本地及冻结候选一致。原始构建报告确认 32 项 JVM 测试通过、库 Lint 无问题、19 项签名配置、21 项发布配置和 2 项隔离服务回归通过。

| API | 通过 | 显式跳过 | 未报告 | 失败 | R8 新进程 |
| --- | ---: | ---: | ---: | ---: | --- |
| 21 | 28 | 0 | 8 | 0 | PID 2710，PASS |
| 30 | 31 | 5 | 0 | 0 | PID 6164，PASS |
| 31 | 31 | 5 | 0 | 0 | PID 5466，PASS |
| 35 | 27 | 5 | 0 | 4 | 未执行 |
| 36 | 31 | 5 | 0 | 0 | PID 5410，PASS |

API 35 首次输入法前置用例及两个 Demo 交互用例报 RootViewWithoutFocusException，宿主持续无窗口焦点；首次输入法失败尚未创建库作用域。显示期间输入法用例的 finally 强制把重新取得的 content.rootView.layoutParams 转为 WindowManager.LayoutParams，实际为 FrameLayout.LayoutParams，导致 ClassCastException，覆盖了原始失败；不能据此推断被覆盖的原异常类型。持续失焦的根因尚未确定，不把其他版本通过解释成 API 35 问题已修复。

本地在 fix/window-test-diagnostics 调整该收尾逻辑：保存注册时的提示根视图及两个 ViewTreeObserver，按原对象清理；读取窗口参数使用安全转换，保留附着状态；若清理也失败，将其作为原异常的 suppressed 信息，不替换首个异常。三个相关测试类补充上述失败采证规则，矩阵工具加入收尾快照与本次目录拉取。未改变生产库、依赖、工具链、用例集合、等待上限或通过断言。83 项 Python 回归通过，覆盖采证超时、缺失文件、Runner 超时及安装失败等路径；:app:assembleDebugAndroidTest 使用既有环境离线编译通过。

实际编译的 WindowFailureDiagnosticsRule 另通过五项 JVM 边界自检：真实 JUnit RuleChain 保持原异常对象且在宿主销毁前采证、未启用时不执行 shell、非法运行 ID 被拒绝、单个系统服务异常不打断其他采证、fixture 假设跳过不触发失败采证。Android 系统服务使用替身；此结果只验证控制流程，不证明设备权限、dumpsys 内容和 adb 文件拉取可用。自检入口与 class SHA 记录位于本地 build/reports/release-freeze/check_window_diagnostics_rule.py 及 window-rule-selfcheck/result.json，不进入发布产物。这是提交前的本地阶段记录；后续普通窗口回归已在第 16、17、18 轮通过，但均未触发设备失败采证分支，不宣称持续失焦根因已解决。

第 15 轮证据归档于 build/reports/device-matrix-cloud/38258471248，verification-summary.json 的 accepted=false；失败结果必须保留。干净 main 下的正式 ZIP SHA-256 为 3b23d39ecf27cefa6eb36f424681e82ce838873a1430149664dc2dcc580e0d12，30 个内部文件与先前冻结包一致；该包只有本地签名及文件验收，未上传。后续修复完成并通过新 PR 与最终 main CI 后，须在干净提交下重新准备发布报告，核对实际插件 ZIP 与授权范围，再执行真实上传及远端验收。

### 窗口测试清理与诊断的 PR 验收

[PR #3](https://github.com/logan0817/KToast/pull/3) 的 [第 16 轮 CI](https://github.com/logan0817/KToast/actions/runs/38267311302) 首尝试整体 Success，未重跑。实际源码为 fb157aa90c3ecb8d4008e1027b91a3dcec1c24c6，测试合并提交为 c47c3bec27ff6a018fb208881b23de2ebbe8ea7a，其父提交为 main 的 5be2c22 和该 PR head，文件树与 head 完全一致。七份原始 ZIP 均与 GitHub 官方摘要一致；同批输入 APK/AAR 的实际字节、构建和运行提交均匹配，完整 candidate.json 与本地及冻结候选一致。

| API | 通过 | 显式跳过 | 未报告 | 失败 | R8 新进程 |
| --- | ---: | ---: | ---: | ---: | --- |
| 21 | 28 | 0 | 8 | 0 | PID 2720，PASS |
| 30 | 31 | 5 | 0 | 0 | PID 6230，PASS |
| 31 | 31 | 5 | 0 | 0 | PID 5432，PASS |
| 35 | 31 | 5 | 0 | 0 | PID 4748，PASS |
| 36 | 31 | 5 | 0 | 0 | PID 4977，PASS |

原始 Runner 结果重解析与 result.json 一致，五版本均正常返回 -1、无必需用例缺失。R8 消费者的新进程 PID 与对应 PASS 日志一致；专用 fixture 跳过及 SDK 过滤未报告项不计成功。原始构建报告确认 32 项 JVM 测试通过、库 Lint 无问题、19 项签名配置、21 项发布配置及 2 项隔离服务回归通过；官方构建日志确认执行 83 项 Python 回归。未改变生产库、工具链或生产依赖。

本轮窗口测试及普通 IME 场景实际通过，但所有版本都没有失败，新增诊断规则的设备采证及 adb 拉取分支未触发，不能将其写成设备失败路径已经验证。第 15 轮持续失焦根因仍未确定；本轮成功不证明该问题已消除，也不替代合并后的 main CI。证据归档于 build/reports/device-matrix-cloud/38267311302，verification-summary.json 的 accepted=true 只表示本次 PR 验收通过。后续仅记录结果的文档提交不改变已验收的测试或生产库源码；最终 PR 检查和合并后的 main CI 状态仍须分别核对。

### 最终 main 与真实 Central 上传验收（发布前记录）

最终 PR head f9146fd 的 [第 17 轮 CI](https://github.com/logan0817/KToast/actions/runs/38269476190) 首尝试通过。PR #3 经授权合并后，[第 18 轮 main CI](https://github.com/logan0817/KToast/actions/runs/38273388599) 于 2026-10-13（北京时间）首尝试整体 Success，未重跑。构建与设备运行提交均为 3d0abe5886e3b89e97ebd1b1132cc1fc0ae4c51e；合并父提交为 5be2c22 和 f9146fd，文件树与授权 head 一致。

| API | 通过 | 显式跳过 | 未报告 | 失败 | R8 新进程 |
| --- | ---: | ---: | ---: | ---: | --- |
| 21 | 28 | 0 | 8 | 0 | PID 2711，PASS |
| 30 | 31 | 5 | 0 | 0 | PID 6205，PASS |
| 31 | 31 | 5 | 0 | 0 | PID 5075，PASS |
| 35 | 31 | 5 | 0 | 0 | PID 4476，PASS |
| 36 | 31 | 5 | 0 | 0 | PID 4938，PASS |

七份原始 ZIP 与 GitHub 官方摘要一致，同批 APK/AAR 字节及构建提交匹配；原始 Runner 重解析与报告一致，五版本均返回 -1、无必需用例缺失。完整 candidate.json 与本地及冻结候选一致。原始报告确认 32 项 JVM、无问题 Lint、19 项签名配置、21 项发布配置及 2 项隔离服务回归通过；官方可见构建日志确认 83 项 Python 回归通过。归档为 build/reports/device-matrix-cloud/38273388599，verification-summary.json 的 accepted=true。

API 31 全量 logcat 导出出现 UTF-8 解码错误，result.json 已记录 logcatError；instrumentation.txt 及 R8 专用日志完整并已核验，不把缺少全量诊断日志写成完整采证。所有版本无测试失败，因此设备失败采证分支仍未触发；此前持续失焦根因未确定。厂商真机、专用 fixture 及 AGP/compileSdk 官方支持范围限制继续保留。

在上述干净 main 提交下执行 prepareCentralBundle 及 Gradle 根 :publishToMavenCentral，沿用既有 Android Studio 构建环境、正式 GPG 密钥与标准受控 Central 配置。根入口完成干净源码、坐标、USER_MANAGED 模型、本地包和插件暂存验收后，整个构建及 build service 成功结束。20 个签名派生校验和经验证后仅在插件临时目录规范化，实际 ZIP 包含 30 个预期文件。未执行 release/drop 或标签操作。

| 记录项 | 实际值 |
| --- | --- |
| 发布源码 | 3d0abe5886e3b89e97ebd1b1132cc1fc0ae4c51e，上传时 dirty=false |
| 批次名 | io.github.logan0817-KToast-1.1.0 |
| Maven 坐标 | io.github.logan0817:ktoast:1.1.0 |
| deployment ID | a956146e-2061-4eb0-9319-85fc7cc534f0 |
| 本地验收 ZIP SHA-256 | cc933813dbba6926d9bdd4fed796e5bb5553a426f0025098652f1c004347a77a |
| 插件实际上传 ZIP SHA-256 | 1b4fa2afd1e8a1946e7e8c1793fbda1bcc01f689a44f3e16edbdb24a710daeca |
| 官方状态 | VALIDATED，errors 为空，warnings 为空 |
| 官方已发布查询 | published=false |

两份 ZIP 的封装摘要不同，30 个内部文件逐字节一致；均已归档。正式 AAR SHA-256 仍为 e599259a80368161214c4e5961c2e804629d048e067585a920884b2ac4b633f1，sources 仍为 571832f360e25cac0fda90300949185d1963cc509e23d83b57c91c2e1fc8a8df，正式 javadoc 为 72c5d69d75036c2bc4bdd8e6b57b90c8ba8245db94c3d2e76fca2f278fff2acc。正式文档与 SNAPSHOT 文档的版本标签关系保持此前核验结果。

通过该 ID 的官方认证 API 下载全部 30 个制品、签名及校验文件，逐文件与验收 ZIP 匹配，再运行 tools/verify_central_deployment.py 验证五份真实签名、名称、坐标、ID 和 VALIDATED 状态。随后官方实时状态及已发布查询均为 HTTP 200，仍为 VALIDATED、无错误无警告、published=false。该结果包含真实官方查询和下载证据，不仅是离线样例通过；离线工具本身仍不认证网络来源。

本地证据目录为 build/central/a956146e-2061-4eb0-9319-85fc7cc534f0，包含原始/最终状态、官方查询来源、30 个下载文件、签名验收、实际插件 ZIP 及 acceptance-summary.json。证据受忽略规则保护，不包含 token、私钥或口令，不进入源码提交。上述记录对应发布前的上传验收，PR #4 的文档收尾没有改变产物或发布源码，也没有再次上传。随后正式发布及公开版本入口同步结果见下一节。

### 1.1.0 正式发布与正式仓库验收

2026-10-13 经独立发布授权，在 main 637ae7c 上先重新查询指定 deployment ID：状态为 VALIDATED、errors 为空、warnings 为空、published=false；已下载 30 个文件与冻结报告一致。使用受控标准 Central Token 对官方 /api/v1/publisher/deployment/a956146e-2061-4eb0-9319-85fc7cc534f0 仅发送一次 POST，2026-10-13 12:03:58（北京时间）收到 HTTP 204，随后进入 PUBLISHING。没有重新上传、drop 或修改批次坐标。

2026-10-13 12:13:19（北京时间）官方状态为 PUBLISHED，已发布查询为 true，正式 Maven Central 的 POM 返回 HTTP 200。status 接口的 purls 为空，errors.common 仍包含 Deployment components info not found；原始响应保留，不能写成发布后无诊断。实际正式下载和消费者解析是独立证据，不修改响应或仅以界面状态验收。

从 https://repo.maven.apache.org/maven2/io/github/logan0817/ktoast/1.1.0/ 下载全部 30 个文件，与冻结 ZIP 及上传验收文件逐字节一致；五份正式签名有效，POM、旧公开 API、minSdk 21、major=52、metadata 1.9、生产依赖、sources 与 javadoc 检查通过。AAR SHA-256 为 e599259a80368161214c4e5961c2e804629d048e067585a920884b2ac4b633f1，发布源码仍为 3d0abe5886e3b89e97ebd1b1132cc1fc0ae4c51e，不改写为发布操作所在的文档提交。

全新独立消费者沿用 AGP 7.2.2、Gradle 7.4.2、Kotlin 1.9.22、compileSdk 31、minSdk 21 与 JVM 1.8，KToast 仅允许从正式 Maven Central 解析；构建前共享缓存没有 1.1.0 坐标，不使用本地候选或未发布批次。Java/Kotlin 编译、Debug 与 R8 Release 构建通过，两变体解析 AAR SHA 均与正式产物一致。旧 Lint 对 metadata 1.9 的分析诊断仍存在，SDK XML 版本提示也保留。临时报告任务首次写错 APK 文件名，改为读取 AGP 实际 output-metadata.json 后增量验收通过，没有修改源码、测试断言或依赖。

发布后无本机连接设备，未再次执行设备运行；第 18 轮五版本矩阵已运行相同 AAR 字节，不能将它描述为正式仓库新消费者 APK 的运行结果。厂商真机、fixture 和此前失焦诊断限制继续保留。

本地证据位于 build/central/a956146e-2061-4eb0-9319-85fc7cc534f0/formal-release：release-request.json、publication-confirmed.json、status-observations.jsonl、official-repository-verification.json、consumer-verification.json、consumer-build.log 及正式下载文件。证据受忽略规则保护，不含凭据，不提交。

发布后的本地收尾将 VERSION、APP_VERSION_NAME、版本目录及中英文安装坐标同步为 1.1.0，Demo APP_VERSION_CODE 从 5 顺序增为 6，并更新 CHANGELOG、页面说明和路线状态；不改变库源码、依赖、工具链或已发布产物，不再次上传。Git 提交、推送、PR、合并、标签和 GitHub Release 仍须分别明确授权。

### 1.1.0 标签与 GitHub Release

PR #5 已合并到 main 4663690f37b708288691effe55bbcda904182d08，文件树与已授权 head bb58d09 一致。[第 20 轮 main CI](https://github.com/logan0817/KToast/actions/runs/38355264325) 六项检查首尝试全部成功，七份原始 ZIP 的摘要与官方记录一致，提交、构建输入及设备产物关联完成验收。API 21 为 28 项通过、8 项未报告；API 30、31、35、36 各为 31 项通过、5 项跳过，必需项及五版本 R8 消费者均通过。32 项 JVM 测试通过，库 Lint 为 0 个 issue。证据位于 build/reports/device-matrix-cloud/38355264325/verification-summary.json；候选 AAR 与正式 AAR 字节一致，但运行证据仍不代表正式仓库新消费者 APK 的设备验证。

2026-10-13 经独立授权，通过 Git 创建并推送附注标签 1.1.0，远端标签对象为 a127df2d8ed193f231e7cea7f18ba11ec72cd13f，剥离后的提交为 4663690f37b708288691effe55bbcda904182d08。标签目标与正式产物发布源码 3d0abe5886e3b89e97ebd1b1132cc1fc0ae4c51e 相比，仅有九个版本配置和文档文件变化，库源码一致；没有移动标签或重发 Central 产物。

[KToast 1.1.0 GitHub Release](https://github.com/logan0817/KToast/releases/tag/1.1.0) 已于当天 15:23（北京时间）正式发布，标记为 Latest，关联标签和提交正确。中文正文记录功能、兼容策略、验证证据和限制，附件仅包含 GitHub 自动生成的源码 ZIP 与 tar.gz，没有上传历史 Demo APK 或其他二进制文件。本地正文与页面截图位于 build/reports/github-release/1.1.0，受忽略规则保护，不进入源码提交。
