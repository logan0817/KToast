# 发布指南

本页维护当前双模块发布步骤。当前正式版本为 **1.1.0**，**1.2.0-SNAPSHOT** 已实施，尚未正式签名、上传或发布；每次发布须核验最终提交的完整矩阵及合并后的 main CI。逐次结果与摘要见 [1.2.0 验收记录](releases/1.2.0.md)，历史发布见 [1.1.0 归档](releases/1.1.0.md)。

1.2.0 同批发布 `io.github.logan0817:ktoast` 与 `io.github.logan0817:ktoast-lifecycle`。扩展依赖同版核心，两者不能独立选择不匹配的版本。操作前先确认本次具体目标；提交、推送、PR、合并、Central 上传、正式发布、标签和 GitHub Release 分别按授权执行。

## 发布门槛

- 候选代码完成审查，公共 API、默认行为、依赖与兼容边界明确。
- 最终合并后的 main CI 通过；候选分支 CI 不能直接写成合并后 CI。合并改变实现或制品输入时重新验证。
- 双模块 JVM、Lint、候选产物、冻结 Java/Kotlin 字节码、独立源码消费者及 R8 通过；设备结果与同批输入 SHA 对应。
- README 中英文、使用文档、CHANGELOG、版本号和 Demo 状态一致，明确未覆盖场景。
- 正式产物使用明确选定的发布密钥，签名、公钥、POM、metadata、源码、API 文档和校验和全部验收；正式报告绑定干净提交。
- Central 上传返回的唯一 deployment ID、远端状态和实际下载字节核对完成，再执行独立的正式发布步骤。

当前工具链与厂商设备、专用 fixture、Demo Lint 等限制保留在验收记录。不能将跳过、SDK 过滤未报告、构建成功或历史成功当作本次设备通过。

## 本地候选与消费者

