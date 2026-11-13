# 接口实现证据门禁

本工具对当前已领取的技术任务累积生成实现报告。08 继承已验收的 01 至 07，累计四十操作；本票六项操作中的 `getTask` 已在旧范围内，因此只新增五项。完整契约的 95 个操作保持原分母，范围外操作逐项列为未实现；不将切片结果宣传为全项目完成。08 契约设计签认入口为 `docs/agents/api/reviews/contract-08-frontend.md` 与 `contract-08-backend.md`，正式契约及 `scope-08.json` 须冻结后由两个实际实施代理分别签认同一字节摘要；缺文件或双签时门禁拒绝，工具小包通过不代表本票适用范围已通过。正式契约及适用映射通过 `.gitattributes` 固定换行以保持字节摘要。

先安装固定工具依赖：

```powershell
python -m pip install -r scripts/contract/requirements.txt
python -m unittest discover -s scripts/contract -p 'test_*.py'
python scripts/contract/verify.py --inventory
python scripts/contract/verify.py --snapshot
python scripts/contract/verify.py
```

已安装在 `.runtime/contract-tools` 的同版依赖也可使用。后端测试分别自动生成 `server/target/contract/provider-evidence.json`、`provider-settings-evidence.json`、`provider-knowledge-bases-evidence.json` 和 `provider-indexing-evidence.json`，前端测试生成 `front/artifacts/contract-consumer.json`，这些文件包括同一契约与实现摘要、测试名称、操作标识、实际验证过的约束指针及结果。缺少任一默认提供方文件或跨文件重名、过期均拒绝。不得把读取约束清单后无条件填入测试映射作为实际验证。结构校验仅映射实际值触发的分支；描述行为须追溯到对应真实行为断言，不能由结构遍历代替。提供方须运行真实接口；消费方须观察实际请求与渲染，固定替身不替代最终真实浏览器联调。原始测试结果、请求结构与断言在各自证据中追溯，报告不保存真实会话值或模型凭据。

`run_live.py` 在后端打包后，以独立的 `.runtime/live-checks/` 测试数据目录和临时回环端口启动发行包，调用前端真实浏览器断言与截图脚本，再关闭其启动的应用进程。它不会使用用户真实数据目录或调用模型。浏览器证据输出到 `front/artifacts/contract-browser.json`，作为消费方补充；缺少或过期也会阻断报告。生成器同时核对 Vitest 标准结果 `front/artifacts/vitest.json` 与后端原始测试报告，失败、空测试或跳过测试不能靠自报映射通过。

生成器会校验完整标准契约、设计双签摘要、证据快照与测试结果，并按契约递归计算约束分母。任何缺项或失效均生成失败报告并返回非零状态；不会按手写样例或测试数量缩小分母。对象属性、必填、格式、范围、枚举、未知字段、安全和响应状态独立列项，描述中的行为约束也不得遗漏。不可变机器与中文报告保存在已忽略的 `.scratch/technical-design/contract-reports/<运行标识>/`。

`scope-<任务编号>.json` 单独记录当前累积范围适用性，须有前后端同一摘要独立签认。报告同时保留原始全部约束、当前适用项、条件未触发项与后续全局规则；后两者不计为通过，不从原始分母消失。切片适用项通过不代表全原始约束或全项目完成；只有全部原始约束通过才将该操作计入全项目完整路径覆盖，存在保留项的操作仍列全项目未完成。当前安全规则拆成八个可追溯行为，新增 SSE 的 Cookie 会话、来源及失效会话拒绝、令牌不进 URL 和断线查询原任务，仍要求双方证据；不再接受全局安全的后续 SSE 豁免。真实 TLS 部署不借本次 HTTP 浏览器测试声称完成。

事件订阅另外要求 `x-sse` 下的 `events/phase`、`events/terminal`、`id`、`lastEventIdHeader`、`terminalStates`、`reconnect` 六项语义证据。全局业务规则按直接所属操作计入：`indexSwitch` 对应任务查询、库文件列表和管理检索及结果证据读取；`publication` 对应管理检索及知识库详情；`cost` 对应任务查询及管理检索；`sse` 对应事件订阅及任务查询。每项都须由实际行为断言映射，不因结构解析或共享声明自动通过。

报告通过后，前后端实际代理分别创建 `docs/agents/api/reviews/<运行标识>-frontend.json` 和 `<运行标识>-backend.json`。记录字段为 `role`（分别为 `frontend`、`backend`）、`actor`（各自真实代理标识）、`conclusion`（通过或拒绝）、`contractSha256`、`implementationSha256`、`reportSha256`、`evidence`（中文审阅依据）。这些是机器字段名，审阅内容使用简体中文。生成器与总协调均不得自动补签。

```powershell
python scripts/contract/verify.py --acceptance .scratch/technical-design/contract-reports/<运行标识>/report.json
```

验收命令只核对既有报告与两份签认，不生成新报告，避免签认循环。缺任一方、同一代理代签、失败报告或工作区改变都会拒绝；通过后仍须总协调核对根构建、覆盖率和真实端到端证据。此次工具本身不证明 Windows 发行、真实模型质量或后续票据已完成。
