# 个人知识管理应用

导入PDF、Markdown和纯文本资料，以数据集管理文件、知识库独立配置索引，提供带原文依据的检索与问答。首版为Windows x64单用户本机网页应用，不提供应用内笔记编辑、扫描识别或多租户。

## 当前状态

01—08有历史本地验收记录；09—24的实现已合入 `integration`，正在统一验证与修复，不能沿用旧快照宣称新代码通过。文档归并已落盘，本轮在根集成工作区合并验证相关任务，并解决 GitHub 持续集成错误；本地检查通过后推送并核查远程结果。正式发行、资源建议值和真实模型效果尚未验收。

当前任务范围、批准和实施记录统一由[技术任务索引](.scratch/technical-design/tickets.md)导航；[前端工作包](.scratch/ui-ux-design/tickets.md)细化同一切片，不另设进度。完成状态以各技术任务正文及其真实证据为准。

## 构建与本地启动

需要Java 21、Maven 3.9.x和Python。前端工具链由Maven固定安装，不依赖个人Node路径；依赖版本以构建文件和锁文件为准。

```powershell
python -m pip install -r scripts/contract/requirements.txt
mvn --batch-mode --no-transfer-progress package '-Dmaven.test.skip=true'
java -jar server/target/server-0.1.0-SNAPSHOT.jar
```

后端同源提供正式前端与接口，默认打开 `http://127.0.0.1:8080/`。源码运行数据默认位于已忽略的 `.runtime/`；不要提交个人资料、数据库或凭据。上述命令说明使用方式，不表示当前版本已通过验证。

按最新要求，工作流执行生产构建与真实同源运行检查，移除契约工具测试和严格契约报告，前后端单元测试及覆盖率默认跳过。运行检查命令为 `python scripts/contract/run_live.py`。所有检查统一在根工作区 `integration` 执行，实际失败及后续证据归入[24实施记录](.scratch/technical-design/implementation-24.md)。

## 前端开发与原型

```powershell
pnpm.cmd --dir front install --frozen-lockfile
pnpm.cmd --dir front dev --port 4173 --strictPort
```

根地址是需连接真实后端的正式工作台，`/prototype.html` 是三套固定样例原型。用户已采纳“紧凑高效”，另两套保留为探索；模拟计时、回答和浏览器演示存储不代表真实业务能力。视觉参数与历史证据见[界面地图](.scratch/ui-ux-design/map.md)，各独立工具命令以 `front/package.json` 为准。

## 发行与模型

包含Java运行时的Windows发行包、启动方式及Chrome/Edge回归见[发行说明](docs/release.md)。Qdrant独立部署；启动后显式配置生成、嵌入及向量服务，无环境变量密钥兜底。数据目录中的模型密钥为明文，接口只回掩码，备份需按个人资料保管。

普通验证使用固定资料及模型替身，不调用真实收费模型。[中文评测](docs/evaluation.md)区分模拟流程和真实效果，实际模型调用须单独授权。

## 文档入口

- [代理约定](AGENTS.md)、[术语](GLOSSARY.md)、[协作](docs/agents/collaboration.md)、[任务跟踪](docs/agents/issue-tracker.md)。
- [技术规格](.scratch/technical-design/spec.md)、[架构](.scratch/technical-design/architecture.md)、[界面规格](.scratch/ui-ux-design/spec.md)。
- [唯一接口与设计基线](docs/agents/api/README.md)、[证据工具](scripts/contract/README.md)。
