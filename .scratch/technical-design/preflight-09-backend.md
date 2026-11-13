# 09 后端只读预检

预检日期：2026-11-13。预检角色：`/root/backend08`。本文件仅记录技术建议，未领取 09，未修改业务代码、契约或票据状态。04 已完成；08 尚待最终验收，09 的原阻塞保持有效。08 当前仍在修复审查项，正式领取前须重新核对实际实现。

本次没有发现需要重新裁定的业务规则；下列未决项均可由前后端技术协商、共同审阅并交协调确认范围。

## 依据与现状

- 任务依据：[技术 09](issues/09-document-index.md)、[前端 09](../ui-ux-design/issues/09-document-index.md)。09 验证 Markdown、文字 PDF 的索引和原文位置；通用切片配置及预检界面属于 [技术 10](issues/10-chunk-preview.md)，完整版本切换展示属于 [技术 12](issues/12-content-switch.md)，不提前关闭后续票。
- 业务依据：[技术规格](spec.md)第 25、31、36、44 问及第 299—328、442—446 行；[界面规格](../ui-ux-design/spec.md)原文版本、危险内容和定位约束。原副本不改写；定位绑定实际内容版本；页面换行、页边界不直接等同句末；损坏、资源超限和无法可靠解析须明确表达，不静默截断。
- 唯一契约：[接口文件](../../docs/agents/api/openapi.yaml)，现场核验版本 `1.0.4`，SHA-256 `9b81bdc9561a3e0cb8aaaf0172e0013c6e741f2aea8efe283f64e14cebd1c444`。前端 09 票仍记录旧 `1.0.0`，正式领取时由共享文档写入者更新当前摘要与范围，不能继续采用旧摘要。
- 04 的真实交付边界见 [实施记录](implementation-04.md)：安全原格式预览、源行与页码基础已验收；全文索引提取、复杂阅读顺序及跨页映射未由 04 验收。

## 可复用的公开接缝

| 接缝 | 可复用部分 | 09 仍须补齐或核验 |
| --- | --- | --- |
| `FileService.versionContent(fileId, versionId)` | 指定版本副本读取、受控路径与可用性检查 | 当前返回的 `Stored.file` 来自当前 `get(fileId)`，其 `latestVersion` 不是指定版本的元信息；解析类型、媒体、大小等必须取实际目标版本，不按最新版本猜测 |
| `Indexes` 的任务领取、检查点、向量读回和发布 | 继承 08 的稳定任务、文件与配置版本、取消资格及生效切换 | 当前非 `text` 直接拒绝；扩展解析输出，不复制一套索引、任务或检索流程；须以最终验收的 08 为基线 |
| `testKnowledgeBaseSearch`、`getSearchRequest`、`getSearchEvidence` | 单库管理检索、完整证据及版本身份、运行期失效 | 用真实 Markdown/PDF 命中补充各媒体的 `Location`，保留无匹配与依赖失败的区别 |
| `getTask`、`watchTask`、`listKnowledgeBaseTasks` | 阶段、终态、恢复和查询 | 全文提取的超时、超限、损坏、取消与恢复仍走同一任务事实，不通过预览或订阅重新外发模型 |
| `previewLatestFile`、`downloadLatestFile`、`listFileVersions` | 既有安全二进制响应和版本目录 | 数据集查看最新原文可以直接复用；证据的历史定位不能调用最新原文代替 |
| `previewVersionFile`、`downloadVersionFile` | 契约已正式定义指定版本的三种媒体、鉴权、完整字节及 410 | 当前 `FileController` 没有对应路由，前端文件客户端也只有最新二进制入口；这是实现缺项，不是缺少契约。现行操作映射将其列在 12，须先由协调明确 09 的最小历史定位闭环是否提前纳入这两个操作 |
| 前端 `TextPreview`、`PdfPreview` | 已有源行、页码导航、安全渲染与资源限制 | 当前导航依赖手动输入，PDF 初始打开第一页；由前端提供证据指定版本、起始行或页与范围提示的受控入口，不需要新增“根据文件名猜位置”接口 |

源码依据：[文件服务](../../server/src/main/java/cn/knowpool/datasets/FileService.java)、[文件控制器](../../server/src/main/java/cn/knowpool/datasets/api/FileController.java)、[索引服务](../../server/src/main/java/cn/knowpool/knowledgebases/Indexes.java)、[文本预览规则](../../front/src/files/text-preview.js)、[文本预览组件](../../front/src/files/TextPreview.vue)、[PDF 预览组件](../../front/src/files/PdfPreview.vue)、[操作映射](../../docs/agents/api/frontend-requirements.md)。

## 最小技术方案

### 共用解析结果及溯源

在现有索引流水线的解析阶段产生有序文本片段及其来源映射，映射绑定固定文件、内容版本、解析规则版本和配置版本。切片只消费此结果，正文范围、重叠和降级均能回溯来源；不把解析后的文本当成新原文件。PDF 页和 Markdown 源行是两种不同坐标，不相互伪造。

