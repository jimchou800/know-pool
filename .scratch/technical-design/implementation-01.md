# 技术任务 01 实施与验收记录

## 已验证的实现

正式中文工作台通过相对接口地址初始化会话并读取真实后端状态；三套历史演示保留于 `/prototype.html`。后端仅回环监听，提供来源校验、会话 Cookie、防跨站保护、统一错误、SQLite 元数据与数据目录进程锁。前后端仍为独立模块，由根 Maven 统一验证，后端发行包同源提供前端资源。

唯一契约字节未变：版本 `1.0.0`，摘要 `48f98810322f07e3b8380359f37d43ade7fac221ff056f34f05383c9a0b04fcb`。正式接口范围仅 `initializeSession`、`getSystemStatus`，不是完整产品交付。

## 同一快照证据

- 实现快照：`78b0cbacb3205c09a4997ccf474fb5dbd89d519d3263af7a7e9b728c1cb398ee`。
- 自动机器与中文报告：`contract-reports/20261113T013052Z-5b1c6db1/report.json`、`report.md`。
- 机器报告摘要：`4029af7406fedeacb594a43782dabf6e90a02cb212bd9f85657e8dceaa710fa1`。
- 原始约束 82 项；当前适用约束 63 项全部通过，19 项条件未触发或后续保留，未计为通过。当前七项安全行为通过，完整 95 操作仍有 93 项未实现。
- 适用映射：`scripts/contract/scope-01.json`，前后端分别批准同一摘要，记录于 `docs/agents/api/reviews/scope-01-frontend.json` 和 `scope-01-backend.json`。

## 实际检查

2026-11-13 根目录 `mvn --batch-mode --no-transfer-progress verify` 完整成功，包含固定 Node.js `22.22.3`、pnpm `11.6.0` 冻结安装、前端格式与静态检查、60 项测试与生产构建；后端 Java 21、Spring Boot `4.1.2`、JUnit `5.13.4` 的 10 项测试、ArchUnit、Spotless、SpotBugs、覆盖率与打包；15 项契约工具自检、真实发行包同源 Edge 联调、自动契约报告。

前端行覆盖率 100%、分支覆盖率 98.37%；后端行覆盖率 166/181（91.71%）、分支覆盖率 76/94（80.85%）。前端独立浏览器回归 15 项通过；真实同源联调另核对 Cookie、会话、来源和写保护，输出 `front/artifacts/contract-browser.json`，三宽度截图为 `front/artifacts/live-home-1280.png`、`live-home-1440.png`、`live-home-1920.png`。协调已实际查看 1280 截图，前端分别复核三宽度。

首次根验证在沙箱下载 Node.js 时连接重置，按环境权限规则重试后成功；本机 Java 环境只在终端临时设置，没有写入工程安装路径或个人环境变量名。工具仍受沙箱限制，不将用户口头全访问视为工具已无限权限。

## 交付限制

本轮未调用真实模型、未测试真实 TLS 部署、未发行含运行时的 Windows 压缩包、未验收后续业务。接口静态批准、范围映射批准和实现报告验收分别记录，不能互相代替。代码仅本地保存，不拉取、不推送。

CI 文件已接入根验证，尚未在 GitHub 实际运行；后续补齐浏览器、Vitest 与截图产物归档，不能把本地成功冒称远程工作流成功。
