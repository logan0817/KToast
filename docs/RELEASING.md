# 1.1.0 发布检查表

## 当前状态与产物

Java 调试入口兼容修复后的候选已通过本地构建、API、文档示例及第 6 轮 API 21、30、31、35、36 云端关键设备矩阵；PR #1 的第 7 轮首尝试在 API 35 生命周期夹具切换超时，失败任务原样重跑一次后通过，原始报告与制品摘要已验收，尚未合并。正式签名及 Central 发布门槛尚未完成。Maven Central 正式版本与 README 安装坐标仍为 1.0.5；候选为 io.github.logan0817:ktoast:1.1.0-SNAPSHOT。这里集中维护验证结果与发布待办，不保留中间候选或沟通过程记录。

产物路径为 library-android/build/outputs/aar/library-android-release.aar，本地仓库为 build/candidate-repository。最新 AAR、sources、发布坐标、文件大小与 SHA-256 由 tools/verify_candidate.py 写入 build/reports/compatibility/candidate.json，以该报告和实际文件一致性为准。构建缓存、设备日志和临时测试配置不进入版本库。

API 文档由 Dokka 1.9.20 从 Kotlin 源码、KDoc 和 [API 模块说明](./API.md) 生成，输出为 library-android/build/docs/api/index.html，并作为 javadoc 分类 JAR 加入发布。该插件采用 Apache 2.0 许可证，仅属于构建依赖，不进入消费者 POM 或库生产依赖。本地文档不请求远程 Android/JDK/Kotlin 索引，可离线浏览。

## 工具链与兼容边界

构建使用 JDK 17、Gradle 8.14.3、AGP 8.6.0、Kotlin 1.9.23。库产物目标为 JVM 1.8，Demo/buildSrc 为 JVM 17；minSdk 21，生产依赖仍为 stdlib 1.9.23 与 Startup 1.1.1。

- AGP 8.6 官方最高支持 API 35，当前 compileSdk 36 超出该范围。android.suppressUnsupportedCompileSdk 只压制提示；维护者已选择本版保留当前配置并记录风险；已完成本地构建及消费者验证，但不以压制提示或设备运行作为官方支持认证。
- AGP 7.2.2 消费者实测 Debug/R8 Release 构建通过，但内置 Lint 对 metadata 1.9 有解析诊断。JVM 1.8 消除了原高字节码警告，不代表修复该分析器限制，不承诺任意旧 AGP 或 Kotlin 编译器可用。
- AAR minCompileSdk=1、minAndroidGradlePluginVersion=1.0.0、无需 core library desugaring 是元数据检查结果，不等于全部消费者组合得到认证。
- 未裁剪 AAR 体积包含新增功能成本，不能直接等同于消费者最终 APK 增量；消费者体积评估应以相同构建条件下 R8 后结果为准。

## 验证矩阵

以下当前关键矩阵证据来自 2026-10-12 的发布配置拆分与双签名候选，包含 Java 调试入口兼容修复；API 24/37 及专用 fixture 结果单独保留为此前功能候选的覆盖记录。源码或构建设置影响行为、API、依赖或产物目标后，须重跑对应验证；仅文档和注释调整须更新 sources 并重新校验产物，不把历史运行结果写成新产物已运行。

当前已验收的构建源码为 811c2535cf0a2d07dcb3229d72d7b3df8e755c6a。本地与云端生成的 AAR、sources、javadoc 及完整 candidate.json 一致，32 项 JVM 测试、库 Lint、57 项工具回归、旧公开 API 与消费者验证通过。五个关键版本均运行同一批 APK/AAR；API 24/37、16KB 页面大小及专用场景属于此前候选的历史证据，未对本轮产物重复运行，不用于认证本轮新 AAR。

