# 构建、兼容与排障

## 保持现有工具链

项目当前使用 AGP 8.6.0、Kotlin 1.9.23、Gradle 8.14.3。构建继续使用现有 JDK 17；库模块的 Java/Kotlin 字节码目标为 1.8，Demo 和 buildSrc 仍为 17。请在 Android Studio 的 Gradle JDK 设置中选择与命令行相同的 JDK。

构建 JDK 与发布字节码目标是两个独立设置。库使用 JavaVersion.VERSION_1_8 和 Kotlin JVM_1_8，减少旧 D8/R8 对高版本类文件的处理限制，并不要求贡献者安装 JDK 8，也不改变 minSdk 或 Kotlin 依赖。Android 最终运行 DEX；Java 标准库 API 是否可用仍由 Android API 版本和 desugaring 决定，不能仅凭字节码目标推断。Kotlin metadata 保持 1.9，旧 Kotlin 编译器或旧 AGP 内置 Lint 的解析限制也不会因此消失。

Gradle 8.14.3 不支持运行在 JDK 25 上；发现此问题时先检查 IDE 与命令行所选 JDK，不通过盲目升级 Gradle、AGP 或 Kotlin 来解决。[AGP 8.6 官方兼容说明](https://developer.android.com/build/releases/past-releases/agp-8-6-0-release-notes) 列出的最高 API 为 35，构建 JDK 为 17；仓库 compileSdk 36 超出其官方支持范围，现有压制提示不代表完整认证。工具链支持范围属于发布前评估项，调整须先评估消费者影响，状态见 [发布检查表](./docs/RELEASING.md)。

```bash
java -version
./gradlew :library-android:testDebugUnitTest :library-android:lintDebug :library-android:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

不需要先执行 clean，也不要删除共享 Gradle 缓存。具体失败先分析首个有效错误。Android Studio 共享环境脚本可用于本地运行同一组任务，避免创建独立缓存。

若系统浏览器能下载依赖，而 Java 请求出现 Remote host terminated the handshake，检查系统已有代理是否被 Gradle 使用。可为单次命令传入 `-Dhttp.proxyHost`、`-Dhttp.proxyPort`、`-Dhttps.proxyHost` 和 `-Dhttps.proxyPort`，值必须来自本机现有代理；不要把个人代理写进仓库，也不要关闭 TLS 校验或换成不可信仓库。

## 公开 API 检查

基线来自 Maven Central 发布的 1.0.5 AAR。Release 构建完成后提取 classes.jar，在已有 Python 3.9+ 和 JDK 17 下检查：

```bash
python3 - <<'PY'
from zipfile import ZipFile
from pathlib import Path
artifact = Path('library-android/build/outputs/aar/library-android-release.aar')
output = artifact.parent / 'ktoast-api-check-classes.jar'
output.write_bytes(ZipFile(artifact).read('classes.jar'))
print(output)
PY
python3 tools/check_public_api.py --classes library-android/build/outputs/aar/ktoast-api-check-classes.jar
```

脚本比较公开类、构造及方法的 JVM 描述符和泛型声明，排除 Kotlin internal 和编译器访问桥接；检查通过仍需执行消费者源码、已编译调用、Kotlin metadata、R8 与设备验证。

## 设备回归

`KToastLifecycleTest` 覆盖入队前工作线程取消、全局取消保留终态、内容构建降级、Activity 重建、View 脱离、立即取消后的内容复用和回调中新请求。`KToastScopeTest` 覆盖页面停止和恢复、关闭、跨作用域隔离、工作线程准备期间的生命周期变化、窗口内容约束、主题和诊断异常。连接专用测试设备后执行：

```bash
./gradlew :app:connectedDebugAndroidTest --console=plain
```

测试会安装并启动 Demo，设备需解锁且允许前台 Activity 显示。构建成功不等于设备测试通过；各 API 的结果应单独记录。

Demo 的 Insets 在根布局处理底部、侧边系统栏及缺口，在 Toolbar 保留顶部安全区域。滚动视口止于导航栏上方，重复分发 Insets 不累加 padding。出现 Espresso 的 INJECT_EVENTS 异常时先检查日志中的实际点击目标；若为 NavigationBar，应检查视口与点击坐标，不给应用增加系统注入权限。Demo 点击回归同时检查视口安全边界并执行真实点击。

缺少本地镜像时可使用 [GitHub 云端矩阵](./docs/RELEASING.md#云端设备矩阵)，覆盖 API 21、30、31、35、36。工作流只在托管 Runner 准备镜像，保持项目版本与消费者门槛不变；实际通过状态以发布检查表和对应提交的运行报告为准。普通矩阵不启用下述无障碍、缺口与自由窗口 fixture。

`tools/run_device_matrix.py --prepare` 在候选发布校验与 Demo/测试/消费者 APK 构建后记录输入校验和。设备模式必须显式传入 --api 与 --serial，只接受专用 emulator 设备；会安装 Demo、测试 APK 和独立消费者，执行 Runner 并检查新进程 R8 PASS，不清空 logcat、不修改日常真机。结果写入 build/reports/device-matrix/api-版本/result.json，原始 Runner 与 logcat 一并保留。必需用例跳过或缺失不会通过；未报告项和显式跳过分开记录。工具的解析回归随 tools/tests 一起执行，无需 Android 镜像。

`KToastPlatformTest` 的输入法测试只在 API 30+ 执行，无障碍推荐时长测试只在 API 29+ 执行。后者需要专用测试模拟器把 accessibility_non_interactive_ui_timeout_ms 设为 10000，并给 AndroidJUnitRunner 传入 `-e accessibilityTimeoutFixture true`；缺少该参数时明确跳过，不能计为通过。不要修改使用者日常设备的无障碍设置。测试完成后移除专用模拟器设置或销毁该模拟器。

自定义交互控件的两项时长回归使用另一套独立配置：专用模拟器 accessibility_interactive_ui_timeout_ms=10000，accessibility_non_interactive_ui_timeout_ms 保持默认，Runner 参数为 `-e interactiveTimeoutFixture true`。分别运行 customButtonUsesInteractiveRecommendedTimeout_withoutBubbleCancel 与 customPlainTextDoesNotUseInteractiveTimeout，验证按钮采用交互时长、可执行无障碍点击，而普通自定义文字按普通时长结束。测试会先断言平台对文字推荐 2000ms、对控件至少推荐 5000ms，防止全局延长文字掩盖控件判断问题；不要与上一项的非交互 10000ms 配置混用。记录原设置后再配置，结束时恢复原值。

`KToastWindowTest` 在 API 30+ 验证显示期间 START 随宿主 RTL/LTR 切换定位，以及底部提示在输入法出现后移动到键盘上方。输入法场景必须显式使用底部 Gravity，不能沿用 Demo 的全局居中样式后仅断言“未重叠”，否则可能没有真正验证避让逻辑。宿主与提示子窗口分别观察绘制，避免只在宿主绘制前读取尚未更新的子窗口位置；失败信息包含可见键盘、实际屏幕位置与请求定位参数。界面变化以实际窗口位置为准，不能把初次显示位置正确等同于动态适配正确。

显示缺口测试需给 Runner 传入 `-e displayCutoutFixture true`，并在专用模拟器上启用非零顶部缺口；测试会检查真实 DisplayCutout，再验证极大负偏移也不会让整个内容越过安全区域。没有该参数时明确跳过。可先用 `adb -s <专用设备> shell cmd overlay list` 确认系统已有的 display.cutout.emulation.tall，再用 cmd overlay enable/disable 配置和恢复；不要在日常设备上修改显示设置，也不要把未启用缺口的普通屏幕结果计为此项通过。

`KToastMultiWindowTest` 需要 API 30+，仅在支持自由窗口的专用模拟器启用 `-e multiWindowFixture true`，没有该参数时明确跳过。测试检查实际进入多窗口、宿主边界小于整屏且起点非零，再验证超大自定义内容的整体尺寸、安全坐标和任务调整大小后的作用域行为；不能用全屏测试替代。

专用模拟器可先记录 `settings get global enable_freeform_support` 与 `settings get global force_resizable_activities` 的原值，再分别用 settings put global 将两项设为 1。测试使用 am start 的 --windowingMode 5 与 am task resize，并在清理阶段恢复 --windowingMode 1；结束后还须恢复原开发者设置。原值为 null 时使用 settings delete global 删除对应项，原值非 null 时写回原值。不要在日常设备上强制改变窗口设置。不同平台可能不支持这些 shell 参数，必须以实际进入模式和改变尺寸的断言为准。

## 提示未显示或队列未推进

开启 `KToast.setDebugMode(true)` 查看窗口挂载、内容工厂、渲染和业务回调异常。调度层隔离业务回调异常并保证内部收尾，日志只用于诊断，不应包含敏感业务消息。

1.0.5 的 debugMode 属性 setter 与静态 setDebugMode(boolean) 在 JVM 中同名，Java 源码直接调用可能出现歧义。1.1 候选将属性 setter 标记为 JVM synthetic，保留原签名和 Kotlin 属性访问，Java 使用静态入口即可；独立 Java/R8 消费者同时验证开关的实际启用和关闭。

系统降级只能尝试显示：后台限流、通知状态和 ROM 策略可能导致系统 Toast 不可见。当前 onShow 表示渲染调用返回，系统 COMPLETED 表示本地预计时间到达，不代表真实送达。需要用户操作的前台消息应评估 Snackbar，后台重要消息应使用应用自己的通知方案。

`ToastHandle.cancel()` 返回 true 表示接受首次取消请求；工作线程调用的实际清理与回调在主线程执行。终态后再取消返回 false。已显示的自定义提示随宿主停止、销毁或 View 脱离取消；全局未显示请求仍按原策略推进。页面独有消息可使用 [KToastScope](./docs/PAGE_SCOPE.md)，或由旧全局入口配合 groupKey/cancelGroup 清理。全局配置请在初始化或主线程统一设置，不支持一边跨线程修改可变配置、一边并发提交请求。

后续功能路线见 [docs/ROADMAP.md](./docs/ROADMAP.md)，兼容验证矩阵与发布门槛见 [docs/RELEASING.md](./docs/RELEASING.md)。

## 已编译消费者样例

`tools/compatibility/JavaConsumer.java` 与 `KotlinConsumer.kt` 用于检查旧构造、默认参数、copy、析构和静态入口。先使用发布版 1.0.5 编译这两个样例，然后保持样例字节码不变，把运行时库替换成候选 classes.jar 执行。需要既有 JDK/Kotlin 编译器、对应 stdlib 与 Android SDK 编译桩；不能在正常 JVM 中执行真正的窗口操作。

该样例验证链接与部分纯配置行为，不等于消费者完整 APK 构建，也不涵盖混淆或 Kotlin metadata 的全部兼容性。

## 本地候选发布与真实消费者

使用 Gradle 内置 maven-publish 生成本地 SNAPSHOT，不需要额外发布插件：

```bash
./gradlew :library-android:publishReleasePublicationToCandidateRepository -PVERSION=1.1.0-SNAPSHOT --console=plain
python3 tools/verify_candidate.py
```

Candidate 发布任务仅接受 SNAPSHOT，仓库位于 build/candidate-repository，默认不签名。正式 VERSION 和安装坐标仍为 1.0.5。verify_candidate.py 检查 minSdk、AAR 元数据、全部类文件版本、旧公开 API、关键 Kotlin metadata、发布坐标与 aar 包装，以及 POM 依赖 scope。module 的 API、运行时与源码变体分别检查依赖、必需产物、文件名、大小和校验和，避免依赖合并掩盖运行时缺项；源码包中的 Kotlin 文件还须与当前源码逐文件一致。javadoc 包由 Dokka 1.9.20 生成，检查 HTML 首页与关键公开页面，拒绝空包和 internal 调度实现暴露。报告写入 build/reports/compatibility/candidate.json。PATH 中的 javap 需可用，也可用 --javap 指定现有 JDK 工具；--sources、--javadoc 可指定待检查源码和文档包。不同候选版本须显式指定对应 javadoc 文件。

单独生成文档可执行 :library-android:apiDocsJar，HTML 首页在 library-android/build/docs/api/index.html。Dokka 仅为构建插件，不增加库生产依赖。需要签名发布演练时使用独立本地 Staging 任务，签名方式、Central ZIP 生成和正式审核流程见 [发布检查表](./docs/RELEASING.md#签名与本地正式产物)。本地 Staging 构建会覆盖 build/publications/release 中的版本元数据；验证 SNAPSHOT 消费者前必须重新生成 Candidate，不能将两种版本报告混用。

发布校验工具的回归测试使用临时目录中的正确及损坏样本，不改动实际候选。verify_central_deployment.py 使用指定的 Portal 状态快照和下载目录，核对批次 ID、项目名、坐标、正式 ZIP 字节及签名；错误状态、旧下载和缺项不能通过。它不读取上传凭据或查询网络，不能把样例测试写成真实上传验收。命令与报告格式见[发布检查表](./docs/RELEASING.md#maven-central-手工审核流程)。

运行工具回归：

```bash
python3 -B -m unittest discover -s tools/tests -v
```

`tools/compatibility/android-consumer` 是独立消费者工程，使用 AGP 7.2.2、Gradle 7.4.2、Kotlin 1.9.22、compileSdk 31、minSdk 21 和 JVM 1.8，仅依赖本地候选坐标。工程包含完整 Gradle Wrapper，按下方命令构建 Debug 与 R8 Release；共享环境脚本运行该独立工程时，应显式指定现有 JDK 17 与 Android SDK，避免默认选到不兼容的 IDE JBR。preBuild 校验消费者实际解析的 AAR 与当前候选 SHA 一致，防止测试到旧 SNAPSHOT。

先完成上方本地候选生成。在同一个终端使用已选择的 JDK 17，并让 ANDROID_HOME 指向已有 Android SDK；从项目根目录执行：

```bash
(
    cd tools/compatibility/android-consumer
    ./gradlew assembleDebug assembleRelease --console=plain
)
```

Windows 使用 gradlew.bat 执行同一组任务。Wrapper JAR 和脚本复用根项目已使用的官方启动器，消费者自己的 wrapper 配置固定选择 Gradle 7.4.2，不使用根项目的 Gradle 8.14.3。首次运行若共享缓存缺少该发行包，需要可用网络；不切换到任意系统 Gradle。

默认解析 Candidate 的 1.1.0-SNAPSHOT；签名演练后，可在消费者目录执行下列命令解析本地 Staging 的 1.1.0：

```bash
./gradlew assembleDebug assembleRelease -PktoastPublication=staging -PktoastVersion=1.1.0 --console=plain
```

ktoastPublication 只接受 candidate 或 staging，均对应仓库内 build 的本地目录；ktoastVersion 指定明确版本。preBuild 分别核对 Debug 与 Release 实际解析 AAR 的 SHA，须与根项目当前 AAR 一致。正式演练包与 SNAPSHOT 版本不可混用，库重建后需重跑对应消费者构建。

Release 开启 R8 并使用调试签名，仅供本地验证。安装后启动 com.logan.ktoast.compatibility/.ConsumerActivity，界面及 KToastConsumer 日志应出现 PASS，验证旧 Java 入口、新页面 Java/Kotlin、排队和延时取消。构建通过仍需设备冒烟；该工程不作为商店发布应用。

AGP 7.2.2 的实际 R8 构建对原 JVM 17 库字节码发出不正式支持警告，库降为 JVM 1.8 后该警告消失。不过该 AGP 内置 Lint 仍输出 Kotlin metadata 1.9 与其分析器期望 1.6 不一致的诊断。即使构建最终成功，也只能记录该组合的实测构建与冒烟结果，不能承诺其完整 Lint 分析能力或任意旧 AGP 支持。

## 设备上已有同名应用但签名不同

若安装报 INSTALL_FAILED_UPDATE_INCOMPATIBLE，不要直接卸载使用方的已有 Demo。可通过临时 Gradle init script 设置独立 applicationId/testApplicationId 构建验证包，库 namespace 和业务代码保持原值；测试结束只清理本次创建的独立包。

如果 connected 测试因额外测试平台组件下载受限，可在 APK 构建成功后，使用 adb 安装独立 Demo 与测试 APK，再运行对应测试包的 AndroidJUnitRunner。系统安装确认、设备锁屏或 USB 安装策略需要先处理，不能把“APK 编译成功”报告为“设备测试通过”。
