# 接口设计基线索引

## 当前设计

唯一正式文件为[openapi.yaml](../openapi.yaml)，版本 `1.0.5`，SHA-256 `2eba1980d9e62318edfece692e46c977d9e55191f06c59e4629285f29f66f5fd`，完整95操作。09采纳此版本后未再改接口字节；当前批次本人设计签认见[24前端](contract-24-frontend.md)、[24后端](contract-24-backend.md)。当前适用范围与独立映射确认在同目录 `scope-24-{frontend,backend}.json`。

本索引仅导航设计与历史证据，不代表运行对齐或当前构建通过。实现进度查[根说明](../../../../README.md)，实际验收查对应技术实施记录及不可变报告；独立签认原件不因文档归并改写。

## 历史批准

| 版本 | 设计变化与独立签认 |
| --- | --- |
| 1.0.0 | 首次完整接口设计：[前端](frontend-baseline.md)、[后端](backend-baseline.md) |
| 1.0.1 | 上传及最新原文错误边界：[03前端](contract-03-frontend.md)、[03后端](contract-03-backend.md) |
| 1.0.2 | 模型与向量配置、探测任务及凭据：[06前端](contract-06-frontend.md)、[06后端](contract-06-backend.md) |
| 1.0.3 | 异步创建结果及来源补偿：[07前端](contract-07-frontend.md)、[07后端](contract-07-backend.md) |
| 1.0.4 | 索引、管理检索、事件及结果失效：[08前端](contract-08-frontend.md)、[08后端](contract-08-backend.md) |
| 1.0.5 | 文档来源定位澄清：[09前端](contract-09-frontend.md)、[09后端](contract-09-backend.md) |

各版本原始摘要及本人范围在对应签认原文，旧版本不能用于后续实现确认。[需求映射](../frontend-requirements.md)维护24票与操作的关系，[技术决策](../design-decisions.md)解释选择，二者不另定义字段。

## 历史设计检查

初次设计的[静态报告](../../../../.scratch/technical-design/contract-design-validation/static-report.json)及[脚本](../../../../.scratch/technical-design/contract-design-validation/validate.py)保留为证据，使用OpenAPI标准校验、结构模式和引用检查、代表性正反边界及工具反向自检。该历史设计检查不能证明后续业务实现或真实模型效果，也不替代现有根门禁。

设计变更、合并批次验证和双阶段验收按[接口规范](../README.md)，派活按[协作](../../collaboration.md)，不在本文件重复维护流程。
