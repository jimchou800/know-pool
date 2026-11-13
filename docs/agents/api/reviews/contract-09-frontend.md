# 09 契约草案前端独立审查

审查角色：`/root/frontend09`。结论：批准下述同字节草案作为 09 消费方设计基线，由协调在双方确认齐备后正式采纳。本记录仅签认设计，不代表实现或运行验收。

草案位于后端工作树 `.scratch/technical-design/contract-09-draft.json`；版本为 `1.0.5`。本人现场读取原始字节并计算 SHA-256 为 `2eba1980d9e62318edfece692e46c977d9e55191f06c59e4629285f29f66f5fd`，与送审摘要一致。比较基线为本人工作树正式 `1.0.4`，SHA-256 为 `9b81bdc9561a3e0cb8aaaf0172e0013c6e741f2aea8efe283f64e14cebd1c444`。

## 本人执行与差异

本人通过 Python 标准库递归比较完整文档，并断言变更指针集合恰好为以下 14 项：`/info/version`、`/components/schemas/Location/description`，以及 `Location/properties` 下 `pageStart`、`pageEnd`、`lineStart`、`lineEnd`、`titlePath`、`paragraphId`、`structureId`、`continuationIndex`、`tableRowStart`、`tableRowEnd`、`tableColumnStart`、`tableColumnEnd` 的 `description`。没有路径、操作、安全、媒体、字段类型、必填、可空、枚举或结构约束变更。

本人将根 `.runtime/contract-tools` 加入 Python 模块搜索路径，独立执行 `openapi_spec_validator.validate_spec` 及全部结构的 `Draft202012Validator.check_schema`，均通过；操作标识唯一且仍为 95。该执行没有改写正式契约或生成实现报告。

## 消费语义确认

- 页行从 1 起，起止均包含；文本规范化与现有 `sourceLines` 的 BOM、CRLF、孤立 CR 及末尾空行一致，原始副本不改变。
- 标题路径含当前所属标题，标题自身含自己，跨结构聚合取共同祖先；真实段落或单列表项身份不使用文件片序号，实际结构容器续片共享身份，无法可靠识别不伪造。
- 表格行列从 0 起且表头为第 0 行；Markdown 分隔行只计源行，重复注入表头不扩大该片实际数据行范围；未知坐标为 `null`。
- `continuationIndex` 对已识别结构表示结构内序号；无结构身份纯文本兼容 08 按文件从 0 起的稳定片序号。前端依据格式与结构身份展示，不能将该纯文本序号标为真实段落或结构续片。此规则补充并优先于本人前端设计中未列兼容例外的笼统表述。
- 所有字段必填，不适用为 `null`；PDF 不可靠源行和结构不补造。前端显示实际完整证据及范围，预览验证初始页/行，越界不夹到第一页或第一行冒充成功。

本人已独立阅读后端设计提交 `00cd8f1`，同意其固定页行样例、跨页范围、真实结构身份与重复表头来源约定；指定版本读取保护是待实现要求，不能声称旧代码已经满足。目标版本必须通过显式版本元数据读取，不将旧版本塞入当前 `latestVersion`。版本不存在或归属错误为 404、明确副本不可用为 410、元数据可用而磁盘异常为 500，不回退最新正文。

09 仅提前已有 `previewVersionFile`，复用现有安全二进制消费与 `TextPreview`、`PdfPreview`；`downloadVersionFile`、切换历史及完整 12 验收保留原票。协调须单写同步操作映射、09 累积分母及 12 复用范围，不重复计新增操作，不将本签认当作 12 完成。

## 尚未验收

业务编码仍冻结，未运行 09 行为测试、根 Maven、浏览器或收费模型。08 修复提交 `8269835` 的最终自动报告须另行读取本人复核，不沿用旧实现快照签认。复杂 PDF 阅读顺序、跨页表格恢复及资源限制以实现和固定资料实证为准，不能由本次静态设计签认推定通过。
