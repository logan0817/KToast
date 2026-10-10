# 1.1.0 发布检查表

## 当前状态与产物

Java 调试入口兼容修复后的候选已通过本地构建、API 与文档示例校验，API 21、30、31、35、36 云端关键设备矩阵待重跑，尚未满足全部正式发布门槛。Maven Central 正式版本与 README 安装坐标仍为 1.0.5；候选为 io.github.logan0817:ktoast:1.1.0-SNAPSHOT。这里集中维护验证结果与发布待办，不保留中间候选或沟通过程记录。

产物路径为 library-android/build/outputs/aar/library-android-release.aar，本地仓库为 build/candidate-repository。最新 AAR、sources、发布坐标、文件大小与 SHA-256 由 tools/verify_candidate.py 写入 build/reports/compatibility/candidate.json，以该报告和实际文件一致性为准。构建缓存、设备日志和临时测试配置不进入版本库。

API 文档由 Dokka 1.9.20 从 Kotlin 源码、KDoc 和 [API 模块说明](./API.md) 生成，输出为 library-android/build/docs/api/index.html，并作为 javadoc 分类 JAR 加入发布。该插件采用 Apache 2.0 许可证，仅属于构建依赖，不进入消费者 POM 或库生产依赖。本地文档不请求远程 Android/JDK/Kotlin 索引，可离线浏览。

## 工具链与兼容边界

构建使用 JDK 17、Gradle 8.14.3、AGP 8.6.0、Kotlin 1.9.23。库产物目标为 JVM 1.8，Demo/buildSrc 为 JVM 17；minSdk 21，生产依赖仍为 stdlib 1.9.23 与 Startup 1.1.1。

- AGP 8.6 官方最高支持 API 35，当前 compileSdk 36 超出该范围。android.suppressUnsupportedCompileSdk 只压制提示；维护者已选择本版保留当前配置并记录风险；已完成本地构建及消费者验证，但不以压制提示或设备运行作为官方支持认证。
- AGP 7.2.2 消费者实测 Debug/R8 Release 构建通过，但内置 Lint 对 metadata 1.9 有解析诊断。JVM 1.8 消除了原高字节码警告，不代表修复该分析器限制，不承诺任意旧 AGP 或 Kotlin 编译器可用。
- AAR minCompileSdk=1、minAndroidGradlePluginVersion=1.0.0、无需 core library desugaring 是元数据检查结果，不等于全部消费者组合得到认证。
- 未裁剪 AAR 体积包含新增功能成本，不能直接等同于消费者最终 APK 增量；消费者体积评估应以相同构建条件下 R8 后结果为准。

## 验证矩阵

以下历史设备与消费者运行证据来自 2026-10-10 的功能候选；Java 调试入口修复后的设备证据待更新。源码或构建设置影响行为、API、依赖或产物目标后，须重跑对应验证；仅文档和注释调整须更新 sources 并重新校验产物，不把历史运行结果写成新产物已运行。

功能候选的设备证据与最新构建分开记录。发布配置收尾后，32 项 JVM 测试、库 Lint、AAR/sources/javadoc 与发布元数据生成、Demo/测试 APK 构建重新通过；文档生成、签名及 ZIP 工具另有下表所列验证。签名 Staging 的旧 AGP 消费者 Debug/R8 Release 构建通过，两种变体解析 SHA 与签名演练报告一致，R8 APK 在 API 24 新进程输出 PASS。演练后已恢复 SNAPSHOT Candidate，增强校验及默认消费者 Debug/R8 Release 构建通过，两个变体的解析 SHA 与当前候选报告一致。下表 API 37 的完整设备结果属于注释调整前的功能候选，未对发布配置收尾产物重复运行。