| 验证项 | 已有结果 | 限制 |
| --- | --- | --- |
| JVM 测试、库 Lint | 32 项通过；No issues found | 不替代设备运行 |
| Release AAR、候选发布、Demo 与测试 APK | 构建通过；候选额外包含真实 javadoc 包 | 本地 publish 不等于正式远程发布 |
| 发布与矩阵校验工具 | 候选校验 15 项、发布包 13 项、远端离线验收 14 项、矩阵工具 15 项，共 57 项回归通过；输入 APK/AAR 校验信息生成通过 | 离线样例不等于真实 Portal 校验；工具测试通过不等于远程设备运行通过 |
| 本地签名演练 | 五类产物签名通过，生成 30 文件 Central ZIP；错误指纹、产物篡改、签名损坏均拒绝 | 临时测试密钥已移除；未使用正式密钥、未上传或服务端校验 |
| 签名配置边界 | 新增 12 项真实 Gradle 配置回归通过；短/缺失指纹、冲突密钥、Gradle 口令、旧 GPG、configuration cache 均拒绝，缺少指定私钥的真实 GPG 签名失败 | 空测试密钥环与临时测试密钥不认证正式密钥可用 |
| 旧公开 API 与 metadata | 15 个旧公开类基线一致；major=52；关键 metadata=1.9.0 | 不承诺所有旧编译器 |
| 已编译 1.0.5 消费者 | Java/Kotlin 保持原字节码，替换本轮候选运行通过 | JVM 样例不执行窗口 |
| Kotlin 源码消费 | 1.9.22、2.0.21 配置样例编译运行通过；页面源码编译通过 | 此前源码样例验证；本轮旧 AGP 消费者使用 1.9.22，完整仓库使用 1.9.23 |
| AGP 7.2.2 消费者 | Gradle 7.4.2、Kotlin 1.9.22、compileSdk 31、JVM 1.8；Debug/R8 Release 通过 | metadata 分析限制保留；compileSdk 31 不等于 API 31 运行 |
| API 24 | 28 项核心设备回归通过；R8 Release 新进程 PASS | 此前候选结果；7 项高版本专用用例由 SDK 过滤，不计成功 |
| API 37、16KB 页面大小 | 原 33 项、交互时长 2 项、自由窗口 1 项分三次 Runner 通过；R8 Release 新进程 PASS | 此前候选结果；缺口与自由窗口仅覆盖专用模拟器场景 |
| 超大内容复核 | 修正为显式 6000px 布局宽度的同一用例在 API 24/37 各通过 | 重复复核不计新增独立用例 |
| API 21 | 云端专用模拟器 28 项必需回归通过；R8 Release 新进程 PASS | 8 项由 SDK 过滤、无逐项状态，不计成功 |
| API 30/31 | 两个版本各 31 项必需回归通过；R8 Release 新进程均 PASS | 每个版本 5 项专用 fixture 明确跳过，不计成功 |
| API 35/36 | 两个版本各 31 项必需回归通过；R8 Release 新进程均 PASS | 每个版本 5 项专用 fixture 明确跳过；不替代厂商真机 |
| 更多厂商与分屏 | 未验证 | 不将自由窗口结果推广到全部厂商和窗口模式 |

API 33 的旧验证仅对应更早稳定性阶段，不用于认证当前完整 1.1 候选。设备测试的 SDK 过滤、fixture 跳过、失败和成功必须分别记录。无障碍、显示缺口、自由窗口的专用配置及恢复要求见 [TROUBLESHOOTING.md](../TROUBLESHOOTING.md)。

当前本地不下载缺失测试镜像，不调整 AGP/compileSdk。属性 setter 的 JVM synthetic 兼容修复已包含于本轮产物；发布校验工具与工作流对齐后，重新完成下述云端关键矩阵。设备项按原始 Runner、R8 日志和产物报告核对；官方支持范围风险及专用场景覆盖限制继续保留。

## 云端设备矩阵

[Candidate device matrix](../.github/workflows/device-matrix.yml) 使用 GitHub 托管 Ubuntu Runner，为 API 21、30、31、35、36 分别启动临时模拟器。镜像只在云端 Runner 下载，不占用或修改维护者本机 SDK。API 21 使用官方 default/x86 镜像，其余使用 google_apis/x86_64；官方仓库目录已确认包含这些镜像，目录存在不代表模拟器已成功运行。

构建任务沿用 JDK 17、两套现有 Gradle Wrapper 和现有依赖，先完成 JVM 测试、库 Lint、Demo/测试 APK、SNAPSHOT 发布校验及旧 AGP 消费者 Debug/R8 Release 构建。所有设备任务下载同一批 APK/AAR，校验 SHA-256 与构建提交后再安装，避免每个 API 重建或使用旧候选。Actions 使用固定提交，工作流仅有 contents: read 权限，不签名正式产物、不上传 Maven Central。

