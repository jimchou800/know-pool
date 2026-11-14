# 已批准的前端任务

状态：用户已确认并发布 24 个本地前端工作包；01 至 08 已随对应技术切片共同完成验收，后续按技术票据依赖推进。

这些工作包细化既有技术切片，不形成独立实施排期。原技术任务是端到端状态的唯一依据，领取时同步记录双方负责人，避免重复派发。依赖、集成和统一验证顺序遵循根代理约定；前置实现齐备并集成后可继续编码，验收状态单独记录，不能依据前端单方完成或 `ready-for-agent` 分流状态宣称完成。

审批与拆分依据见本文件批准记录。各票包含契约范围、前后端分工、验收条件、共同门禁及证据要求。

| 前端工作包 | 阻塞于原技术任务 | 关联原切片 | 执行状态 |
| --- | --- | --- | --- |
| [01：建立受保护的正式工作台入口](issues/01-local-contract.md) | 无 | [技术 01](../technical-design/issues/01-local-contract.md) | 已完成 |
| [02：创建和维护数据集](issues/02-datasets.md) | 技术 01 | [技术 02](../technical-design/issues/02-datasets.md) | 已完成 |
| [03：上传、预览和下载单份纯文本资料](issues/03-text-files.md) | 技术 02 | [技术 03](../technical-design/issues/03-text-files.md) | 已完成 |
| [04：安全预览 Markdown 和文字 PDF](issues/04-document-preview.md) | 技术 03 | [技术 04](../technical-design/issues/04-document-preview.md) | 已完成 |
| [05：批量上传、同名覆盖和资料重命名](issues/05-batch-upload.md) | 技术 03 | [技术 05](../technical-design/issues/05-batch-upload.md) | 已完成 |
| [06：显式配置模型与向量连接](issues/06-model-settings.md) | 技术 01 | [技术 06](../technical-design/issues/06-model-settings.md) | 已完成 |
| [07：创建空知识库并关联资料](issues/07-knowledge-base.md) | 技术 03、技术 06 | [技术 07](../technical-design/issues/07-knowledge-base.md) | 已完成 |
| [08：查看纯文本索引并执行管理检索](issues/08-text-index.md) | 技术 07 | [技术 08](../technical-design/issues/08-text-index.md) | 已完成 |
| [09：核对文档索引结果的原文位置](issues/09-document-index.md) | 技术 04、技术 08 | [技术 09](../technical-design/issues/09-document-index.md) | 已实现，待统一验证 |
| [10：配置切片并查看完整性预检](issues/10-chunk-preview.md) | 技术 09 | [技术 10](../technical-design/issues/10-chunk-preview.md) | 已实现，待统一验证 |
| [11：管理数据集同步、多来源和单文件排除](issues/11-associations.md) | 技术 08 | [技术 11](../technical-design/issues/11-associations.md) | 已实现，待统一验证 |
| [12：展示内容更新、旧版本服务和切换记录](issues/12-content-switch.md) | 技术 05、技术 08 | [技术 12](../technical-design/issues/12-content-switch.md) | 已实现，待统一验证 |
| [13：发布、下线和显式复查知识库](issues/13-publication.md) | 技术 11 | [技术 13](../technical-design/issues/13-publication.md) | 已实现，待统一验证 |
| [14：打通最小实例的多知识库检索](issues/14-multi-search.md) | 技术 13 | [技术 14](../technical-design/issues/14-multi-search.md) | 已实现，待统一验证 |
| [15：完整管理检索实例及重启恢复](issues/15-search-instances.md) | 技术 14 | [技术 15](../technical-design/issues/15-search-instances.md) | 已实现，待统一验证 |
| [16：提交首问并展示通过校验的引用回答](issues/16-grounded-answer.md) | 技术 14 | [技术 16](../technical-design/issues/16-grounded-answer.md) | 已实现，待统一验证 |
| [17：继续会话、恢复首页最近问答并处理取消删除](issues/17-conversation-history.md) | 技术 16 | [技术 17](../technical-design/issues/17-conversation-history.md) | 已实现，待统一验证 |
| [18：修改配置后观察整库重建与切换](issues/18-configuration-rebuild.md) | 技术 10、技术 12 | [技术 18](../technical-design/issues/18-configuration-rebuild.md) | 已实现，待统一验证 |
| [19：恢复任务并显式重试失败资料](issues/19-index-recovery.md) | 技术 12 | [技术 19](../technical-design/issues/19-index-recovery.md) | 已实现，待统一验证 |
| [20：确认删除影响并恢复失败清理](issues/20-deletion-cleanup.md) | 技术 11、技术 12、技术 13、技术 17 | [技术 20](../technical-design/issues/20-deletion-cleanup.md) | 已实现，待统一验证 |
| [21：安全迁移数据目录并呈现恢复阶段](issues/21-data-migration.md) | 技术 17、技术 19、技术 20 | [技术 21](../technical-design/issues/21-data-migration.md) | 已实现，待统一验证 |
| [22：备份恢复并明确索引和凭据边界](issues/22-backup-restore.md) | 技术 18、技术 19、技术 20 | [技术 22](../technical-design/issues/22-backup-restore.md) | 已实现，待统一验证 |
| [23：设置实测资源参数并验证中文业务边界](issues/23-quality-evaluation.md) | 技术 09、技术 10、技术 14、技术 16、技术 17、技术 18、技术 19 | [技术 23](../technical-design/issues/23-quality-evaluation.md) | 已实现，待统一验证 |
| [24：验收完整网页发行与桌面适配边界](issues/24-release-acceptance.md) | 技术 15、技术 17、技术 18、技术 19、技术 20、技术 21、技术 22、技术 23 | [技术 24](../technical-design/issues/24-release-acceptance.md) | 已实现，待统一验证 |

## 当前可领取范围

01—08 有历史端到端验收记录；09—24 已集成，待统一验证和修复。依赖按根代理约定以已提交实现推进编码，验收仍须统一验证及双方独立签认。

## 完成门槛

- 本票全部接口操作及约束 100% 对齐，全项目未实现范围单列。
- 自动报告绑定同一契约和实现快照，前后端分别确认，总协调集成验收；缺项不关闭。
- 前后端行、分支覆盖率分别至少 80%，通过对应工程、真实浏览器流程及截图检查。
- 真实模型评测单独授权；桌面宿主实现留待后续确认，网页发行不代表桌面封装已验收。

## 发布检查

已检查 24 份独立文件、连续编号、阻塞与原技术票一致且无环、关联原切片链接有效，全部 95 个正式操作均在任务范围明确列出或继承。契约摘要与已批准基线一致。本检查只证明任务发布完整，不是正式业务实现对齐报告。

06 已随技术切片完成验收，证据见 [06 实施记录](../technical-design/implementation-06.md)。后续按既定阻塞关系领取，不以领域完成代替验收。

当前实现前沿：09—24 已集成，待统一验证和修复。依赖已提交实现可用于后续编码，统一验证与验收后置，见根代理约定。

## 批准记录

2026-11-12 用户回复“确认”，批准24个切片的粒度、编号及硬依赖。原拆分草案已发布为本索引所列独立任务，前端工作包不另建排期；正式任务保留交付行为、依赖、独特验收与讨论。草案不再重复维护，原文可用 `git show 6e3454a:.scratch/ui-ux-design/tickets-draft.md` 追溯。当前进度以任务正文为准，发布和批准本身不代表实现或运行验收。