| 验证项 | 已有结果 | 限制 |
| --- | --- | --- |
| JVM 测试、库 Lint | 32 项通过；No issues found | 不替代设备运行 |
| Release AAR、候选发布、Demo 与测试 APK | 构建通过；候选额外包含真实 javadoc 包 | 本地 publish 不等于正式远程发布 |
| 发布与矩阵校验工具 | 文档及发布包工具 26 项、矩阵工具 15 项回归通过；输入 APK/AAR 校验信息生成通过 | 工具测试通过不等于远程设备运行通过 |
| 本地签名演练 | 五类产物签名通过，生成 30 文件 Central ZIP；错误指纹、产物篡改、签名损坏均拒绝 | 临时测试密钥已移除；未使用正式密钥、未上传或服务端校验 |
| 签名配置边界 | 缺密钥/口令、未签名 Staging、签名开启 configuration cache 均明确拒绝 | 正式签名仍需受控环境注入 |
| 旧公开 API 与 metadata | 15 个旧公开类基线一致；major=52；关键 metadata=1.9.0 | 不承诺所有旧编译器 |
| 已编译 1.0.5 消费者 | Java/Kotlin 保持原字节码，替换候选运行通过 | JVM 样例不执行窗口 |
| Kotlin 源码消费 | 1.9.22、2.0.21 配置样例编译运行通过；页面源码编译通过 | 完整仓库使用 1.9.23 |
| AGP 7.2.2 消费者 | Gradle 7.4.2、Kotlin 1.9.22、compileSdk 31、JVM 1.8；Debug/R8 Release 通过 | metadata 分析限制保留；compileSdk 31 不等于 API 31 运行 |
| API 24 | 28 项核心设备回归通过；R8 Release 新进程 PASS | 7 项高版本专用用例由 SDK 过滤，不计成功 |
| API 37、16KB 页面大小 | 原 33 项、交互时长 2 项、自由窗口 1 项分三次 Runner 通过；R8 Release 新进程 PASS | 缺口与自由窗口仅覆盖专用模拟器场景 |
| 超大内容复核 | 修正为显式 6000px 布局宽度的同一用例在 API 24/37 各通过 | 重复复核不计新增独立用例 |
| API 21 | 云端专用模拟器 28 项必需回归通过；R8 Release 新进程 PASS | 8 项由 SDK 过滤、无逐项状态，不计成功 |
| API 30/31 | 两个版本各 31 项必需回归通过；R8 Release 新进程均 PASS | 每个版本 5 项专用 fixture 明确跳过，不计成功 |
| API 35/36 | 两个版本各 31 项必需回归通过；R8 Release 新进程均 PASS | 每个版本 5 项专用 fixture 明确跳过；不替代厂商真机 |
| 更多厂商与分屏 | 未验证 | 不将自由窗口结果推广到全部厂商和窗口模式 |

API 33 的旧验证仅对应更早稳定性阶段，不用于认证当前完整 1.1 候选。设备测试的 SDK 过滤、fixture 跳过、失败和成功必须分别记录。无障碍、显示缺口、自由窗口的专用配置及恢复要求见 [TROUBLESHOOTING.md](../TROUBLESHOOTING.md)。

当前本地不下载缺失测试镜像，不调整 AGP/compileSdk。下述历史云端关键矩阵已运行通过；本轮修改属性 setter 的 JVM synthetic 标记，产物发生变化，必须重新验证。设备项按原始 Runner、R8 日志和产物报告核对；官方支持范围风险及专用场景覆盖限制继续保留。

## 云端设备矩阵

[Candidate device matrix](../.github/workflows/device-matrix.yml) 使用 GitHub 托管 Ubuntu Runner，为 API 21、30、31、35、36 分别启动临时模拟器。镜像只在云端 Runner 下载，不占用或修改维护者本机 SDK。API 21 使用官方 default/x86 镜像，其余使用 google_apis/x86_64；官方仓库目录已确认包含这些镜像，目录存在不代表模拟器已成功运行。

构建任务沿用 JDK 17、两套现有 Gradle Wrapper 和现有依赖，先完成 JVM 测试、库 Lint、Demo/测试 APK、SNAPSHOT 发布校验及旧 AGP 消费者 Debug/R8 Release 构建。所有设备任务下载同一批 APK/AAR，校验 SHA-256 与构建提交后再安装，避免每个 API 重建或使用旧候选。Actions 使用固定提交，工作流仅有 contents: read 权限，不签名正式产物、不上传 Maven Central。

SDK 准备沿用 Action 的 cmdline-tools，显式设置 packages: platform-tools，避免默认安装已下架的旧 tools 包；编译平台与 Build Tools 由工作流后续步骤明确指定。

- API 21 必须通过 Demo、生命周期与作用域共 28 项核心回归，并在新进程运行 R8 Release 消费者。
- API 30、31、35、36 另要求首次输入法避让、显示期间输入法移动及 RTL 动态定位，共 31 项必需回归，并运行同一 R8 APK。
- 无障碍时长、显示缺口和自由窗口仍使用专用 fixture；普通矩阵未配置这些 fixture，不能替代已有专用场景或厂商真机验证。

工具将 passed、skipped、failed 和 notReported 分开记录。SDK 过滤可能不产生逐项状态，未报告项不计为成功，也不猜测为已跳过。任一必需用例未通过、Runner 中断、空运行、安装失败、API 不一致、产物变化或新进程未输出 PASS，任务均失败；失败报告仍上传。矩阵 fail-fast 关闭，一个 API 失败不会取消其他 API 的运行。