解析工作在数据库事务之外执行；固定来源资格、持久化解析结果及最终发布仍采用短事务。继承 08 的读取保护、取消及检查点边界，不在重启时重新运行可能已收费的嵌入。旧副本读取期间不得清理，迟到的解析或模型结果不得发布。

### Markdown

1. 严格 UTF-8 解码。与 04 已验收的 `sourceLines` 对齐：仅移除首个 BOM，将 CRLF 与孤立 CR 统一为 LF，从一计行并保留末尾空行；原副本、哈希及下载字节不改写。转换前后保留可追溯关系，不用渲染后的屏幕折行作为源行。
2. 使用已选定的 CommonMark 抽象语法树并启用源位置。现行两个 POM 均没有 CommonMark 依赖，需协调写入者先锁定版本；管道表格如需结构定位，同版本加入表格扩展并与前端 markdown-it 的固定样例核对。
3. 标题按层级形成 `titlePath`，段落、列表、表格及代码块保持来源范围与结构身份。稳定身份应以实际内容版本及源结构位置产生，续片共享结构身份，不能把每片全局序号当成其段落身份。
4. CommonMark 源行从零开始，映射到契约时加一；源列和长度使用 UTF-16 单元，不能直接当成 Unicode 码点计量。内部偏移用于准确提取，切片长度仍按码点计量并保护代理对。
5. 不执行原始 HTML、图片、链接或代码；解析器不访问资料给出的网络地址。不默认删除页眉页脚或其他正文。09 实现必要定位，不把尚未交付的 10 预检界面和全部配置编辑能力计为完成。

### 文字 PDF

1. 复用 PDFBox `3.0.6` 和既有独立进程资源隔离方式，但新增完整提取职责，不能复用首文字早停结果冒充全文。当前 `FileInspectionWorker.TextInspection` 发现第一个非空文字项即抛出 `TextFound` 并判可导入，后续页可能未检查。
2. 有界全文提取应保存输出文本与起止页映射；按 PDFBox 的页生命周期和文字位置建立来源范围。确定性阅读顺序算法须固定版本，以中文双页、跨页句子及表格样例验证，不能以 `setSortByPosition(true)` 声称自动恢复复杂版面语义。
3. 跨页句子保留完整语义与起止页，不机械逐页截断；`page` 策略也遵守已确认的完整性优先级。PDF 的 `lineStart`、`lineEnd` 可空，不能伪造 Markdown 式源行；至少能打开实际起始页并明确跨页范围。
4. 页眉页脚去除只能使用明确配置，默认保留；所有重复标题、表头及重叠占切片预算。表格无法可靠恢复时不得捏造行列位置或把续片标成独立完整表格。
5. 使用现有解析超时和最大提取码点参数，过程内限内存、限输出并能够终止；超时或超限返回 `RESOURCE_LIMIT`，损坏或无法建立可靠解析结果返回 `PARSING_FAILED`，不发布前缀成功索引。整份无可提取文字或加密 PDF 沿用上传不支持规则，不增加扫描识别。
6. 部分页面没有可提取文字不等同于能够判定扫描、空白或纯图表；不能仅凭“文件含文字”证明全文完整，也不能声称规则能识别所有阅读顺序异常。明确记录可验证范围；已确认提取缺失或顺序异常应报告真实问题，无法可靠定位时失败，不能以静默丢正文冒充完整成功。

资源参数依据：[上传与处理配置](../../server/src/main/java/cn/knowpool/infrastructure/UploadConfiguration.java)、[运行参数](../../server/src/main/resources/application.properties)。当前工程初值为单份 10 MiB、解析 10 秒、提取上限 10,485,760 码点；这些是现有参数，不代表 09 全文提取性能已经测量或验收。

## 契约判断及必要双审

1. **现有结构足够承载 09 核心结果。** `Location` 已有 `format`、起止页/行、标题路径、段落与结构身份、续片、表格行列和代码位置。PDF 必须有合法起止页，其余文本必须有合法起止行；所有字段均必填，不适用字段明确为 `null`，不能省略。当前没有必须新增公开业务端点或字段才能索引这两种格式的证据。
2. **语义需要技术落定。** 双方确认换行/BOM规则、起止范围包含关系、标题路径是否含当前标题、段落与结构身份生成、续片序号归属、表格表头及行列计数、跨页打开行为、无副本时展示证据。规则可先写共同设计；如改变正式字段说明或条件约束，按唯一契约流程变更并重新双签摘要。
3. **历史原文闭环需要协调划界。** 指定版本预览下载已定义但未实现，操作映射当前属于 12。建议 09 提前实现供定位必需的指定版本预览，并同时明确下载是否纳入；范围映射、原始分母和双方验收须同步，不自行吞并 12 的切换历史、取消及完整界面工作。
4. **异常已有表达通道。** `TaskFailure` 具有封闭错误码枚举，使用已有 `PARSING_FAILED`、`RESOURCE_LIMIT`、`INVALID_FILE`、`INTEGRITY_FAILED` 等，不新增未声明错误码；限制说明可用 `Task.warnings` 的中文数组。`Evidence.updateState` 仅允许 `ready`、`updating`、`failed`，不能直接复制文件处理状态的其他枚举。
5. **不擅自扩展定位精度。** 当前契约没有 PDF 矩形坐标、逐字高亮或全新定位令牌，页码与源行足以支持本票承诺。若后续确需这些字段，另行说明必要性并双审，不先实现第二套隐藏协议。
6. **必须保存解析版本与来源事实。** 当前 `index_runs` 及 `index_chunks` 主要存处理身份、正文、位置和远端检查点，尚不能据此认定解析规则版本、清洗映射及复杂结构来源已完整落库。由后端提出最小内部迁移，并由前端核对实际输出和失效行为；不因内部迁移自动增加公开接口。

