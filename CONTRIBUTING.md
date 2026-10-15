# 贡献指南

欢迎提交可复现的问题、文档改进和聚焦的修复。使用库请先阅读 [README](README.md)；构建、测试与发布工具的问题见 [排障指南](TROUBLESHOOTING.md)。

## 项目结构

| 目录 | 职责 |
| --- | --- |
| `library-android/` | 核心公开 API、调度、窗口和样式 |
| `library-lifecycle/` | 可选 LifecycleOwner 扩展 |
| `app/` | Demo 与 Android 设备回归 |
| `tools/` | API、候选、发布、消费者和设备证据校验 |
| `tools/compatibility/` | 独立消费者与冻结字节码样例 |
| `gradle/` | 发布、签名与验收 Gradle 脚本 |
| `docs/` | 使用、维护、设计和逐版本证据 |

Gradle 内部模块名与公开坐标不同：`library-android` 发布为 `ktoast`，`library-lifecycle` 发布为 `ktoast-lifecycle`。不要仅为统一名称移动模块或改变包名、API、坐标。

## 开发环境

- JDK 17、仓库 Gradle Wrapper 8.14.3、AGP 8.6.0、Kotlin 1.9.23。
- 已有 Android SDK：platforms 36 与 31，Build Tools 34.0.0 与 30.0.3；后两项中的旧版本用于独立消费者。
- Python 3.9+ 用于校验工具；签名或验签才需要 GnuPG 2，常规候选不需要密钥或 Central 凭据。
- Android Studio 与终端使用同一 JDK、SDK 和 Gradle 缓存；SDK 路径放在本地 `local.properties` 或现有 `ANDROID_HOME` 中，不提交个人路径。

AGP 8.6 官方最高支持 API 35，仓库 compileSdk 36 超出该范围；现有构建与设备验证不等于官方支持认证。保留当前配置的边界见 [验收记录](docs/releases/1.2.0.md)。不要为绕过错误随意升级工具链或删除缓存。

以下命令从项目根执行。Windows 使用对应 `gradlew.bat`；独立消费者须使用其自己的 Wrapper。

## 构建本地候选与消费者

```bash
./gradlew verifyReleaseCandidate :app:assembleDebug :app:assembleDebugAndroidTest -PVERSION=1.2.0-SNAPSHOT --console=plain
```

根候选任务检查两个模块的 JVM 测试、库 Lint、Python 工具与发布模型，生成 AAR、POM、Gradle metadata、sources、Dokka 文档及对应校验报告。不签名、不上传，也不执行消费者设备运行。普通任务不需要先 `clean`。

本地 Maven 仓库位于 `build/candidate-repository`。要在另一个应用试用候选，在其仓库中加入此目录的本机绝对路径（仅放在本地配置中），使用：

```kotlin
dependencies {
    implementation("io.github.logan0817:ktoast:1.2.0-SNAPSHOT")
    // 需要 LifecycleOwner 作用域时使用这一项；它会传递依赖同版核心。
    // implementation("io.github.logan0817:ktoast-lifecycle:1.2.0-SNAPSHOT")
}
```

两个独立消费者分别验证核心与扩展：

```bash
./tools/compatibility/android-consumer/gradlew -p tools/compatibility/android-consumer assembleDebug assembleRelease -PktoastVersion=1.2.0-SNAPSHOT --console=plain
./tools/compatibility/lifecycle-consumer/gradlew -p tools/compatibility/lifecycle-consumer assembleDebug assembleRelease -PktoastVersion=1.2.0-SNAPSHOT --console=plain
```

消费者固定 AGP 7.2.2、Gradle 7.4.2、Kotlin 1.9.22、compileSdk 31、JVM 1.8，使用同一 JDK 17、SDK 和共享缓存。构建会核对实际解析 AAR 的 SHA，防止验证到旧 SNAPSHOT。该组合内置 Lint 的 Kotlin metadata 提示仍存在，不能推广为任意旧工具链兼容。

## 选择验证范围

| 改动 | 必要验证 |
| --- | --- |
| 普通 Markdown | 相对链接、锚点、代码块、版本与实际接口一致性 |
| Dokka 模块说明或 KDoc | 重新生成相关 API 文档与候选，检查 HTML 和文档包 |
| 核心调度或取消 | 核心 JVM、相关设备回归、默认行为与公共 API |
| LifecycleOwner 绑定 | 扩展 JVM、停止恢复与 View 重建设备回归、独立消费者 |
| 公共 API、依赖或发布配置 | 双模块候选、正式 API 基线、冻结消费者、源码消费者及 R8；发布工具变更另跑对应配置回归 |
| Python 工具 | `python3 -B -m unittest discover -s tools/tests -v` |

已编译旧消费者的入口：

```bash
python3 -B tools/verify_binary_consumers.py --java-home "$JAVA_HOME" --sdk "$ANDROID_HOME" --gradle-home "$HOME/.gradle"
```

`JAVA_HOME` 需指向上述 JDK 17，`ANDROID_HOME` 指向已有 SDK。工具核验正式 1.0.5/1.1.0 AAR，编译后冻结 Java/Kotlin 样例字节码，再替换候选运行；不执行真正的 Android 窗口。源码和冻结消费者不能互相替代。

设备运行与专用 fixture 见[排障指南](TROUBLESHOOTING.md#设备回归与专用场景)，云端五版本流程见[发布指南](docs/RELEASING.md#云端设备矩阵)。每个结果需关联实际提交、设备与产物，区分通过、失败、跳过和未报告。

## 报告问题

在 [GitHub Issues](https://github.com/logan0817/KToast/issues) 提供：

- KToast 版本、入口（全局 / Activity / LifecycleOwner）和最小复现代码。
- Android API、设备或模拟器、前后台与页面生命周期状态。
- 预期与实际结果，以及脱敏后的首个有效错误、堆栈或日志。
- 构建问题还需 JDK、Gradle、AGP、Kotlin 和依赖解析信息。

不要提交密钥、Token、证书、账号资料、业务消息正文或完整个人环境文件。涉及敏感内容时，先整理不含敏感数据的最小复现，不把机密信息贴进公开 Issue。

## 提交与 PR

保持一个修改解决一个明确问题，按既有架构和命名实现；不要混入依赖升级、无关格式化或重构。公开接口变化同步调用方、测试及使用文档，并保护已发布构造、默认参数、方法和枚举。API 基线修改应说明兼容理由，不以重新扫描覆盖基线代替审查。

提交标题采用 `feat(scope): 中文说明`、`fix(scope): 中文说明`、`docs: 中文说明` 等类型格式；正文简述实际变更和验证。PR 使用中文说明背景、行为、影响、测试及已知限制，未执行的验证应明确标出。新增功能通常需要对应 CHANGELOG；纯内部或文档修正按实际影响更新。

普通修复不改发布版本号，不提交构建报告、临时脚本、签名包、个人配置或密钥。上传和正式发布按[维护者发布流程](docs/RELEASING.md)单独安排。

## 文档与许可证

文档结构与命名约定见[文档导航](docs/README.md#文档维护约定)。中英文首页和核心示例需保持一致；完整许可证只维护根 [LICENSE](LICENSE)。提交贡献须确保有权提供相应代码、文档和资源，并接受项目的 MIT 许可证。
