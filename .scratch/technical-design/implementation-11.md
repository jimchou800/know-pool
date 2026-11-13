# 11 实施记录

执行状态：已实现，待统一验证。08前置已验收；按依赖编号在09、10实现齐备后进入11，运行验收留统一阶段。

后端 `/root/backend09` 继续单写 `server/` 及所需迁移，前端 `/root/frontend09` 继续单写 `front/`（依赖与锁除外），复用当前两棵活跃任务树。总协调负责正式契约、适用范围、共享工具及任务状态。双方须覆盖来源并集、排除优先、最后来源失效、取消排除仅依据有效来源、自动同步及迟到任务不得复活；重复来源不重复嵌入。

现行契约1.0.5，摘要 `2eba1980d9e62318edfece692e46c977d9e55191f06c59e4629285f29f66f5fd`。预计新增已有操作 `removeKnowledgeBaseSource`、`listKnowledgeBaseExclusions`、`excludeKnowledgeBaseFile`、`includeKnowledgeBaseFile`、`listFileKnowledgeBases`，累计48操作；来源建立、来源及文件列表复用。双方核实技术缺项后再变更契约，不改变已确认业务规则。范围设计使用 `scripts/contract/scope-11.json`，须双方同摘要独立签认。

仅编写实现与必要测试、浏览器及证据脚本，暂不执行测试、构建或运行验收。真实模型未授权。完成后记录实际提交，双方齐备再进入12，不提前后续票。

提交：后端8709acf、8c5cefa；前端5ef8556、8678fe1、e548a63。双方代码已在后端任务树同步。最终范围摘要18c6add3bc9db6d78f30d29eaec3d7bef28f35303cf327ef0bf3926c418af0a6，本人设计双签齐备。未执行运行验证；按序进入12。
