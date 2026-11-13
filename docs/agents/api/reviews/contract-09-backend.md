# 09 契约草案后端独立审查

审查角色：`/root/backend09`。结论：批准同字节草案的技术语义，待前端本人同摘要确认后由协调正式采纳。此为设计签认，不是实现验收。

草案：`.scratch/technical-design/contract-09-draft.json`；版本 `1.0.5`；本人现场计算 SHA-256：`2eba1980d9e62318edfece692e46c977d9e55191f06c59e4629285f29f66f5fd`。正式旧基线为 `1.0.4`，SHA-256 为 `9b81bdc9561a3e0cb8aaaf0172e0013c6e741f2aea8efe283f64e14cebd1c444`。

本人逐指针比较完整JSON，差异仅 `/info/version`、`/components/schemas/Location/description`，以及该结构 `properties` 下 `pageStart`、`pageEnd`、`lineStart`、`lineEnd`、`titlePath`、`paragraphId`、`structureId`、`continuationIndex`、`tableRowStart`、`tableRowEnd`、`tableColumnStart`、`tableColumnEnd` 的 `description`。无路径、操作、媒体、安全、类型、枚举、必填及结构约束变更；代码语言/代码块字段没有改动。

语义批准：文本规范化与04源行一致；页行1起且端点包含；标题路径含当前标题且跨结构取共同祖先；段落/结构身份必须真实；表格行列0起且表头为0；重复表头不扩大实际数据行范围；不可靠位置不可伪造。`continuationIndex` 明确允许无结构身份纯文本按文件从0稳定片序号，保留08兼容，Markdown/PDF真实结构续片按结构编号，不能冒充段落身份。

09 操作范围建议仅提前已有 `previewVersionFile`；12 保留 `downloadVersionFile`、`listIndexSwitches`、取消和完整历史切换验收，并复用指定版本预览。协调需同步操作映射、适用范围分母及票据，本签认不代替该范围记录。指定版本读取必须使用目标版本媒体与大小，版本不存在/归属错误404、明确已清理410、元数据可用但磁盘异常500，不回退最新正文。

依据：本人读取唯一契约、09双方票、两份预检、08基线文件服务/索引/文件控制器、前端09设计，并与前端直接协商定位语义。固定样例和最小持久化建议见本人 `backend-09-design.md`。本人已用标准库解析并逐字段比较草案、复算摘要；当前默认Python未装 `openapi_spec_validator`，未独立复跑该静态验证，协调已报告静态验证通过，不能冒充本人执行。未运行新行为测试、根Maven或浏览器，业务编码仍冻结；不声称契约运行对齐完成。
