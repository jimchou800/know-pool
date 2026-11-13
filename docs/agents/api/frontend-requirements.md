# 前端接口需求与审阅清单

本文件由前端领域代理维护，用于核对界面规格与正式契约的覆盖关系。唯一接口定义是 `openapi.yaml`；本文件不定义替代字段、路径或枚举。设计签认单独记录于 `reviews/frontend-baseline.md`，实现完成后另行生成和双审契约校验报告。

依据：`.scratch/ui-ux-design/spec.md`、技术规格最新确认项、领域约定及已批准的 24 个切片。需求整理不代表原型已连接正式接口。

## 操作与状态覆盖

| 界面与任务 | 必需操作和可消费信息 | 关键异常与边界 |
| --- | --- | --- |
| 本机入口、首页；01、24 | 初始化会话、获得请求保护信息、基础运行状态；最近问答由会话列表获取 | 来源拒绝、会话过期、保护令牌错误、迁移暂停写入、队列繁忙；外部服务故障不禁用本地资料管理 |
| 数据集；02、20 | 列表、创建、详情、修改名称说明、删除影响、删除；稳定标识、时间、文件数与受影响库 | 去首尾空白、同类不区分大小写唯一、空名拒绝；删除资料而不删除库 |
| 文件；03—05、12、20 | 批量上传逐项结果、列表详情、重命名、最新原格式预览下载、内容版本、关联库处理、删除影响与删除 | 同批重名组全部拒绝而其他项继续；同名哈希与大小均相同跳过、变化保留文件标识；不同名相同内容不合并；覆盖失败保留原状态；重命名冲突拒绝 |
| 知识库；07、13、18、20 | 列表、创建空库、详情、名称说明与处理配置修改、发布、下线、显式依赖复查、删除影响与删除 | 发布、运行可用、索引任务三维分开；发布拒绝保留状态；最后有效索引消失自动下线；恢复及重建不自动发布 |
| 来源与排除；07、11 | 数据集来源、单文件来源的建立撤销；排除取消排除；按文件列出来源、有效性、排除状态 | 来源并集、排除优先；显式加入不取消排除；撤销一种来源不能移除其他来源；无来源时取消排除不凭空加入 |
| 索引、切片预检；08—10、12、18—19 | 按库文件任务及阶段、错误、目标版本、生效版本、是否仍使用旧内容；切片统计样例、降级与空文本；单项及全部失败重试、切换关系 | 版本替代立即取消旧任务；迟到发布拒绝；普通文件独立切换、配置变更整库切换；重试逐项区分入队、已有任务、不符合条件；最多三次尝试含首次 |
| 管理检索；08、13 | 单知识库当前生效索引检索、原文及位置 | 独立管理入口可测未发布或下线库；不能通过普通检索参数绕过发布；无索引、无命中、依赖故障分别表达 |
| 独立检索；14—15 | 实例列表、新建、详情、重命名、删除、保存范围、提交、查询、取消；结果含文件、库、实际内容版本、原文位置及运行期快照 | 同实例新提交取消旧请求且请求身份可核对；切换实例不取消；重启只保留名称、最近已提交查询和范围；结果不持久化，不自动重跑 |
| 问答；16—17 | 会话列表、首问原子创建并提交、详情与历史、重命名、范围调整、删除；任务状态结果、取消与事件流；证据快照定位 | 空白首问前不保存、允许同名、尊重手动名称；同会话一项未完成任务；断线查询同任务；重启中断不重跑；取消删除后迟到响应不能复活 |
| 回答与范围；14、16—17 | 逐轮所选范围、实际范围、遗漏库原因、近期历史提示；最终回答及引用快照 | 无结果、无依据、部分缺口、矛盾、生成失败、校验失败分开；未校验正文不能出现在查询或事件中；全部库不可用终止，不能自动扩围 |
| 模型设置；06 | 生成、嵌入分别读取修改及连接测试；配置状态和密钥掩码；保持、替换、清除 | 掩码不得被当密钥回传；无环境变量兜底；测试不发送用户资料并明示可能费用；密钥不进入浏览器持久化、报告、日志 |
| 运行设置、迁移备份恢复；21—24 | 资源参数读取修改、当前数据目录、迁移预检提交与阶段、清理残留状态重试、备份恢复预检提交与结果 | 目标路径验证、暂停写入、复制校验切换清旧分别显示；失败不冒充成功；明文凭据和不含向量快照提示；需重建索引明确费用 |

