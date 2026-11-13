# 08 前端设计交接

状态：技术设计与契约复核；尚未编码，尚未签认下一版正式契约或实现报告。

实际前端代理：`/root/frontend08`；后端同伴：`/root/backend08`。工作树 `.worktrees/frontend08`，分支 `implement/08-frontend`。本文件为实施交接，不新增规格。

## 依据与范围

已核对根 `AGENTS.md`、前端角色、术语表、领域约定、现行技术规格及架构、统一界面规格、工程及协作规范、接口协作规范、两份 08 票、08 实施记录及 07 下一前沿预检。未发现 `docs/adr/`。实际契约 `1.0.3` 的 SHA-256 为 `8c94c7c52284c4bc7e4445bf1a2b175641e3f3c8d5d498a1d1afdfb512ec28ef`。

本票消费六操作：`getTask`、`watchTask`、`listKnowledgeBaseTasks`、`testKnowledgeBaseSearch`、`getSearchRequest`、`getSearchEvidence`。沿用 07 知识库详情、现有会话保护及中文浅色工作台。实现后只写 `front/`，不写 POM、锁文件、正式契约或共享验证工具。

## 最小完整交互

知识库详情增加“处理任务”和“单库检索测试”两个局部区域，按实际复用拆为专用组件；不另建全局任务框架。列表、配置、来源、文件仍沿用原页面。

处理任务先读取当前库任务分页，显示文件、目标内容及配置版本、阶段、尝试次数、真实完成量与总量、时间、警告及失败原因。总量为空时只显示阶段和已完成数量，不计算虚假百分比；终态不等于一定已有生效版本。现有文件区域仍分别显示纳入、更新、旧生效版本及失败状态。任务成功后重读当前库和文件，不能仅凭 `succeeded` 宣称已发布。

选中的非终态任务订阅事件，阶段通知促发原任务查询，终态关闭事件流并刷新权威任务及库文件状态。用户切换任务或知识库时释放本地订阅，后台任务继续；查询序号和库、任务身份阻止迟到响应污染新页面。任务页支持不透明游标与防重复激活，读取失败只提供重读入口，不重新提交索引任务。

管理检索固定当前库，没有普通服务范围开关。明确说明“仅检索当前生效索引，不生成回答，可能调用嵌入服务并产生费用”，允许未发布或已下线库。查询不能为空白且最多 20000 码点；实际请求只含 `query` 与 `knowledgeBaseIds: [当前库标识]`。

一次显式提交固定查询、库与幂等键。受理身份不确定时保留同一次键和负载，提供“继续确认本次检索”；不能把网络错误变为新的收费请求。取得请求标识后查询原请求；失败读取提供“继续查询原请求”。非终态期间禁用重复新提交；任务终止后用户显式新检索使用新键。管理测试没有实例，不借用后续实例新查询取消旧请求的操作。切库隔离迟到结果，不自动重提或取消服务端任务。

结果仅由 `getSearchRequest` 的成功终态读取。无索引显示“请先完成资料处理”；成功且无命中显示“未找到相关内容”；模型、向量或范围故障分别显示脱敏原因。运行期结果或证据 `410/RESULT_EXPIRED` 显示“结果已失效，请手动重新检索”，绝不自动付费恢复。部分范围与全部范围分支保持原契约语义，管理操作不扩到其他库。

命中显示原文切片、所属当前库、捕获时文件名与可空当前文件名、实际内容/索引/配置版本、位置、是否旧生效版本及更新失败/更新中提示；不显示跨模型可信度百分比。点击“查看原文依据”实际调用 `getSearchEvidence`，在可关闭的依据区域展示完整真实切片和位置；原文件不可用时明确只剩证据快照，不能打开最新正文冒充旧版本。资料正文按不可信纯文本插值，不解析未净化 HTML。关闭后焦点回到触发点，错误和阶段具有可访问状态提示。

## 六操作及字段消费清单

所有请求使用实际稳定 UUID，普通请求通过现有 Axios 会话客户端，写操作带内存防跨站令牌。响应拒绝未知字段、数值字符串、非安全整数、非法时间、非法枚举及缺失必填；可空字段不能用默认值掩盖缺失。响应身份必须与当前库、任务、请求及证据一致。

