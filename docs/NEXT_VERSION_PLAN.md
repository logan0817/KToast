# 下个版本方案入口

当前正式版本为 1.3.0，已完成发布与验收；本版设计见 [design/1.3.0.md](design/1.3.0.md)，结果见[发布记录](releases/1.3.0.md)。下一版确定为 **1.3.1（待发布）**，范围为系统 Toast 收尾、品牌属性隔离、Compose 交互推荐时长，以及 Demo 延时撤回和语义配色，具体变更见 [CHANGELOG](../CHANGELOG.md#131待发布)。三模块元数据统一为 1.3.1，候选为 1.3.1-SNAPSHOT，Demo versionCode 为 9。

保持现有构造、默认参数、方法和枚举兼容。新增的 `accessibilityContainsControls` 作为配置类体属性，通过 `snapshot()` 传递；已发布 API 基线与 1.3.0 增量保持不变，1.3.1 增量独立校验。设备验收需覆盖系统延长时长后的取消、Compose 交互/纯文字对照、品牌主题污染及 API 21 资源解析、Demo 连续点击和销毁。

发布前还需完成现有五档设备矩阵及八项平台专项、PR 与合并后 main 验收、干净提交的正式签名包和正式消费者验证。Central 上传、发布及公开仓库验签按后续授权执行；当前 README 保持已发布的 1.3.0 安装坐标，正式上线核验后再切换。

有限展示时长、锚点避让和 loading/原位更新不纳入本批修复，后续方向见[版本路线](ROADMAP.md#后续评估方向)。

- 样式与品牌接入：[STYLES.md](STYLES.md)。
- Compose 接入：[COMPOSE.md](COMPOSE.md)。
- 历史 1.2.0 方案：[design/1.2.0.md](design/1.2.0.md)。
- 当前文档：[导航](README.md)。
- 发布操作：[发布指南](RELEASING.md)。

保留入口兼容既有链接，历史设计不覆盖。