## 已批准切片与正式操作映射

下表映射当前设计候选中的实际 `operationId`，用于后续按票选择实现范围；每票继承前置票已完成的操作，不重复计数。签认前任何标识修改须同步本表。评测和发行是验证活动，不为其发明业务接口。

| 任务 | 当前契约操作标识 |
| --- | --- |
| 01 本机闭环 | `initializeSession`、`getSystemStatus`；其余全部操作复用会话、安全和错误结构 |
| 02 数据集 | `listDatasets`、`createDataset`、`getDataset`、`updateDataset` |
| 03 纯文本资料 | `listDatasetFiles`、`uploadFiles`、`getFile`、`previewLatestFile`、`downloadLatestFile` |
| 04 Markdown 与 PDF | 复用 `uploadFiles`、`previewLatestFile`、`downloadLatestFile` 的三种媒体类型与位置结构 |
| 05 批量覆盖重命名 | `renameFile`、`listFileVersions`；复用 `uploadFiles` 的逐项结果与版本约束 |
| 06 模型配置 | `getGenerationModel`、`updateGenerationModel`、`testGenerationModel`、`getEmbeddingModel`、`updateEmbeddingModel`、`testEmbeddingModel`、`listEmbeddingModelVersions`、`getEmbeddingModelVersion`、`updateEmbeddingVersionCredential`、`getConnectionTestResult`；向量连接配套 `getVectorConfig`、`updateVectorConfig`、`testVectorConfig`；提前复用 `getTask`，共十四操作 |
| 07 知识库初始化 | `listKnowledgeBases`、`createKnowledgeBase`、`getKnowledgeBase`、`updateKnowledgeBase`、`getProcessingConfig`、`addKnowledgeBaseSource`、`listKnowledgeBaseSources`、`listKnowledgeBaseFiles` |
| 08 纯文本索引 | 继承 06 已有 `getTask`、`watchTask`、`listKnowledgeBaseTasks`、`testKnowledgeBaseSearch`；管理测试随后用 `getSearchRequest`、`getSearchEvidence` 读取结果 |
| 09 文档索引 | 复用 08 管理检索与文件读取操作，验证 PDF、Markdown 原文位置与内容类型 |
| 10 切片预检 | `getChunkPreflight`、`updateProcessingConfig`；消费切片统计、样例、降级、位置与完整性结构 |
| 11 来源与排除 | `removeKnowledgeBaseSource`、`listKnowledgeBaseExclusions`、`excludeKnowledgeBaseFile`、`includeKnowledgeBaseFile`、`listFileKnowledgeBases`；复用 07 来源及文件列表 |
| 12 内容切换 | `listIndexSwitches`、`previewVersionFile`、`downloadVersionFile`、`cancelTask`；复用上传、任务与文件处理状态 |
| 13 发布下线 | `publishKnowledgeBase`、`offlineKnowledgeBase`、`checkKnowledgeBase`；管理测试继续使用独立入口 |
| 14 多库检索 | `submitSearch`、`getSearchRequest`、`getSearchEvidence`；检索实例承载普通请求，先创建最小实例以便端到端演示，完整实例交互由 15 完成 |
| 15 检索实例 | `listSearchInstances`、`createSearchInstance`、`getSearchInstance`、`renameSearchInstance`、`updateSearchInstanceScope`、`deleteSearchInstance`、`cancelSearchRequest`；复用 14 提交及结果操作 |
| 16 异步问答 | `submitFirstQuestion`、`getQuestionTask`、`watchQuestionTask`、`getConversationEvidence`；首问原子创建会话和任务 |
| 17 多轮历史 | `listConversations`、`getConversation`、`renameConversation`、`updateConversationScope`、`listConversationTurns`、`submitQuestion`、`cancelQuestionTask`、`deleteConversation` |
| 18 配置重建 | `getProcessingConfigVersion`；复用 `updateProcessingConfig`、`listIndexSwitches`、按库任务与模型版本操作 |
| 19 恢复重试 | `retryKnowledgeBaseFile`、`retryFailedKnowledgeBaseFiles`；复用 `checkKnowledgeBase` 及任务状态查询 |
| 20 删除清理 | `getDatasetDeleteImpact`、`deleteDataset`、`getFileDeleteImpact`、`deleteFile`、`getKnowledgeBaseDeleteImpact`、`deleteKnowledgeBase`、`listTasks`、`retryCleanupTask`；复用实例删除与读取保护 |
| 21 目录迁移 | `migrationPreflight`、`submitMigration`、`getMaintenanceTask`、`retryMaintenanceTask`；复用系统写入状态 |
| 22 备份恢复 | `backupPreflight`、`submitBackup`、`restorePreflight`、`submitRestore`；复用维护任务与失败索引重试 |
| 23 质量与资源 | `getRuntimeSettings`、`updateRuntimeSettings`；固定资料与模拟模型验证既有检索问答操作，真实评测另行授权 |
| 24 发行验收 | 验证本表全部正式操作与约束及目标平台发行链路，不新增专属发行接口 |