| 操作 | 输入及成功结构 | 关键消费及分支 |
| --- | --- | --- |
| `listKnowledgeBaseTasks` | 路径库标识，`limit` 1—100（客户端沿用 20），可选非空 `cursor` 最多 2048；`TaskPage.items/nextCursor` | 分页任务属于当前库；空态、加载、失败重读、末页空游标；不将创建任务校验机械放宽为任意类型 |
| `getTask` | 路径任务标识；`Task` | 现有创建消费保持严格类型；本票索引及检索分别检查适用类型和身份，任务事实来源 |
| `watchTask` | 路径任务标识，可选 `Last-Event-ID` 最多 100；`text/event-stream` | 只处理 `phase/terminal`，验证帧标识与 `TaskEvent`，断线先查原任务再重连；注释心跳无业务效果 |
| `testKnowledgeBaseSearch` | 当前单库路径、令牌、UUID 幂等键、`SearchSubmit`；202 `SearchRequest` | 精确单库、固定本次输入及键、受理后原请求查询；字段错误、队列满、暂停写入、会话/来源及依赖错误分别保留 |
| `getSearchRequest` | 路径请求标识；`SearchRequest` | 身份与任务、结果关联；成功才消费结果，失败显示 `task.failure`，取消/中断独立，410 不自动重提 |
| `getSearchEvidence` | 路径请求及证据标识；`Evidence` | 证据必须属于当前请求结果及当前库，完整正文、实际版本与定位、旧版本/更新状态/原文件可用性，404 与 410 区分 |

`Task` 全字段：`id/kind/state/phase/createdAt/startedAt/finishedAt/knowledgeBaseId/fileId/targetContentVersionId/targetConfigVersionId/retryOfTaskId/attempt/progressCompleted/progressTotal/failure/warnings/resultResourceId`。六状态均消费；所有终态要求 `phase=completed` 且结束时间非空，排队开始时间为空；失败或中断有合法 `TaskFailure`，其他状态失败字段为空。失败结构全部消费 `code/message/retryable`；重试性质不等于本票可直接重跑，显式重试索引入口仍属后续批准票据。

`TaskEvent` 全字段：`taskId/sequence/state/phase/occurredAt`；序号为安全正整数，帧 `id=taskId:sequence`，终态事件与终态状态匹配。重复或旧序号不倒退当前状态，不把事件正文当作结果。流专用使用原生 `fetch` 与同源凭据、取消控制器，以支持恢复头；普通请求仍为 Axios。不将令牌放入 URL。解析覆盖拆帧、换行、注释与非法事件；关闭流不是取消任务。

`SearchRequest` 全字段：`id/instanceId/task/result/scope`。管理 `instanceId=null`；成功时结果非空，其余状态为空；`scope` 检查前为空，检查后失败也保留。`SearchResult` 全字段：`requestId/instanceId/taskId/query/scope/outcome/evidence/preprocessingState/additionalQueries/warnings`。本票最小管线不调用生成预处理，使用 `not_used` 与空附加查询；原查询仍执行嵌入检索。

`ScopeResult` 全字段：`selectedKnowledgeBaseIds/usedKnowledgeBaseIds/omitted/incomplete`；遗漏项消费 `knowledgeBaseId/reason/message`，理由覆盖 `deleted/not_published/unavailable/no_effective_index` 的正式枚举校验。管理运行仅选单库，未发布不造成遗漏；成功的使用范围为该库，无可用库须失败，不能返回无命中成功。不存在跨库部分成功的实际管理路径，不伪造它为本票证据。

`Evidence` 全字段：`id/chunkId/knowledgeBaseId/fileId/contentVersionId/indexVersionId/configVersionId/filenameAtCapture/currentFilename/text/location/originalAvailable/usingPreviousVersion/updateState/capturedAt`。原文与快照身份不可替换；当前名称为空合法，捕获名称不能缺失；更新状态覆盖 `ready/updating/failed`。

`Location` 全字段：`format/pageStart/pageEnd/lineStart/lineEnd/titlePath/paragraphId/structureId/continuationIndex/tableRowStart/tableRowEnd/tableColumnStart/tableColumnEnd/codeLanguage/codeBlockId`。纯文本必须有真实起止行且末行不小于首行；可选结构位置不得伪造。本票纯文本真实路径不产生 PDF 页码、Markdown 标题、表格及代码块定位；这些结构仍严格校验类型及可空，但真实格式处理留给 09/10，适用映射不得用缺测试作豁免理由。

