# 06 实施协调记录

执行状态：进行中。前置 01 已验收，01 至 05 均已完成端到端交付。为保留主树 05 已验收快照，06 在独立集成分支 `implement/06-integration`、工作树 `.worktrees/integration06` 推进，最终验收后再合主 `integration`。不拉取、不推送、不调用真实模型或收费接口。

## 分工与唯一契约

后端 `/root/backend06` 加载 `.codex/agents/backend.md`，独占 `.worktrees/backend06` 的 `server/`（不含 POM）；前端 `/root/frontend06` 加载 `.codex/agents/fornt-agent.md`，独占 `.worktrees/frontend06` 的 `front/`（不含 POM）。各自从 06 集成基线独立分支实施，本地提交前合并该集成分支的最新本地代码，不操作远程。

总协调独占唯一契约、适用映射、报告工具、POM、锁文件与共享文档，负责集成及最终门禁；涉及锁文件或新依赖先提出具体需要。双方仅写自己的独立范围及报告签认，协调不代签。

唯一契约 `1.0.2`，SHA-256 `16c66a6a591eb5e9b32e85d6adcb16f27429cc1b916bb4a0b42bed475c278879`。设计代理 `/root/backend05` 与 `/root/frontend06_design` 已对相同字节分别确认，正式保存于 `docs/agents/api/reviews/contract-06-backend.md`、`contract-06-frontend.md`。标准校验、95 操作、999 引用、11 内置样例、27 通用边界样例及十项草案针对性断言通过，不能据此声称实现通过。

本票十四操作：`getGenerationModel`、`updateGenerationModel`、`testGenerationModel`、`getEmbeddingModel`、`updateEmbeddingModel`、`testEmbeddingModel`、`listEmbeddingModelVersions`、`getEmbeddingModelVersion`、`updateEmbeddingVersionCredential`、`getConnectionTestResult`、`getVectorConfig`、`updateVectorConfig`、`testVectorConfig`、`getTask`。累积回归已验收的十三操作，不删原始九十五操作或任何约束分母；08 后续继承通用任务查询，不重复发明查询接口。

## 已确认技术边界

- 初次未配置也持久生成两模型独立版本与时间，显式空值和未设状态，无环境变量或默认模型兜底；向量同样返回完整未设状态。
- 生成非凭据配置变化产生新版本，嵌入地址或模型变化形成独立身份，纯凭据不换版本，相同保存不造版本。历史嵌入凭据独立轮换，在途任务固定完整配置和凭据快照。
- 同源新版本保持凭据为服务器内部独立绑定；协议、规范主机、有效端口跨源且已有凭据时，保持动作原子 400，字段 `/credential/action`，显式替换或清除可保存。掩码固定为契约保留标记，不能作为密钥回传；历史根级凭据输入错误指针为 `/value`。
- 显式兼容基础地址和原样模型标识；仅空白模型拒绝，不擅自替换用户模型。生成及嵌入用固定非个人负载，向量只读集合探测，无隐式重试、重定向或外发个人资料。受控本机替身验证路径、认证隔离、超时及非法响应，真实兼容性另行授权。
- 探测不可用仍是任务成功完成且结果不可用，本机执行故障为任务失败；结果未生成、失败或中断读取 409，错误种类或不存在 404，成功但结果缺失 500。重启中断未终态，不自动再外发；同幂等键返回原任务。失败或中断可能已经产生费用，不能自动重测。
- 任意成功保存使前端当前测试有效标记失效，旧版本标识相同不代表新凭据已验证；页面保存世代与任务身份隔离迟到结果，秘密只留短期表单内存，失败或离开清理，不进入浏览器持久化、日志或截图。

## 完成门槛

先独立审查本票全部实际约束并双签范围映射，再扩展提供方、消费方及真实浏览器证据。使用公开行为红绿测试，前后端行、分支覆盖率分别至少 80%。最终在 06 集成快照完整运行无跳过根验证、受控替身业务流程及三宽截图，生成不可变报告，两个实际领域代理独立签认后协调验收。模型名或真实服务未核实不阻断受控替身开发，也不能宣称真实模型可用。
