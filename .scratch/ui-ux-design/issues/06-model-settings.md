Status: ready-for-agent

# 06: 显式配置模型与向量连接

## 关联与执行状态

关联原切片：[技术任务 06：配置生成与嵌入模型并测试连接](../../technical-design/issues/06-model-settings.md)。

本票是原切片的前端工作包，不另行派发重复实现。原技术任务是端到端完成状态的唯一依据，前端完成不代表整个切片完成。

Blocked by: [技术任务 01：建立本机启动与接口契约验证闭环](../../technical-design/issues/01-local-contract.md)

实现领取以关联前置已提交并集成为准；最终验收仍需统一验证和独立签认，分流状态不能代替完成状态。同票后端与前端共同推进，不将其完成设为启动前置。

执行状态：已完成

前端负责人：`/root/frontend06`，`.worktrees/frontend06`，分支 `implement/06-frontend`

后端负责人：`/root/backend06`，`.worktrees/backend06`，分支 `implement/06-backend`

共享文件写入者：总协调在 `.worktrees/integration06` 维护契约、范围、工具、POM、依赖锁及共享文档；双方独立源码工作树按角色隔离。

## 交付行为

用户分别保存生成与嵌入模型配置、选择已保存嵌入版本、维护向量连接并显式测试连接。

## 验收条件

- [x] 读取只展示掩码和状态，保持、替换、清除分别构造。
- [x] 掩码不能当密钥回传，真实密钥不进入浏览器持久化、日志或截图。
- [x] 指定历史嵌入版本可单独换密钥，不能误提示必须重建。
- [x] 连接测试说明费用且不发送个人资料。
- [x] 缺配置不假设环境变量兜底。
- [x] 关联原切片真实后端与本票界面联通，成功、失败及边界流程完成验收，不能仅凭模拟样例关闭。
- [x] 本票全部操作及约束对齐率 100%，自动报告绑定实现快照与契约摘要，前后端独立验收同一报告，全项目缺项单列。
- [x] 工程检查、行与分支覆盖率、浏览器交互和截图满足下述共同规则，总协调完成集成核验。

## 领域工作包

- 前端：落实上述行为，提供实际请求构造、响应消费、关键状态测试及截图，独立审阅同一实现的契约报告。
- 后端：原技术 06 管理配置版本、独立凭据更新及异步测试；用模型替身验证配置往返、秘密不回显、失败及测试结果。
- 总协调：在关联原切片记录双方领取和共享文件单写入边界，避免重复派发；核对前置验收、集成证据及双方独立确认，不代签。

## 契约与证据

契约版本：`1.0.2`。

契约 SHA-256：`16c66a6a591eb5e9b32e85d6adcb16f27429cc1b916bb4a0b42bed475c278879`。

操作范围：`getGenerationModel`、`updateGenerationModel`、`testGenerationModel`、`getEmbeddingModel`、`updateEmbeddingModel`、`testEmbeddingModel`、`listEmbeddingModelVersions`、`getEmbeddingModelVersion`、`updateEmbeddingVersionCredential`、`getConnectionTestResult`、`getVectorConfig`、`updateVectorConfig`、`testVectorConfig`、`getTask`。契约 `1.0.2`，摘要 `16c66a6a591eb5e9b32e85d6adcb16f27429cc1b916bb4a0b42bed475c278879`，详见 06 实施协调记录。

字段、类型、媒体、安全、事件及错误以唯一正式契约为准；领取前复核摘要与双方设计签认，字节变更先重新校验与双审，不自行猜测。

验收证据：见 [06 最终实施记录](../../technical-design/implementation-06.md)。无跳过根验证、真实浏览器、同快照自动报告及双方独立签认通过，证据不包含真实密钥或个人资料。

## 共同实施与验收规则

共用[界面规格](../spec.md)、[协作流程](../../../docs/agents/collaboration.md)、[工程规范](../../../docs/agents/engineering.md)和[接口规范](../../../docs/agents/api/README.md)，本票仅列独特范围及证据。按根代理约定，前置实现齐备可继续编码；多票统一在根工作区 `integration` 合并验证，已有有效证据覆盖的要求不重复运行，运行验收通过后才标记完成。

## 讨论记录

- 2026-11-12：用户回复“确认”，批准 24 个前端工作包的粒度、阻塞及划分，按依赖顺序发布本地文件。仅发布，未领取或开始实现；原技术任务及接口未修改。

- 2026-11-13：01 至 05 已验收，06 已经双方完成接口技术互审并确认同一摘要；在独立 06 集成分支采纳 1.0.2，正式派发两方独立实现，受控替身验证，不调用真实模型。

## 最终验收

2026-11-13，已随技术 06 完成端到端验收，见 [06 最终实施记录](../../technical-design/implementation-06.md)。623 项前端测试、三宽真实浏览器、契约报告及双方独立签认通过；不代表全项目实现或真实模型评测完成。