## 固定资料与公开验证安排

| 资料或入口 | 已有价值 | 09 新增断言 |
| --- | --- | --- |
| `server/src/test/resources/documents/chinese-two-pages-actions.pdf` | 两页中文，含打开动作及注释；既有测试明确两页标题 | 真实上传、索引、管理检索、证据页码与实际 PDF 预览一致；索引不能执行动作或外联。该夹具没有已证明的跨页句子/表格，不冒充这两类验收 |
| 同目录 `image-only.pdf` | 纯图片扫描样例 | 继续拒绝，不触发模型或新增索引 |
| 同目录 `oversized-page.pdf`、`oversized-image.pdf` | 04 的真实预览资源边界 | 回归预览的明确资源错误；不能把画布限制当成索引全文提取限制的证据 |
| `LocalApplicationTest#preserveChinesePdfAndUnsafeMarkdownBytesWhileRejectingImageOnlyPdf` | 中文 PDF、危险 Markdown、BOM 及混合换行，原字节保留 | 增加 Markdown 标题/段落源行与内容版本定位；危险文本保持证据，不执行、不外联 |
| `LocalApplicationTest#rejectUnreadablePdfAndBoundTextInspection` | 动态生成损坏、加密、无文字、长空白 PDF | 作为上传回归；另补“前页有文字、后页超限或损坏”以证明不是首文字早停冒充全文成功 |
| `front/scripts/check-document-previews.mjs`、`TextPreview`/`PdfPreview` 测试 | 真实危险 Markdown、安全预览、页码/源行、原字节下载及资源预算 | 从真实检索证据直接打开对应内容版本；验证范围、焦点、错误、切换取消与三个桌面宽度 |
| 09 待新增确定性夹具 | 当前无经过验收的中文跨页句子、跨页表格及阅读顺序异常夹具 | 标注每页实际文字、跨页句子、表格行列与预期位置；Markdown 覆盖重复标题、多层标题、列表、表格、代码、补充平面字符、CRLF/孤立 CR/末尾空行 |

后端沿用已确认的真实 HTTP 接缝、SQLite 持久事实及受控外部嵌入/Qdrant；断言真实检索证据和实际原文位置，不测私有切片方法、不直接改库冒充业务。至少覆盖成功、损坏/无法可靠解析、资源超限、覆盖竞争、读取版本保护、重启检查点与指定版本 404/410；前端用相同资料和真实后端完成定位。普通验证不使用真实收费模型。

## 一手技术资料及未验证项

- [CommonMark 解析器源码](https://github.com/commonmark/commonmark-java/blob/main/commonmark/src/main/java/org/commonmark/parser/Parser.java)：已现场读取 `parse`、`parseReader`、`includeSourceSpans` 接缝。
- [CommonMark 源位置源码](https://github.com/commonmark/commonmark-java/blob/main/commonmark/src/main/java/org/commonmark/node/SourceSpan.java)：已现场核实从零计行、整篇输入偏移及 UTF-16 单元说明。
- [表格扩展](https://github.com/commonmark/commonmark-java/blob/main/commonmark-ext-gfm-tables/src/main/java/org/commonmark/ext/gfm/tables/TablesExtension.java)及[表格解析源码](https://github.com/commonmark/commonmark-java/blob/main/commonmark-ext-gfm-tables/src/main/java/org/commonmark/ext/gfm/tables/internal/TableBlockParser.java)：已现场核实需显式启用扩展，源码具有表头、行与单元格源位置处理。
- [PDFBox 3.0.6 提取器源码](https://github.com/apache/pdfbox/blob/3.0.6/pdfbox/src/main/java/org/apache/pdfbox/text/PDFTextStripper.java)：已现场核实页生命周期、当前页号、文字位置和位置排序接缝；排序能力不等于语义阅读顺序恢复。

CommonMark 当前仅核对上游源码能力，尚未选定、安装或编译验证具体发布版本；不得将主分支能力直接当作已锁定依赖。此次未运行 Maven、浏览器或收费模型，未创建 09 夹具，未做全文提取性能试验，未生成 09 契约报告或设计双签。正式领取仍须先完成 08 验收，再确定共享文件写入者、实际操作范围、依赖版本及共同设计。