使用 [贡献指南](../CONTRIBUTING.md#开发环境)约定的已有 JDK、SDK、Wrapper 和缓存，不需要 `clean`：

```bash
./gradlew verifyReleaseCandidate :app:assembleDebug :app:assembleDebugAndroidTest -PVERSION=1.2.0-SNAPSHOT --console=plain
./tools/compatibility/android-consumer/gradlew -p tools/compatibility/android-consumer assembleDebug assembleRelease -PktoastVersion=1.2.0-SNAPSHOT --console=plain
./tools/compatibility/lifecycle-consumer/gradlew -p tools/compatibility/lifecycle-consumer assembleDebug assembleRelease -PktoastVersion=1.2.0-SNAPSHOT --console=plain
python3 -B tools/verify_binary_consumers.py --java-home "$JAVA_HOME" --sdk "$ANDROID_HOME" --gradle-home "$HOME/.gradle"
```

`verifyReleaseCandidate` 聚合两个模块的 JVM、库 Lint、Python 工具、候选产物及 Central 批次模型检查。消费者构建、冻结字节码和设备运行仍需独立执行。候选仓库为 `build/candidate-repository`，默认不签名且只接受 SNAPSHOT。

报告使用 `build/reports/compatibility/ktoast-candidate.json` 与 `ktoast-lifecycle-candidate.json`，不使用历史单模块 `candidate.json` 认证当前候选。验证包含 AAR、POM、Gradle metadata、sources、真实 Dokka HTML，以及正式 1.0.5/1.1.0 API 基线和精确新增清单。

发布工具或签名配置变化时，另行运行真实 Gradle 回归，不在外层 Gradle 任务内嵌套启动：

```bash
python3 -B tools/verify_signing_configuration.py -- ./gradlew
python3 -B tools/verify_central_publishing_configuration.py -- ./gradlew
python3 -B tools/verify_central_service.py -- ./gradlew
```

配置回归使用空临时密钥环，不签名、不上传。service 回归仅在隔离工程联系本机假 Portal，验证失败不上传、成功仅一次 USER_MANAGED 上传，不认证正式账号权限。

## 云端设备矩阵

[Candidate device matrix](https://github.com/logan0817/KToast/actions/workflows/device-matrix.yml) 在构建任务生成同一批 Demo、测试、核心/扩展 R8 APK 和两份 AAR，再由 API 21、30、31、35、36 的设备任务消费。手动触发时明确选择待验收分支，并核对实际运行提交。

本地等价工具入口（先完成上面的所有构建）：

```bash
python3 -B tools/run_device_matrix.py --prepare
python3 -B tools/run_device_matrix.py --api 35 --serial emulator-5554
```

设备入口仅接受显式指定的专用模拟器，会安装并运行 Demo、测试 APK 和两个消费者。必需用例未通过、空运行、Runner 中断、版本或摘要不一致、新进程未出现 PASS 均失败。原始输出与 `result.json` 在 `build/reports/device-matrix/api-35`；专用 fixture 配置见[设备排障](../TROUBLESHOOTING.md#设备回归与专用场景)。

安装后、启动 Runner 前执行启动检查：轮询启动完成与有效窗口焦点，等待期限为 60 秒，单次 adb 命令另有超时。当前焦点为 ANR 对话框或系统报告锁屏时立即失败，保存 `preflight-window.txt`、`startupPreflight` 结果及收尾诊断，不自动关闭对话框或重跑。检查通过只说明当时满足启动条件，不能保证测试期间不会出现 ANR。

矩阵启用采证自检，用真实 JUnit 规则触发可控异常，检查原异常保留、宿主销毁前生成快照，并由主机 `run-as` 导出本次私有目录中的五个文件。设备与主机验证系统服务标识。旧系统使用兼容的 `dumpsys` 命令，用例读取端五秒超时，主机收尾命令十五秒超时；采证错误保留原始失败，不重试业务断言。

停止/恢复用例使用 Debug 专用的同进程不透明 Activity 遮挡宿主，等待真实生命周期事件并核对 ActivityScenario 状态；恢复后在 finally 中移除观察器。该夹具不进入 Demo Release 或两个库，不修改、跳过或重试业务断言。

普通失败另有宿主清理后、Runner 结束后的收尾快照，这些时间点不能直接证明失焦首次发生时的原因。成功的采证自检也不证明所有偶发窗口故障已经消除。

Actions 的输入、构建报告及逐 API 报告保留 **14 天**。验收时下载七份原始 ZIP，核对 GitHub 官方 digest、提交、同批六产物 SHA、原始 Runner、必需集合、API/指纹/ABI 与两个 R8 新进程 PID，并保存运行 URL。不同轮次的部分成功不能拼成最终矩阵。

## 正式版本与本地签名

批准本次发布范围后，核对 `gradle.properties` 的 `VERSION`、`APP_VERSION_NAME`、`APP_VERSION_CODE`，同步 CHANGELOG；正式仓库确认上线前，README 不提前宣称新坐标已可安装。兼容基线独立于版本配置，不能随版本号自动覆盖。

下面命令中的 `1.2.0` 是待发布目标，执行本地准备不等于授权上传或正式发布。签名默认关闭；两种模式均要求 `--no-configuration-cache`，不使用公开 build scan 或调试日志保存签名环境。

### 本机 GPG 模式

复用现有 GnuPG 2、密钥环与 agent。在受控用户级 `~/.gradle/gradle.properties` 选择已核对的完整主公钥或签名子密钥指纹：

```properties
signing.gnupg.keyName=<40 或 64 位完整十六进制指纹>
```

也可使用环境变量 `KTOAST_SIGNING_FINGERPRINT`；同时提供时须一致。缺少指纹、短 ID、冲突值、`signing.gnupg.passphrase` 和旧版 GPG 均拒绝。路径选项沿用 `signing.gnupg.executable`、`signing.gnupg.homeDir`，口令交给 agent/pinentry，不写入 Gradle 或命令行。

公钥文件必须来自已确认的发布密钥，并独立核对指纹，不从待验签名推导“期望指纹”。设置 `KTOAST_PUBLIC_KEY_FILE` 为本机公钥文件路径，再执行本地准备：

```bash
./gradlew prepareCentralBundle -PVERSION=1.2.0 -PsignPublication=true -PsigningMode=gpg -PcentralPublicKeyFile="$KTOAST_PUBLIC_KEY_FILE" --no-configuration-cache --console=plain
```

根任务同时生成两个模块的 Staging publication，再校验 `build/central/ktoast-1.2.0.zip` 与相邻 `.report.json`。两模块共 **10 类制品、10 份签名、60 个 ZIP 文件**：AAR、sources、javadoc、POM、module 各自有签名及四种制品校验和。ZIP 不含密钥、公钥、报告或 `maven-metadata.xml`。

### 受控内存签名

仅在受控环境注入 `KTOAST_SIGNING_KEY`（ASCII-armored 私钥）和 `KTOAST_SIGNING_PASSWORD`（口令），使用相同命令，将 `-PsigningMode=gpg` 替换为 `-PsigningMode=memory`。缺少任一变量会失败，不自动切换模式或默认密钥；私钥与口令不得进入仓库或日志。

### 核对正式包

验收工具验证两个模块的当前源码、API、依赖、真实文档、签名指纹与校验和。报告必须 `rehearsal=false`、`source.dirty=false`，且提交、版本和逐文件摘要与本次包一致；源码编辑期间的预验报告不能用于上传。

用同版 Staging 运行两个消费者，分别在前述构建命令中传入 `-PktoastPublication=staging -PktoastVersion=1.2.0`。核对两变体解析的 AAR 与已验收候选关系，完成相应 R8 设备验证。SNAPSHOT 与正式版本的文档标签、POM 和 metadata 可能不同，需解释差异，不能仅凭版本号认定相同。

隔离测试密钥演练使用 `tools/prepare_central_bundle.py --artifacts ktoast ktoast-lifecycle`、`--rehearsal` 及 `-TEST-KEY.zip` 名称；测试包禁止上传。演练后恢复 SNAPSHOT Candidate，再验证候选消费者，避免 Staging 覆盖的 publication 元数据被混用。

## Maven Central 上传与人工审核

上传前确认 main CI、干净源码、正式包、公钥、命名空间权限及具体上传授权。Central User Token 的两部分通过受控用户级 Gradle 配置中的 `mavenCentralUsername` / `mavenCentralPassword` 提供；网页登录不替代 token 配置。不要把凭据作为命令行参数或打印到日志。

唯一上传入口是根任务：

```bash
./gradlew :publishToMavenCentral -PVERSION=1.2.0 -PsignPublication=true -PsigningMode=gpg -PcentralPublicKeyFile="$KTOAST_PUBLIC_KEY_FILE" --no-configuration-cache --console=plain
```

本命令会执行外部上传，仅在授权后运行。批次名为 `io.github.logan0817-KToast-1.2.0`，包含两个模块，固定 **USER_MANAGED**，不会自动正式发布。

根入口先检查环境，再生成、验收签名 ZIP，校对插件本地 staging 与冻结包的全部 60 文件并再次验签。插件为签名派生的 40 份校验和只有在全部验证后才从插件临时目录规范化移除，实际传输集合与冻结包一致。缺项、未知文件、字节差异、演练包和脏源码均拒绝。

禁止直接模块上传、通用 `publish`、自动 release/drop、跳过任务、`--continue`、上传时 `clean` 或 configuration cache。离线查看任务图同时使用 `--dry-run`；仅 `--offline` 不保证第三方插件不联网。

上传发生于 build service 收尾，等待整个进程结束。随后从官方 API/Portal 按名称、时间与两个内部坐标定位唯一 deployment ID，保存实时状态、errors 与 purls。**VALIDATED 表示服务端校验通过，不代表已发布。** 网络错误或响应丢失后先核对是否已创建批次，不直接重复上传。

## 远端验收与正式发布

按本次 deployment ID 建立目录，保存官方 `status.json`，将两个模块共 **20 个制品及签名文件**下载至 `artifacts/`，保留 Maven 文件名。校验和文件如下载也必须一致。设置 `KTOAST_DEPLOYMENT_ID` 和已核对的 `KTOAST_SIGNING_FINGERPRINT` 后：

```bash
python3 -B tools/verify_central_deployment.py \
  --bundle build/central/ktoast-1.2.0.zip \
  --bundle-report build/central/ktoast-1.2.0.report.json \
  --deployment-id "$KTOAST_DEPLOYMENT_ID" \
  --status "build/central/$KTOAST_DEPLOYMENT_ID/status.json" \
  --downloads "build/central/$KTOAST_DEPLOYMENT_ID/artifacts" \
  --public-key "$KTOAST_PUBLIC_KEY_FILE" \
  --fingerprint "$KTOAST_SIGNING_FINGERPRINT" \
  --output "build/central/$KTOAST_DEPLOYMENT_ID/verification.json"
```

该工具仅离线核对指定快照、下载字节和签名，不认证账号权限、快照来源或实时状态。插件实际 ZIP 的顺序、压缩和时间戳可能不同于本地冻结 ZIP，需另记实际上传包摘要并核对内部文件，不能混称同一个 ZIP。

远端验收通过后，再取得并执行正式发布授权。发布前重新查询同一 ID 的状态，核对坐标与验收包；不要按模糊名称操作其他批次。若组件查询报 `Deployment components info not found`，保留该诊断，通过状态与实际文件核对，不据此重复上传。

发布后确认 PUBLISHED、实际正式仓库可解析，并下载双模块的制品、签名与校验和核验。使用只从正式仓库解析的新消费者验证 Debug/R8，避免本地 Candidate 或 Staging 遮蔽正式坐标。然后同步 README 中英文、CHANGELOG、Demo 版本和逐版本记录；标签与 GitHub Release 在相应授权下创建并核对提交及资源。

## 维护入口

发布元数据集中于 `gradle.properties`；签名与 Publication 在 `gradle/release-publishing.gradle.kts`，验收在 `gradle/release-verification.gradle.kts`，Central 上传与门禁在 `gradle/central-publishing.gradle.kts`。修改插件版本、批次模型或文件集合时，先更新对应回归并重新验证双模块流程。