SDK 准备沿用 Action 的 cmdline-tools，显式设置 packages: platform-tools，避免默认安装已下架的旧 tools 包；编译平台与 Build Tools 由工作流后续步骤明确指定。

- API 21 必须通过 Demo、生命周期与作用域共 28 项核心回归，并在新进程运行 R8 Release 消费者。
- API 30、31、35、36 另要求首次输入法避让、显示期间输入法移动及 RTL 动态定位，共 31 项必需回归，并运行同一 R8 APK。
- 无障碍时长、显示缺口和自由窗口仍使用专用 fixture；普通矩阵未配置这些 fixture，不能替代已有专用场景或厂商真机验证。

工具将 passed、skipped、failed 和 notReported 分开记录。SDK 过滤可能不产生逐项状态，未报告项不计为成功，也不猜测为已跳过。任一必需用例未通过、Runner 中断、空运行、安装失败、API 不一致、产物变化或新进程未输出 PASS，任务均失败；失败报告仍上传。矩阵 fail-fast 关闭，一个 API 失败不会取消其他 API 的运行。

工作流支持 Pull Request、手动触发，以及 main 和 feature/device-matrix 分支推送。自动触发按代码、构建、校验工具、API 文档输入及工作流路径过滤，单独维护发布结果或 README 不重复构建设备矩阵。新工作流尚未合入默认分支时，可通过该验证分支推送触发首轮运行；首次不依赖默认分支的手动入口。创建提交、推送、创建 PR 和触发远程运行须按维护者授权分别执行。推送成功只表示代码已上传，设备结论以对应提交的 Actions 结果和产物报告为准。

Actions 保留 14 天的 device-matrix-input、构建诊断及逐 API 报告。发布评估应保存工作流 URL、提交 SHA、各 result.json、instrumentation.txt、device-logcat.txt 与 R8 日志，核对 success=true、必需用例集合、实际 API、指纹、ABI、AAR/APK SHA 与新进程 PID。模拟器启动或下载失败只表示环境验证失败，不能改为通过；14 天后会过期，发版前应归档必要证据。远程运行通过后再更新上表，不能提前勾选发布门槛。发布前须核对最终合并提交的 main CI；候选分支的通过结果不能直接写成合并后 CI 已通过。若合并改变制品输入，应重新验证；仅文档结果记录变化仍须核对源码与产物关系。本版保留当前 AGP/compileSdk 的决策已记录，设备矩阵不会改变其官方支持状态。

### 已核验的云端结果（当前冻结候选）

