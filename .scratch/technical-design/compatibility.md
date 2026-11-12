# 兼容性核实记录

## 核实范围

以下来自此前官方文档与源码查询，属于历史选型证据，不代表本项目已完成依赖解析、构建或真实调用。当前已确认选择以技术规格为准，精确补丁与组合仍待构建验证。

## Java 与后端框架

- 本次官方发布信息显示 Spring AI `2.0.2` 为稳定版本。[发布记录](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.2)
- Spring AI `2.0.x` 官方说明支持 Spring Boot `4.0.x`、`4.1.x`。[入门文档](https://docs.spring.io/spring-ai/reference/getting-started.html)
- 当前 Spring Boot 系统要求页最低要求 Java 17、Maven 3.6.3；具体兼容上限及页面版本随发布变化，锁定依赖前重新核对。[系统要求](https://docs.spring.io/spring-boot/system-requirements.html)
- Palantir Java Format 当前官方构建配置区分源码库目标与运行时，运行时配置为 Java 21；格式化器运行 JDK 与应用源码目标不是同一概念。[构建配置](https://github.com/palantir/palantir-java-format/blob/develop/build.gradle)、[Java 21 支持](https://github.com/palantir/palantir-java-format#java-21-support)
- Spotless Maven 支持 Palantir Java Format，风格可设为 `PALANTIR`。文档示例中的工具版本不代表最新或本项目锁定版本。[插件文档](https://github.com/diffplug/spotless/tree/main/plugin-maven#palantir-java-format)

已确认 Java 21、Spring AI 2.0.2 与 Spring Boot 4.1.x；锁定补丁后仍须完整构建验证。早期默认路径查询为 JDK 17；规格后续记录已验证 `D:/install/Java/jdk-21.0.13.0.3` 的 Java 21 可运行，本轮未重新核实该环境。

## DashScope

- 嵌入模型支持 OpenAI 兼容调用。[嵌入接口文档](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api)
- `text-embedding-v4` 支持默认 1024 维，也支持 2048、1536、768、512、256、128、64 维；本次官方文档说明单请求最多 10 条，每条最多 8192 个词元。索引任务需遵守模型的请求与输入上限，切分与批处理不能只按字符数控制。
- 后续规格已确认初始评估采用 `text-embedding-v4` 与 1024 维，不代表当前最新或已验证最优。
- 从本次读取的[官方模型目录](https://help.aliyun.com/zh/model-studio/models)未能确认 `qwen3.8-max`；保留用户指定标识，不自动替换，也不据此断言其不存在。
- 接口地址取决于区域和业务空间，配置化管理，不能未经核实固定为某个默认端点。

未读取凭据实际值，未发送资料，未调用收费模型接口。
