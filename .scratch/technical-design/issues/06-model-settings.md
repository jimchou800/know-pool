Status: ready-for-agent

# 06: 配置生成与嵌入模型并测试连接

## 交付行为

设置页分别编辑两类模型，读取掩码状态，选择保持/替换/清除密钥并查看连接测试结果。

Blocked by: 01: 建立本机启动与接口契约验证闭环

执行状态：进行中

前端负责人：`/root/frontend06`，`.worktrees/frontend06`，分支 `implement/06-frontend`

后端负责人：`/root/backend06`，`.worktrees/backend06`，分支 `implement/06-backend`

共享文件写入者：总协调在 `.worktrees/integration06` 维护契约、范围、工具、POM、依赖锁及共享文档；双方独立源码工作树按角色隔离。

## 验收条件

- [ ] 明文只在后端数据目录，无环境变量兜底；客户端缓存和在途配置一致，错误与日志脱敏。自动测试用替身，真实调用须单独授权，指定生成模型未核实不得擅自替换。
- [ ] 界面、接口、业务与持久化端到端联通，成功、失败和边界场景有固定样例验证。
- [ ] 本票全部操作、字段、类型与约束按唯一接口契约 100% 实现，变更前后端分别确认同一契约摘要。
- [ ] 自动生成机器与中文契约报告，绑定实现快照，前后端独立确认；全项目未完成范围单列。
- [ ] 统一格式、静态、测试和构建检查通过，前后端行及分支覆盖率分别至少 80%，总协调核验端到端证据。

## 领域工作包

- 前端代理：实现本票交付行为对应的页面、交互、请求消费和状态展示，提供实际请求测试与界面证据，独立审阅契约报告。
- 后端代理：实现本票交付行为对应的业务、存储、接口、安全与失败处理，提供真实接口和序列化测试证据，独立审阅契约报告。
- 总协调：确认阻塞已解除，派发两方角色工作包，指定共享文件写入者，集成并验收，不代签。

## 契约与证据

操作范围：`getGenerationModel`、`updateGenerationModel`、`testGenerationModel`、`getEmbeddingModel`、`updateEmbeddingModel`、`testEmbeddingModel`、`listEmbeddingModelVersions`、`getEmbeddingModelVersion`、`updateEmbeddingVersionCredential`、`getConnectionTestResult`、`getVectorConfig`、`updateVectorConfig`、`testVectorConfig`、`getTask`。契约 `1.0.2`，摘要 `16c66a6a591eb5e9b32e85d6adcb16f27429cc1b916bb4a0b42bed475c278879`，详见 06 实施协调记录。

验收证据：待实现后记录命令、结果、报告摘要、双方确认和端到端证据。

## 讨论记录

- 用户回复“确认”，批准 24 个切片的粒度与阻塞关系，据此发布本地任务；尚未开始实现。

- 2026-11-13：01 至 05 已验收，06 已经双方完成接口技术互审并确认同一摘要；在独立 06 集成分支采纳 1.0.2，正式派发两方独立实现，受控替身验证，不调用真实模型。