## 已与后端协商的待补契约技术定义

以下为共同认可的补全方向，未修改正式契约，也尚未对草案字节双签；请协调单写、静态校验后再发实际两领域独立签认。

1. `#/components/schemas/Task/properties/resultResourceId` 及适用条件：明确 `search` 成功对应请求标识，`index_file` 成功对应生效索引版本标识；非成功为空。保留知识库创建原规则。明确请求及结果任务身份一致，不靠不支持的跨字段 JSON Schema 比较伪造约束。
2. `#/paths/~1knowledge-bases~1{knowledgeBaseId}~1search-test/post`：结构错误同步 400，不存在库同步 404，限流和维护等未受理错误仍用正式响应。已受理检索没有有效索引时异步失败 `NO_EFFECTIVE_INDEX`，模型/向量故障分别失败，不返回 `no_matches`；明确当前单库及生效版本过滤，且不调用生成或回答校验。
3. `#/components/schemas/SearchRequest`、`#/components/schemas/SearchResult`：管理实例标识恒空，`SearchRequest.id=SearchResult.requestId=成功任务.resultResourceId`，`Task.id=SearchResult.taskId`；选定范围恰好是路径库，原文证据也属于该库。普通检索实例原规则不受影响。
4. `#/paths/~1tasks~1{taskId}~1events/get/parameters` 与 `x-sse`：恢复头采用任务 UUID、冒号、安全正整数序号；非法格式或错误任务返回 400；合法旧/未来序号都只通知当前快照，不承诺阶段回放。真实持久状态变化才增加序号；重连和终态重发不增加；终态发送后关闭。断线查询原任务，不重跑。
5. `#/paths/~1search-requests~1{requestId}/get`、`#/paths/~1search-requests~1{requestId}~1evidence~1{evidenceId}/get`：用持久身份墓碑区分未知 404 与已失效 410，证据/结果正文只保留运行期，重启后不假装仍可读。不增加永久检索历史。

无新增业务待确认项。首次索引的取消、当前内容及目标配置版本、有效关联和文件资格原子发布保护，以及不自动收费重嵌入恢复，仍由后端按已确认规则实施；前端不以禁用按钮替代权威校验。

已交叉审阅后端工作树的 `.scratch/technical-design/backend-08-design.md`，前端确认五项补全与消费设计一致，无阻断分歧。后端已接受管理提交身份不确定时复用原键、原任务恢复、同源事件流及实际证据读取。此设计审阅不代替正式草案同字节双签或实现验收。

## 实现触点、测试与交付

预计触点为 `front/src/knowledge-bases/` 的页面、专用索引检索组件/客户端及测试，必要的既有严格任务校验复用、`front/test-fixtures/` 的契约固定样例及 `front/scripts/check-live.mjs` 的真实浏览器流程。保持现有创建调用者与测试不退化；不扩大导出或新增依赖。集成工具由 `/root/integration08` 单写。

已读取测试驱动技能及公开边界、模拟指南；用户已确认公开界面操作、实际客户端消费、真实浏览器与后端替身联调边界，不重复询问。获实施授权及正式草案双签后，每轮只做一个公开行为的失败测试，再最小实现使其通过；不测私有方法，不先堆全部测试。

公开行为依次覆盖任务分页与阶段、事件正常/中断/重复/终态及原查询恢复、单库请求和幂等重送、成功及无命中、无索引和依赖故障、运行期失效、证据身份与真实定位、安全正文及焦点、切库/迟到响应隔离。使用固定中文纯文本和模型/向量服务替身，保留实际请求、响应及帧证据；负例断言拒绝且不触发收费重复请求。真实浏览器验证 1280、1440、1920 及长内容，截图逐张目视。

实现完成执行前端格式、静态、组件及客户端测试、行/分支各至少 80% 和生产构建；集成根 `mvn verify` 后实际两领域分别审阅同一契约、实现和不可变报告并独立签认。不以本设计、手写样例或旧票报告宣称完成。

尚未编码、运行新测试或浏览器验证；风险为待补技术定义未经正式草案双签，当前不可作为实现基线。未进行拉取、推送、发布或真实模型调用。