2026-10-12 的 [矩阵运行 #6](https://github.com/logan0817/KToast/actions/runs/38208800274) 整体成功，耗时 14 分 17 秒。运行源码提交为 811c2535cf0a2d07dcb3229d72d7b3df8e755c6a，验证分支为 feature/device-matrix。后续仅更新发布结果文档的提交不改变该运行对应的代码与产物。构建任务完成 32 项 JVM 测试、库 Lint（No issues found）、57 项 Python 工具回归、12 项真实 Gradle 签名配置回归、候选发布校验及旧 AGP 消费者 Debug/R8 Release 构建；同一源码的本地 Python 工具回归为 57 项通过。工作流已经加入 main 推送触发，但尚未合入 main，不将本轮候选分支结果作为合并后的 main CI 结果。

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

### PR 合并检查

[PR #1](https://github.com/logan0817/KToast/pull/1) 从 feature/device-matrix 合入 main，当前尚未合并。2026-10-12 的 [第 7 轮 PR 矩阵](https://github.com/logan0817/KToast/actions/runs/38214815247) 首尝试失败：构建通过，API 21、30、31、36 的必需回归及 R8 新进程运行通过；API 35 为 30 项通过、5 项显式跳过、1 项失败，未进入 R8 消费者运行，不计整轮成功。

失败用例为 KToastScopeTest#workerPreparationCannotCrossStopOrReviveStoppedSubmission，在 ActivityScenario.moveToState(CREATED) 等待停止状态时超时，尚未执行到 KToast 取消结果断言。设备日志记录遮挡用的框架 EmptyActivity 被复用后关闭，并出现重复 finish 请求，宿主随后从 STOPPED 恢复到 RESUMED；无法由现有日志确定关闭请求的广播来源。已对失败的 API 35 任务原样重跑一次，不改库实现或断言；第二次尝试于同日成功，耗时 5 分 16 秒，仅 API 35 重新执行，其余成功任务和构建沿用首尝试；官方 Jobs API 的起止时间也确认这一点。此结果支持首轮为测试夹具偶发异常，但没有消除夹具的潜在竞态，也没有确定关闭广播来源。后续若同类问题复现，须修复夹具，不能持续靠重跑通过。

本轮实际 checkout 为 GitHub 测试合并提交 29ae7bd99c7cc2b532f23fc074fb4a7f71680c02，PR head 为 b10f932ef3e6d73e930a260b7c578b6faebee899，二者文件树一致。AAR、sources、javadoc 及完整 candidate.json 与第 6 轮和本地冻结候选一致；APK 按本轮 input.json 校验。首轮七份原始 ZIP 及第二次 API 35 ZIP 的 SHA-256 均与 GitHub 官方运行页一致。原始日志、首轮失败和综合验收报告归档于 build/reports/device-matrix-cloud/38214815247；verified-summary.json 记录每个 API 的实际尝试与制品摘要，不能以第 6 轮成功代替本轮 PR 检查。

本轮最终设备结果如下；五个版本使用同一批输入，Runner 均正常结束，必需用例无缺失。第二次尝试只补齐 API 35，不能描述为五版本全部重新运行。

| API | 验收尝试 | 通过 | 显式跳过 | 未报告 | R8 新进程 PID |
| --- | --- | --- | --- | --- | --- |
| 21 | 首次 | 28 | 0 | 8 | 2702 |
| 30 | 首次 | 31 | 5 | 0 | 6358 |
| 31 | 首次 | 31 | 5 | 0 | 5220 |
| 35 | 单次重跑 | 31 | 5 | 0 | 4520 |
| 36 | 首次 | 31 | 5 | 0 | 4896 |

本轮 Demo APK SHA-256 为 a566256c1e6f3b546c672f0a7a1f1fd59bf27765d50b34ca52babdcb01371319，设备测试 APK 为 4932864f56dc7c27513a3ef0a591d2ca9c297c3df0e2651fe01d96333b5d8b50，R8 消费者 APK 为 c77afb5b32fb6e30b16fdc87d408276a6b6463f82e0ec0d4a7f21125eb0277a0。AAR、sources、javadoc 摘要仍为第 6 轮表中对应值。构建原始报告另核对 32 项 JVM 测试、库 Lint 无问题和 12 项签名配置回归。PR 检查成功不等于实际合并后的 main CI 成功；合并及正式签名、上传、发布仍须分别完成门槛和授权。

## 文档与兼容修复终审

README 中英文与页面说明的实际代码片段使用候选 AAR 编译：33 段 Kotlin、3 段 Java、5 段 XML 校验通过。补齐缺失 import、完整 Activity 示例及仅移除本库 Startup metadata 的配置，修正无效 Android drawable 和历史 Demo 链接；说明局部 DSL 的线程、页面取消与旧全局降级边界。八份权威 Markdown 的本地链接与敏感模式检查通过，没有新增沟通过程文档。

真实 Java 示例编译发现 debugMode 属性 setter 与静态 setDebugMode(boolean) 歧义。本轮使用 @set:JvmSynthetic 对 Java 源码隐藏属性 setter，保留原 JVM 签名和 Kotlin 属性读写；独立消费者新增 Java 静态调用与 Kotlin 属性切换断言，Debug 与 R8 Release 构建均使用当前候选 AAR。旧公开类签名、major=52、metadata=1.9.0 和生产依赖校验通过，设备矩阵已针对修复后同批产物重新运行并核验通过。

维护者已选择本机 GPG/keyring 签名，并保留环境变量内存签名供受控自动化使用。正式签名包尚未生成；本机模式需要独立核对的 KTOAST_SIGNING_FINGERPRINT 和由 gpg-agent 解锁的对应私钥，包验收还需要 KTOAST_PUBLIC_KEY_FILE 指定的导出公钥。私钥与口令不发送到聊天或保存到仓库；公钥可获取性、命名空间权限和 Portal 服务端校验仍须在正式发布阶段确认。

## 本地检查顺序

发布配置按职责拆分：library-android/build.gradle.kts 保留 Android 编译、依赖、发布变体与 Dokka 的类型配置，应用 gradle/release-publishing.gradle.kts 管理文档打包、双模式签名、Publication 与本地仓库；根 build.gradle.kts 应用 gradle/release-verification.gradle.kts 提供本地验证入口。两个脚本不配置远程上传任务。

公开坐标与 POM 元数据集中在 gradle.properties：GROUP、VERSION、POM_ARTIFACT_ID、POM_NAME、POM_DESCRIPTION、POM_URL、许可证、开发者和 SCM 字段。VERSION 及现有 -PVERSION 覆盖方式保留；Demo 版本和版本目录中的已发布依赖版本按用途独立维护。依赖、插件和 SDK 版本继续由 libs.versions.toml 管理；已移除无调用方的 USE_SNAPSHOT 和未应用的 Vanniktech 插件声明。校验工具中的固定坐标、生产依赖与旧 API 是独立兼容基线，不随构建配置自动改变，防止配置与预期一起变化而掩盖回归。

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
- [x] API 文档产物、内存/GPG 双模式签名、本地签名仓库及 Central 手工上传包生成流程完成，两条路径的测试密钥演练通过。
- [ ] 最终发布源码纳入默认分支，合并后 main CI 成功，签名包与已验收候选一致。
- [ ] 正式发布密钥、公钥可获取性和 Central 命名空间权限确认；获上传授权后执行 Portal 服务端校验及远端文件验收。
- [ ] 发布审批后统一更新正式版本号、Demo 版本及安装说明，记录最终发布坐标和校验和。

仓库提供本地 SNAPSHOT Candidate 和本地非 SNAPSHOT Staging 仓库，包含 AAR、sources、真实 javadoc、POM 和 module metadata。Staging 必须显式开启签名。没有远程 Gradle 仓库地址或自动上传任务；本地签名包不等于正式发布完成。内存签名的私钥和口令仅通过受控环境注入，本机 GPG 的口令由 gpg-agent/pinentry 管理；均不保存到仓库，也不放进命令行参数或公开构建日志。

## 签名与本地正式产物

签名默认关闭，只有 -PsignPublication=true 才开启。signingMode 只接受 memory 或 gpg，默认 memory，保留原有调用方式；选择模式本身不会开启签名。两种签名均须使用 --no-configuration-cache，避免将签名配置写入磁盘缓存；签名环境不使用公开 build scan 或调试日志。签名失败不会自动切换到另一种模式。

本机 GPG 模式使用已有 GnuPG 2 和维护者指定的密钥环，口令由 gpg-agent/pinentry 解锁，无需导出私钥或提供 KTOAST_SIGNING_KEY、KTOAST_SIGNING_PASSWORD。必须通过受控环境给出 KTOAST_SIGNING_FINGERPRINT，接受完整的 40 或 64 位十六进制主公钥或签名子密钥指纹，不接受短 ID。Gradle 使用该指纹选择签名密钥，不依赖 GPG 默认密钥；若已有 signing.gnupg.keyName，必须与该指纹相同（忽略大小写）。拒绝 signing.gnupg.passphrase 和旧版 GPG 设置，避免口令进入 Gradle 配置。

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

用受控位置的导出公钥验证；KTOAST_PUBLIC_KEY_FILE 是公钥文件路径，KTOAST_SIGNING_FINGERPRINT 是独立核对过的完整主公钥或签名子密钥指纹：

```bash
python3 tools/prepare_central_bundle.py --version 1.1.0 --public-key "$KTOAST_PUBLIC_KEY_FILE" --fingerprint "$KTOAST_SIGNING_FINGERPRINT" --output build/central/ktoast-1.1.0.zip
```

该工具需要 Python 3.9+、现有 JDK 的 javap、GnuPG gpg。只在临时密钥环导入明确指定的公钥，不自动从网络取钥；核对五个签名、四类校验和、POM 必需信息、旧 API、字节码、依赖、当前源码包及真实 API 页面后才生成 ZIP 与相邻 .report.json。错误指纹、过期/撤销签名、产物篡改或缺项均拒绝；ZIP 不包含私钥、公钥、报告或 maven-metadata.xml。相邻报告额外记录批次名称、实际坐标、Git 提交、工作区是否有未提交文件，以及全部 30 个 ZIP 文件的大小和 SHA-256；源码未提交时可本地预验，但必须在源码收尾后重新生成可追溯的正式报告。

本地测试密钥演练的输出位于 build/reports/release-rehearsal/ktoast-1.1.0-TEST-KEY.zip，报告 rehearsal=true，临时私钥已删除。该包禁止上传。复现测试演练可在独立临时 GPG 目录生成短期测试签名密钥，将其私钥与口令仅注入本次 Gradle 进程，再给工具添加 --rehearsal，并使用以 -TEST-KEY.zip 结尾的文件名；结束时销毁测试私钥和密钥环，不修改个人密钥环。

2026-10-12 复核时，上述短期测试密钥已过期，校验工具按预期拒绝 KEYEXPIRED、EXPKEYSIG 状态。当时的成功结果仅作为流程演练记录，不代表旧演练包现在仍可通过签名验收；不得修改系统时间、放宽过期检查或将旧测试包作为正式包使用。

2026-10-12 新双模式演练使用 /tmp 下的隔离密钥环，先生成默认测试密钥，再明确选择另一把带口令测试密钥，验证 GPG/agent 没有使用默认密钥。原内存签名命令也实际执行签名；两条路径的 AAR、sources、javadoc、POM、module 未签名字节一致，分别通过五份签名、完整指纹、校验和、API/依赖检查并生成 30 文件 ZIP。缺少指定私钥时 GPG 正确失败。两份包均标记 rehearsal=true、文件名以 -TEST-KEY.zip 结尾，位于 build/reports/signing-modes/gpg 和 memory；禁止上传，测试私钥及临时 agent 已清理，保留的测试公钥仍受短期有效期限制。

本地 12 项真实 Gradle 配置回归、57 项 Python 工具回归、32 项 JVM 测试与库 Lint 通过；本地 Staging 消费者 Debug/R8 Release 构建通过（仅构建，运行证据以云端矩阵为准）。结束后已恢复 SNAPSHOT Candidate，AAR 与上表已验收候选一致。配置回归可运行 python3 -B tools/verify_signing_configuration.py -- ./gradlew；它使用空临时密钥环、不执行签名，并由云端构建阶段自动执行和归档。完整本地结果位于 build/reports/signing-modes/verified-rehearsal.json，正式密钥解锁和正式包验收仍待执行。

## Maven Central 手工审核流程

采用 [Central Portal 手工上传 ZIP](https://central.sonatype.org/publish/publish-portal-upload/) 方案；本地 ZIP 遵循 [官方产物、签名、校验和与 POM 要求](https://central.sonatype.org/publish/requirements/)。上传和发布属于外部操作，须分别获得授权。

1. 确认最终发布提交的 main CI 成功，核对签名制品与已验收候选的关系；确认 Central 账号对 io.github.logan0817 命名空间有发布权限，正式公钥可按 Central 要求获取且指纹正确。公开公钥到密钥服务器需单独授权。
2. 使用正式密钥生成 Staging 与 ZIP，核对版本、完整签名指纹及 ZIP SHA-256；不要复用 TEST-KEY 演练包。
3. 上传批次名称固定为 io.github.logan0817-KToast-1.1.0，实际 Maven 坐标仍为 io.github.logan0817:ktoast:1.1.0。名称用于人工识别；所有查询、release 或 drop 都依据本次上传返回的唯一 deployment ID，并核对内部坐标。
4. 在 Portal 上传 ZIP，选择人工审核发布，不启用自动发布；界面若无法将上传验证与正式发布分开，应按实际按钮行为重新确认授权。保存官方响应的 deploymentId、deploymentName、deploymentState、purls、errors；VALIDATED 只表示服务端校验通过，不表示正式上线。
5. 从该 ID 对应的 Portal 下载 AAR、sources、javadoc、POM、module 及五份 .asc 签名，保留 Maven 原文件名。使用下述离线工具核对下载字节、签名、批次状态与正式包报告；校验文件如已下载，也必须与 ZIP 一致。下载报告不包含 token、私钥或口令。
6. 正式发布前重新查询该 ID 的实时状态，只有远端验收、发布门槛及发布授权齐备后才执行 release。离线报告不能认证快照来源、账号权限或实时状态。

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