工作流支持 Pull Request、手动触发，以及 feature/device-matrix 分支推送。自动触发按代码、构建、校验工具、API 文档输入及工作流路径过滤，单独维护发布结果或 README 不重复构建设备矩阵。新工作流尚未合入默认分支时，可通过该验证分支推送触发首轮运行；首次不依赖默认分支的手动入口。创建提交、推送、创建 PR 和触发远程运行须按维护者授权分别执行。推送成功只表示代码已上传，设备结论以对应提交的 Actions 结果和产物报告为准。

Actions 保留 14 天的 device-matrix-input、构建诊断及逐 API 报告。发布评估应保存工作流 URL、提交 SHA、各 result.json、instrumentation.txt、device-logcat.txt 与 R8 日志，核对 success=true、必需用例集合、实际 API、指纹、ABI、AAR/APK SHA 与新进程 PID。模拟器启动或下载失败只表示环境验证失败，不能改为通过；14 天后会过期，发版前应归档必要证据。远程运行通过后再更新上表，不能提前勾选发布门槛。AGP/compileSdk 支持范围仍需独立决策，设备矩阵不会改变其官方支持状态。

### 历史云端结果（修复前候选）

2026-10-10 的 [矩阵运行 #3](https://github.com/logan0817/KToast/actions/runs/38038268507) 整体成功，耗时 13 分 41 秒。运行源码提交为 67f63203b74900e8f5c4c329a9ab286b7fae1530，验证分支为 feature/device-matrix。后续仅更新发布结果文档的提交不改变该运行对应的代码与产物。构建任务完成 32 项 JVM 测试、库 Lint（No issues found）、41 项 Python 工具回归、候选发布校验及旧 AGP 消费者 Debug/R8 Release 构建。

| 实际 API | 系统镜像构建标识 | ABI | 通过 | 显式跳过 | 未报告 | R8 新进程 PID |
| --- | --- | --- | --- | --- | --- | --- |
| 21 | Android 5.0.2 / LSY66K / 4174711 | x86 | 28 | 0 | 8 | 2704 |
| 30 | Android 11 / RSR1.240422.006 / 12134477 | x86_64 | 31 | 5 | 0 | 5917 |
| 31 | Android 12 / SE1A.220826.008 / 10564458 | x86_64 | 31 | 5 | 0 | 5087 |
| 35 | Android 15 / AE3A.240806.043 / 12960925 | x86_64 | 31 | 5 | 0 | 4666 |
| 36 | Android 16 / BE2A.250530.026.F3 / 13894323 | x86_64 | 31 | 5 | 0 | 5343 |

五个版本均无失败、无必需用例缺失，Runner 正常返回 -1，独立 R8 消费者新进程均输出 PASS，覆盖旧 Java、页面 Java/Kotlin、队列及延时取消。普通矩阵不启用无障碍时长、显示缺口、自由窗口 fixture；不能将这些跳过项写成已验证。API 35 的动态输入法用例同时观察宿主与独立提示子窗口，保持实际位置上移及完整避让断言，本轮未修改库定位实现。

所有设备复用以下同批产物，报告中的构建/运行提交及 SHA-256 已与下载字节核对：

| 产物 | SHA-256 |
| --- | --- |
| Release AAR（141877 字节） | 2d7cf6040949cdcf212a0fda4d58abebbe5636f0fb5d709f86419b8ac2ad79a0 |
| Demo Debug APK | 9ed54c5d0b278551673aedc838cbd12eb5976f1c2e7b324cb25245a5f3754432 |
| 设备测试 APK | d6b57a4afed5e6ae1541409b558b44c31e3149ea7f29e5eea6fe70bef61d6828 |
| 旧 AGP R8 Release 消费者 APK | 9076036ea23dbcb7776fcb237e8b1f1a0ee6f889b983f5e67246348a7b44bec4 |
| sources JAR | a0e0a7f4f796501d52966478eced8aaeb5b009ea730b9f1eb33524a60dd6603c |
| javadoc JAR | efb7dfe5395d7383a6cfc6398d18ba53da69152b70a1299b16c3e7711889c85d |

本次原始 ZIP（同批输入、构建诊断、五个设备报告）已按 Actions 展示的 digest 校验，归档在维护者本地 build/reports/device-matrix-cloud/38038268507；verified-summary.json 汇总核验结果，各 ZIP 保留完整设备指纹和原始日志。该目录受忽略规则保护，不进入源码提交；本地归档仍需维护者保留，不能依赖 Actions 14 天保留期作为永久存档。

本次 Actions 提示部分固定版本使用已弃用的 Node 20，并由托管 Runner 强制使用 Node 24；setup-java v4 也提示弃用。构建和设备任务已实际成功，这些提示不改变 Android 消费者门槛；后续维护 CI 时须单独验证 Action 迁移，不能与库生产依赖升级混为一谈。

## 文档与兼容修复终审

README 中英文与页面说明的实际代码片段使用候选 AAR 编译：33 段 Kotlin、3 段 Java、5 段 XML 校验通过。补齐缺失 import、完整 Activity 示例及仅移除本库 Startup metadata 的配置，修正无效 Android drawable 和历史 Demo 链接；说明局部 DSL 的线程、页面取消与旧全局降级边界。八份权威 Markdown 的本地链接与敏感模式检查通过，没有新增沟通过程文档。

真实 Java 示例编译发现 debugMode 属性 setter 与静态 setDebugMode(boolean) 歧义。本轮使用 @set:JvmSynthetic 对 Java 源码隐藏属性 setter，保留原 JVM 签名和 Kotlin 属性读写；独立消费者新增 Java 静态调用与 Kotlin 属性切换断言，Debug 与 R8 Release 构建均使用当前候选 AAR。旧公开类签名、major=52、metadata=1.9.0 和生产依赖校验通过，设备矩阵须针对修复后产物重新运行。

正式签名所需 KTOAST_SIGNING_KEY、KTOAST_SIGNING_PASSWORD、KTOAST_PUBLIC_KEY_FILE、KTOAST_SIGNING_FINGERPRINT 尚未注入；本轮没有生成正式签名包。私钥与口令须在维护者受控环境注入，不发送到聊天或保存到仓库；公钥可获取性、命名空间权限和 Portal 服务端校验仍须在正式发布阶段确认。

## 本地检查顺序

选择 Android Studio 项目相同的 JDK 17 和 SDK，在项目根目录执行：

```bash
./gradlew :library-android:testDebugUnitTest :library-android:lintDebug :library-android:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :library-android:publishReleasePublicationToCandidateRepository -PVERSION=1.1.0-SNAPSHOT --console=plain
python3 -B -m unittest discover -s tools/tests -v
python3 tools/verify_candidate.py
```

PATH 中的 javap 须来自现有 JDK，也可给校验脚本传 --javap。Gradle 使用共享缓存及 daemon，不需要 clean。生成本地候选后，按 [独立消费者步骤](../TROUBLESHOOTING.md#本地候选发布与真实消费者) 验证 Debug/R8 Release，并确认日志 SHA 与 candidate.json 一致；安装 R8 APK 后检查新进程 KToastConsumer 的 PASS。

在专用且允许测试安装的设备执行核心回归，再按平台能力分别执行输入法、无障碍、缺口与窗口用例。安装遇到同名签名冲突时使用独立测试包，不卸载使用者已有应用；系统拒绝安装时记录为未验证，不绕过设备策略。

## 正式发布门槛

- [ ] Java 调试入口修复后的 API 21、API 30/31、API 35/36 关键运行回归完成，记录真实设备、结果和未覆盖项。
- [x] AGP/compileSdk 支持范围完成评估；维护者选择保留 AGP 8.6/compileSdk 36，明确记录超出官方支持范围的风险。
- [ ] 最终候选的 API、metadata、依赖、源码包、R8 消费者及相关运行结果可追溯，验证报告与产物 SHA 一致。
- [x] README 中英文、页面说明、CHANGELOG、注释完成终审；过程记录、敏感信息和临时文件不进入提交。
- [x] API 文档产物、环境变量签名、本地签名仓库及 Central 手工上传包生成流程完成，测试密钥演练通过。
- [ ] 正式发布密钥、公钥可获取性和 Central 命名空间权限确认；获上传授权后执行 Portal 服务端校验。
- [ ] 发布审批后统一更新正式版本号、Demo 版本及安装说明，记录最终发布坐标和校验和。

仓库提供本地 SNAPSHOT Candidate 和本地非 SNAPSHOT Staging 仓库，包含 AAR、sources、真实 javadoc、POM 和 module metadata。Staging 必须显式开启签名。没有远程 Gradle 仓库地址或自动上传任务；本地签名包不等于正式发布完成。私钥和口令仅通过受控环境注入，不保存到仓库，也不放进命令行参数或公开构建日志。

## 签名与本地正式产物

签名默认关闭。环境须注入 KTOAST_SIGNING_KEY（ASCII-armored 私钥内容）和 KTOAST_SIGNING_PASSWORD（口令；无口令测试密钥也须显式提供空值）；给出 -PsignPublication=true 才会使用 Gradle 内存 PGP 签名。开启签名缺少环境变量时配置失败；不读取个人 GPG 密钥环。签名构建禁用 configuration cache，避免将签名配置写入磁盘缓存；签名环境不使用公开 build scan 或调试日志。

获正式版本安排后，在保持已发布版本配置不变的情况下，用命令参数准备本地 1.1.0 产物：

```bash
./gradlew :library-android:publishReleasePublicationToStagingRepository -PVERSION=1.1.0 -PsignPublication=true --no-configuration-cache --console=plain
```

输出为 build/staging-repository/io/github/logan0817/ktoast/1.1.0。五类产物各有 .asc 签名及 MD5、SHA1、SHA256、SHA512 校验文件。Candidate 只接受 SNAPSHOT；Staging 拒绝未签名或 SNAPSHOT 版本。不要执行聚合 publish 代替上述明确目标。

在独立消费者中使用 -PktoastPublication=staging -PktoastVersion=1.1.0 执行 Debug/R8 Release 构建，并对 R8 APK 运行设备冒烟，具体命令见 [消费者说明](../TROUBLESHOOTING.md#本地候选发布与真实消费者)。SHA 校验覆盖两个变体，不能只检查文件存在。完成演练后重新生成 SNAPSHOT Candidate 和对应校验报告，再继续常规候选消费。

用受控位置的导出公钥验证；KTOAST_PUBLIC_KEY_FILE 是公钥文件路径，KTOAST_SIGNING_FINGERPRINT 是独立核对过的完整主公钥或签名子密钥指纹：

```bash
python3 tools/prepare_central_bundle.py --version 1.1.0 --public-key "$KTOAST_PUBLIC_KEY_FILE" --fingerprint "$KTOAST_SIGNING_FINGERPRINT" --output build/central/ktoast-1.1.0.zip
```

该工具需要 Python 3.9+、现有 JDK 的 javap、GnuPG gpg。只在临时密钥环导入明确指定的公钥，不自动从网络取钥；核对五个签名、四类校验和、POM 必需信息、旧 API、字节码、依赖、当前源码包及真实 API 页面后才生成 ZIP 与相邻 .report.json。错误指纹、过期/撤销签名、产物篡改或缺项均拒绝；ZIP 不包含私钥、公钥、报告或 maven-metadata.xml。

本地测试密钥演练的输出位于 build/reports/release-rehearsal/ktoast-1.1.0-TEST-KEY.zip，报告 rehearsal=true，临时私钥已删除。该包禁止上传。复现测试演练可在独立临时 GPG 目录生成短期测试签名密钥，将其私钥与口令仅注入本次 Gradle 进程，再给工具添加 --rehearsal，并使用以 -TEST-KEY.zip 结尾的文件名；结束时销毁测试私钥和密钥环，不修改个人密钥环。

## Maven Central 手工审核流程

采用 [Central Portal 手工上传 ZIP](https://central.sonatype.org/publish/publish-portal-upload/) 方案；本地 ZIP 遵循 [官方产物、签名、校验和与 POM 要求](https://central.sonatype.org/publish/requirements/)。上传和发布属于外部操作，须分别获得授权。

1. 确认 Central 账号对 io.github.logan0817 命名空间有发布权限，正式公钥可按 Central 要求获取且指纹正确；公开公钥到密钥服务器也需单独授权。
2. 使用正式密钥重新生成 Staging 与 ZIP，核对报告、版本、签名指纹和待上传 ZIP 的 SHA-256；不要复用 TEST-KEY 演练包。
3. 在 Central Portal 的发布界面创建部署，上传该 ZIP，检查服务端逐项校验结果。选择人工审核发布，不启用自动发布；界面若无法将上传验证与正式发布分开，停止并按实际按钮行为重新确认授权。
4. 只有服务端校验通过、发布门槛及发布授权齐备后，才执行正式发布。仅本地校验不能认证账号权限、公钥服务端可获取性或远程验证结果。

正式版本需核对 gradle.properties 的 VERSION/APP_VERSION_NAME/APP_VERSION_CODE、gradle/libs.versions.toml 的 ktoast-version、README 中英文坐标及 CHANGELOG 状态。Candidate 任务只允许 SNAPSHOT，不能用于正式上传。版本未上线前，README 的常规安装入口继续指向已发布版本。

发布后使用全新消费者解析正式坐标，确认实际下载产物的校验和、源码与 R8 运行结果，再更新发布状态。Maven Central 已发布版本不可按本地文件覆盖；发现问题时优先停止推广，使用者回退到 1.0.5，后续以修复版本重新发布。使用新页面 API 的消费者回退前需撤回相关调用。