任务 14 早于完整实例交互任务 15；当前协议以实例承载普通检索，因此 14 先落实所需最小 `createSearchInstance` 链路，15 再交付命名、列表、切换、持久化与取消交互。这是复用同一正式接口的实现拆分，不另建无实例检索协议。

## 传输约束审阅

- 所有实体身份、请求身份、时间与数值具有明确线上类型；前端不得依赖隐式数值转换。分页、排序、筛选、名称与正文长度、集合基数、运行限额均可确定；待测默认值应明确为实施验证项，不伪称经过性能评测。
- 每个操作确定唯一操作标识、路径方法、参数位置、媒体类型、成功及失败状态；必填、可空、只读只写、默认值与未知字段策略明确。条件组合约束须可定位并纳入后续校验，不能只检查字段存在。
- 公共错误提供稳定错误码、中文说明与请求标识。上传的逐项错误不能只用整批状态替代；全局繁忙、任务冲突、失效范围、重复名称及文件超限具有可识别结果。
- 最新原文件与历史引用分开；预览下载返回媒体类型、安全响应头、文件名和二进制定义。历史快照包含实际完整引用切片、引用时名、版本与位置；旧副本清理后不能提供伪造的完整旧文件下载。
- 事件流明确事件名称、事件标识、负载结构、终态及重连行为；仅通知阶段和完成，结果查询是事实来源；不用任意字符串代替事件模式。
- 会话 Cookie、请求保护头、来源及主机校验覆盖普通请求、上传、二进制与事件流。令牌不进入地址；密钥和内部绝对副本路径不进入响应。迁移管理所需用户配置路径与内部资料副本路径区分。
- 普通检索与管理测试分离；服务端重新校验发布、关联、排除、目标版本与请求身份。前端禁用操作不能替代后端资格保护。

## 独立签认与实现证据

前端代理先审阅完整最终契约，再读取文件字节计算 SHA-256，独立记录同一摘要的设计批准或阻断项；后端自行签认，任何后续字节变化使原签认失效。

设计审阅只证明契约可以成为开发基线。后续实现必须对真实请求构造、返回消费、错误、媒体类型、任务事件和边界逐项校验，自动报告绑定同一实现快照与契约摘要，再由双方分别验收。空契约、手写样例及原型测试不能证明运行对齐 100%。
