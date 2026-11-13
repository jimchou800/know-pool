# 个人知识管理应用

导入 PDF、Markdown 和纯文本资料，通过数据集管理文件，以知识库独立配置索引，提供带原文依据的知识检索和问答。首版为 Windows x64 单用户本机网页应用，不提供应用内笔记编辑、扫描识别、多用户或多租户。

## 当前交付状态

需求与技术访谈已确认。`front/` 已提供三套浅色界面原型，覆盖首页与问答页，可点击切换方案、新建与切换问答、查看模拟处理进度和原文依据抽屉。用户已采纳方案 1“紧凑高效”为后续界面设计基线；其余两套保留为探索记录。

演示原型使用固定样例，不调用真实模型或后端。技术任务 01 的正式工作台、本机会话、安全及统一构建，02 的数据集管理、03 的单份文本上传、预览与原格式下载、04 的安全文档预览与定位及 05 的批量上传、重命名与版本分页已通过端到端验收，见[01 实施记录](.scratch/technical-design/implementation-01.md)、[02 实施记录](.scratch/technical-design/implementation-02.md)、[03 实施记录](.scratch/technical-design/implementation-03.md)、[04 实施记录](.scratch/technical-design/implementation-04.md)与[05 实施记录](.scratch/technical-design/implementation-05.md)。06 的生成、嵌入与向量配置及显式连接测试也已通过同快照双签验收，见[06 实施记录](.scratch/technical-design/implementation-06.md)。07 的空库创建、配置快照、来源关联与待处理文件也已通过同快照验收，见[07 实施记录](.scratch/technical-design/implementation-07.md)。08 的纯文本索引、事件恢复与单库管理检索也已通过同快照双签验收，见[08 实施记录](.scratch/technical-design/implementation-08.md)。其余业务尚未验收；后续变更须重新验证，不沿用旧快照结论。

01 至 08 按单票完成领域实现、完整根验证与独立验收后集成到 `integration` 并推送 GitHub 持续集成，实际结果见对应运行与实施记录。已核对并清理历史任务分支和工作树，仅保留当前任务的活跃工作树。09 已由两领域领取技术设计及契约双审，尚未开始业务编码；后续继续逐票验收、集成并及时清理，不积累多票后一次合并。

## 运行界面原型

本次验证环境为 Windows、Node.js `22.22.3`、pnpm `11.6.0`。浏览器自动测试使用本机已安装的 Microsoft Edge，不需要下载测试浏览器。

在仓库根目录通过 PowerShell 执行：

```powershell
pnpm.cmd --dir front install --frozen-lockfile
pnpm.cmd --dir front dev --port 4173 --strictPort
```

第一条命令按锁文件安装依赖，镜像固定为 `https://registry.npmmirror.com/`；第二条启动仅监听本机的开发服务。打开 <http://127.0.0.1:4173/prototype.html>，通过顶部按钮比较“紧凑高效、宽松简洁、阅读友好”三套方案。根地址进入正式工作台，需连接真实后端才能显示已连接状态。

原型将已提交的演示会话保存在浏览器本地存储；未提问窗口不保存。刷新时不会自动重发模拟任务，未完成记录显示中断。这里只保存演示输入，不应输入真实敏感资料。固定样例回答不代表模型对任意输入的实际回答。

## 原型检查

```powershell
pnpm.cmd --dir front lint
pnpm.cmd --dir front format:check
pnpm.cmd --dir front test:coverage
pnpm.cmd --dir front build
pnpm.cmd --dir front exec playwright test
```

以上依次检查代码与样式、格式、单元与组件行为及覆盖率、生产构建、实际浏览器交互。浏览器测试自动启动本机开发服务；也可复用已经运行的 `4173` 端口服务。覆盖率统计排除启动入口 `src/main.js`，原型行与分支覆盖率门槛均为 80%。这些检查仅针对原型，不代表正式接口契约、后端或全项目门禁通过。

方案说明见 [视觉设计记录](front/DESIGN.md)，检查结果与截图见[原型验证记录](.scratch/ui-ux-design/verification.md)。

## 正式工程验证与本地启动

构建要求 Java 21、Maven 3.9.x、Python；当前终端的标准 `JAVA_HOME` 或 Java 命令须指向 Java 21。前端工具链由 Maven 固定安装到已忽略的构建目录，不依赖个人安装路径；独立前端开发可使用本机同版本 Node.js 与 pnpm。

```powershell
python -m pip install -r scripts/contract/requirements.txt
mvn --batch-mode --no-transfer-progress verify
java -jar server/target/server-0.1.0-SNAPSHOT.jar
```

01 至 07 已实际通过根验证，后续工作区变更须重新执行。后端同源提供正式前端与接口，默认地址 <http://127.0.0.1:8080/>；原型位于 `/prototype.html`。开发数据和日志保存在已忽略的 `.runtime/`，不提交个人资料、数据库或凭据。

根验证包含格式、静态检查、前后端测试与覆盖率、生产构建、接口证据门禁及真实同源 Edge 联调；使用独立临时数据目录，结束关闭自建进程。自动报告和截图已忽略，双方独立签认在报告生成后执行。完整 Windows 运行时发行包与远程 GitHub 工作流尚未验收。

Maven 职责分为四层：根 POM 管理公共版本、工具版本门禁及模块聚合，`front/pom.xml` 执行前端工具链，`server/pom.xml` 执行后端检查与发行包装配，`verification/pom.xml` 在前后端产物就绪后运行联调和契约检查。全量检查仍从根目录执行上述 `mvn verify`。

后端装配依赖前端构建结果，单独选择后端模块时从根目录使用 `mvn -pl server -am verify`，让反应堆先构建所需前端模块；该命令只执行领域门禁，最终联调与契约报告仍须运行根 `mvn verify`。

## 阅读入口

- [前端规格契约](.scratch/ui-ux-design/spec.md)：统一前端业务、已采纳视觉、交互状态、接口消费、验收与后续桌面封装边界，映射已有技术切片。
- [完整技术规格](.scratch/technical-design/spec.md)与[现行架构索引](.scratch/technical-design/architecture.md)。
- [领域术语](GLOSSARY.md)与[代理入口](AGENTS.md)。
- [前后端派活](docs/agents/collaboration.md)、[工程门禁](docs/agents/engineering.md)、[接口协作规范](docs/agents/api/README.md)。
- [已批准实现任务](.scratch/technical-design/tickets.md)：24 个任务已逐项发布，01 至 07 已验收；[拆分基线](.scratch/technical-design/tickets-draft.md)保留批准依据。
- [实施待办与次日入口](.scratch/technical-design/implementation-todos.md)、[契约实现校验工具](scripts/contract/README.md)。

接口唯一契约为 `docs/agents/api/openapi.yaml`，完整设计、静态检查与双方独立签认见[接口开发共识](docs/agents/api/reviews/baseline.md)。设计批准不代表全部业务已实现；01 已接通运行期自动报告，后续逐票扩展。前端角色文件为 `.codex/agents/fornt-agent.md`，后端为 `.codex/agents/backend.md`；派发时显式加载角色，不自动注册为工具代理。技术细节由两代理协商，业务不确定项先记待办。

## 后续工程要求

引入源码时在本文件补充经过实际验证的运行环境、固定版本、依赖安装、前后端独立开发、统一验证和发行命令。普通测试使用固定资料和模拟模型，真实收费评测单独授权。运行数据使用仓库外目录，开发可用已忽略的 `.runtime/`；凭据不提交，明文配置与备份边界按规格执行。
