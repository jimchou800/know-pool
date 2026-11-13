package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.system.api.LocalSessionFilter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalApplicationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<Map<String, Object>> EVIDENCE = new ArrayList<>();
    private static final List<String> SENSITIVE_VALUES = new ArrayList<>();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static ConfigurableApplicationContext context;
    private static Path dataDirectory;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        Files.createDirectories(Path.of("target/test-data"));
        dataDirectory = Files.createTempDirectory(Path.of("target/test-data"), "application-")
                .toAbsolutePath();
        context = SpringApplication.run(
                KnowledgePoolApplication.class,
                "--server.port=0",
                "--know-pool.data-directory=" + dataDirectory,
                "--logging.file.name=target/test-application.log");
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    @Test
    @DisplayName("本机会话和持久状态通过真实接口联通")
    void sessionAndPersistentStatusWorkOverHttp() throws Exception {
        HttpResponse<String> initialization = get("/session", null, null);
        assertThat(initialization.statusCode()).isEqualTo(200);
        JsonNode session = JSON.readTree(initialization.body());
        SENSITIVE_VALUES.add(session.get("csrfToken").asText());
        SENSITIVE_VALUES.add(session.get("sessionId").asText());
        assertThat(fields(session)).containsExactlyInAnyOrder("sessionId", "csrfToken", "expiresAt");
        UUID.fromString(session.get("sessionId").asText());
        assertThat(session.get("csrfToken").isString()).isTrue();
        assertThat(session.get("csrfToken").asText()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(session.get("expiresAt").asText()).endsWith("Z");
        assertThat(session.get("expiresAt").isString()).isTrue();
        assertThat(Instant.parse(session.get("expiresAt").asText())).isAfter(Instant.now());
        String cookie = initialization.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie)
                .contains("KNOW_POOL_SESSION=", "HttpOnly", "SameSite=Strict", "Path=/")
                .doesNotContain("Secure");
        assertThat(initialization.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(session.get("sessionId").isString()).isTrue();
        MockHttpServletRequest secure = new MockHttpServletRequest("GET", "/api/v1/session");
        secure.setSecure(true);
        secure.setScheme("https");
        secure.setLocalPort(8080);
        secure.addHeader("Host", "127.0.0.1:8080");
        MockHttpServletResponse secureResponse = new MockHttpServletResponse();
        new LocalSessionFilter("", JSON).doFilter(secure, secureResponse, (request, response) -> {});
        assertThat(secureResponse.getHeader("Set-Cookie")).contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/");
        assertThat(Files.readString(Path.of("target/test-application.log")))
                .doesNotContain(
                        session.get("csrfToken").asText(),
                        session.get("sessionId").asText());
        record(
                "本机会话初始化",
                "initializeSession",
                initialization,
                List.of(
                        "#/paths/~1session/get/method",
                        "#/paths/~1session/get/path",
                        "#/paths/~1session/get/parameters",
                        "#/paths/~1session/get/security",
                        "#/paths/~1session/get/responses/200",
                        "#/paths/~1session/get/responses/200/headers/Set-Cookie/schema/type",
                        "#/components/schemas/Session/type",
                        "#/components/schemas/Session/required",
                        "#/components/schemas/Session/additionalProperties",
                        "#/components/schemas/Session/properties/sessionId/type",
                        "#/components/schemas/Session/properties/sessionId/format",
                        "#/components/schemas/Session/properties/csrfToken/type",
                        "#/components/schemas/Session/properties/csrfToken/minLength",
                        "#/components/schemas/Session/properties/expiresAt/type",
                        "#/components/schemas/Session/properties/expiresAt/format",
                        "#/components/schemas/Session/properties/expiresAt/pattern",
                        "#/paths/~1session/get/description"));
        record("会话保护属性与日志不泄漏", "initializeSession", initialization, List.of("security.3", "security.4", "security.7"));

        String header = cookie.split(";", 2)[0];
        HttpResponse<String> repeat = get("/session", header, null);
        assertThat(JSON.readTree(repeat.body())).isEqualTo(session);
        HttpResponse<String> status = get("/system/status", header, null);
        assertThat(status.statusCode()).isEqualTo(200);
        JsonNode state = JSON.readTree(status.body());
        assertThat(fields(state))
                .containsExactlyInAnyOrder(
                        "applicationVersion", "startedAt", "writeState", "dataDirectory", "schemaVersion", "warnings");
        assertThat(state.get("applicationVersion").isString()).isTrue();
        assertThat(state.get("applicationVersion").asText()).isEqualTo("0.1.0");
        assertThat(state.get("startedAt").isString()).isTrue();
        assertThat(state.get("startedAt").asText()).endsWith("Z");
        Instant.parse(state.get("startedAt").asText());
        assertThat(state.get("writeState").asText()).isIn("ready", "paused", "migration_failed", "schema_failed");
        assertThat(state.get("writeState").isString()).isTrue();
        assertThat(state.get("dataDirectory").isString()).isTrue();
        assertThat(Path.of(state.get("dataDirectory").asText())).isEqualTo(dataDirectory.toRealPath());
        assertThat(state.get("schemaVersion").isIntegralNumber()).isTrue();
        assertThat(state.get("schemaVersion").longValue()).isBetween(1L, 9007199254740991L);
        assertThat(state.get("warnings").isArray()).isTrue();
        assertThat(state.get("warnings").isEmpty()).isTrue();
        assertThat(Files.exists(dataDirectory.resolve("application.sqlite"))).isTrue();
        List<String> constraints = new ArrayList<>(List.of(
                "#/paths/~1system~1status/get/method",
                "#/paths/~1system~1status/get/path",
                "#/paths/~1system~1status/get/parameters",
                "#/paths/~1system~1status/get/responses/200",
                "#/paths/~1system~1status/get/security",
                "#/components/schemas/SystemStatus/type",
                "#/components/schemas/SystemStatus/required",
                "#/components/schemas/SystemStatus/additionalProperties",
                "#/components/schemas/SystemStatus/properties/schemaVersion/minimum",
                "#/components/schemas/SystemStatus/properties/schemaVersion/maximum",
                "#/components/schemas/SystemStatus/properties/startedAt/format",
                "#/components/schemas/SystemStatus/properties/startedAt/pattern",
                "#/components/schemas/SystemStatus/properties/writeState/enum"));
        for (String field : List.of(
                "applicationVersion", "startedAt", "writeState", "dataDirectory", "schemaVersion", "warnings")) {
            constraints.add("#/components/schemas/SystemStatus/properties/" + field + "/type");
        }
        record("读取真实持久运行状态", "getSystemStatus", status, constraints);
        record("状态读取不泄漏会话令牌", "getSystemStatus", status, List.of("security.3", "security.4", "security.7"));
    }

    @Test
    @DisplayName("会话失效和未声明参数返回契约错误")
    void invalidSessionsAndUnknownParametersReturnContractErrors() throws Exception {
        for (String cookie : List.of("", "KNOW_POOL_SESSION=invalid")) {
            HttpResponse<String> missing = get("/system/status", cookie.isEmpty() ? null : cookie, null);
            assertError(missing, 401, "SESSION_EXPIRED");
            record(
                    "拒绝缺失或失效会话：" + (cookie.isEmpty() ? "缺失" : "失效"),
                    "getSystemStatus",
                    missing,
                    List.of("#/paths/~1system~1status/get/responses/401", "#/paths/~1system~1status/get/security"));
            record("会话保护拒绝：" + (cookie.isEmpty() ? "缺失" : "失效"), "getSystemStatus", missing, List.of("security.5"));
        }
        HttpResponse<String> initialization = get("/session", null, null);
        String cookie =
                initialization.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        for (String path : List.of("/session", "/system/status")) {
            HttpResponse<String> invalid = get(path + "?unknown=1", cookie, null);
            assertError(invalid, 400, "INVALID_REQUEST");
            record(
                    "拒绝未声明查询参数：" + path,
                    path.equals("/session") ? "initializeSession" : "getSystemStatus",
                    invalid,
                    List.of("#/paths/" + (path.equals("/session") ? "~1session" : "~1system~1status")
                            + "/get/responses/400"));
        }
    }

    @Test
    @DisplayName("拒绝外部来源和伪造主机并允许明确开发来源")
    void rejectUntrustedOriginsAndHostsAndAllowConfiguredOrigins() throws Exception {
        for (String path : List.of("/session", "/system/status")) {
            HttpResponse<String> hostile = get(path, null, "https://untrusted.example");
            assertError(hostile, 403, "ORIGIN_REJECTED");
            record(
                    "拒绝不可信来源：" + path,
                    path.equals("/session") ? "initializeSession" : "getSystemStatus",
                    hostile,
                    List.of(
                            "#/paths/" + (path.equals("/session") ? "~1session" : "~1system~1status")
                                    + "/get/responses/403",
                            "#/x-business-constraints/security"));
            assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
            record(
                    "来源白名单保护：" + path,
                    path.equals("/session") ? "initializeSession" : "getSystemStatus",
                    hostile,
                    List.of("security.1", "security.2"));
        }
        assertThat(get("/session", null, "http://127.0.0.1:4173").statusCode()).isEqualTo(200);
        assertThat(get("/session", null, base).statusCode()).isEqualTo(200);
        for (String host : List.of("attacker.example", "127.0.0.1:1")) {
            try (java.net.Socket socket =
                    new java.net.Socket("127.0.0.1", URI.create(base).getPort())) {
                socket.getOutputStream()
                        .write(("GET /api/v1/session HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                String result = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(result).startsWith("HTTP/1.1 403");
                assertThat(result).contains("ORIGIN_REJECTED");
            }
        }
    }

    @Test
    @DisplayName("敏感写入缺少令牌被拒绝且未知路由不暴露内部错误")
    void rejectUnprotectedWritesAndHideUnknownRoutes() throws Exception {
        HttpResponse<String> initialization = get("/session", null, null);
        String cookie =
                initialization.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        HttpResponse<String> rejected = HTTP.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/system/status"))
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertError(rejected, 403, "CSRF_REJECTED");
        String token = JSON.readTree(initialization.body()).get("csrfToken").asText();
        HttpResponse<String> unknown = HTTP.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/not-implemented"))
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", token)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertError(unknown, 404, "NOT_FOUND");
        assertThat(unknown.body()).doesNotContain(dataDirectory.toString(), token);
        HttpResponse<String> wrongToken = HTTP.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/system/status"))
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", "invalid-token")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertError(wrongToken, 403, "CSRF_REJECTED");
        record("写请求防护与路由检查", "getSystemStatus", rejected, List.of("security.6"));
        record("本机会话公共写请求保护", "initializeSession", rejected, List.of("security.5", "security.6"));
    }

    @Test
    @DisplayName("同一数据目录禁止双开且重开保留表结构版本")
    void lockDataDirectoryAndPreserveSchemaAcrossReopen() throws Exception {
        assertThatThrownBy(() -> new ApplicationData(dataDirectory.toString()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("另一应用进程");
        Path other = Files.createTempDirectory(Path.of("target/test-data"), "reopen-");
        try (ApplicationData first = new ApplicationData(other.toString())) {
            assertThat(first.status().schemaVersion()).isEqualTo(2);
        }
        try (ApplicationData reopened = new ApplicationData(other.toString())) {
            assertThat(reopened.status().schemaVersion()).isEqualTo(2);
        }
        assertThatThrownBy(() -> new ApplicationData("\\\\server\\share"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("本机磁盘");
    }

    @Test
    @DisplayName("真实存储故障只返回统一脱敏错误")
    void storageFailuresReturnSanitizedErrors() throws Exception {
        String cookie = get("/session", null, null)
                .headers()
                .firstValue("Set-Cookie")
                .orElseThrow()
                .split(";", 2)[0];
        Path database = dataDirectory.resolve("application.sqlite");
        byte[] original = Files.readAllBytes(database);
        try {
            Files.writeString(database, "用于故障注入的损坏数据库");
            HttpResponse<String> failure = get("/system/status", cookie, null);
            assertError(failure, 500, "INTERNAL_ERROR");
            assertThat(failure.body()).doesNotContain(dataDirectory.toString(), "SQLException", "用于故障");
            record("存储故障返回统一错误", "getSystemStatus", failure, List.of("#/paths/~1system~1status/get/responses/500"));
        } finally {
            Files.write(database, original);
        }
        assertThat(get("/system/status", cookie, null).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("业务包不存在循环依赖")
    void businessPackagesHaveNoCyclesOrCrossDomainDataAccess() {
        SlicesRuleDefinition.slices()
                .matching("cn.knowpool.(*)..")
                .should()
                .beFreeOfCycles()
                .check(new ClassFileImporter().importPackages("cn.knowpool"));
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that()
                .resideOutsideOfPackage("cn.knowpool.datasets..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("cn.knowpool.datasets.data..")
                .check(new ClassFileImporter()
                        .withImportOption(
                                com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("cn.knowpool"));
    }

    @Test
    @Order(10)
    @DisplayName("数据集创建规范化唯一分页和局部修改通过真实接口")
    void datasetCreationNormalizationPaginationAndPatchingWorkOverHttp() throws Exception {
        Credentials credentials = credentials();
        HttpResponse<String> empty = call("GET", "/datasets", null, credentials);
        verified("空数据集列表", "listDatasets", empty, null);
        assertThat(JSON.readTree(empty.body()).get("items").isEmpty()).isTrue();
        String body = "{\"name\":\"  Caf\u0065\u0301  \",\"description\":\"资料说明\"}";
        HttpResponse<String> created = call("POST", "/datasets", body, credentials);
        verified("创建并规范化数据集", "createDataset", created, body);
        JsonNode dataset = JSON.readTree(created.body());
        assertThat(dataset.get("name").asText()).isEqualTo("Café");
        assertThat(dataset.get("description").asText()).isEqualTo("资料说明");
        String id = dataset.get("id").asText();
        String path = "/datasets/" + id;
        HttpResponse<String> duplicate = call("POST", "/datasets", "{\"name\":\"\u00a0CAFÉ\u00a0\"}", credentials);
        assertError(duplicate, 409, "NAME_EXISTS");
        verified("名称大小写和空白规范化冲突", "createDataset", duplicate, null);
        assertThat(JSON.readTree(duplicate.body()).at("/fieldErrors/0/field").asText())
                .isEqualTo("/name");
        HttpResponse<String> second = call("POST", "/datasets", "{\"name\":\"第二组\"}", credentials);
        verified("创建时省略说明默认空串", "createDataset", second, "{\"name\":\"第二组\"}");
        assertThat(JSON.readTree(second.body()).get("description").asText()).isEmpty();
        HttpResponse<String> firstPage = call("GET", "/datasets?limit=1", null, credentials);
        verified("分页第一项及非空游标", "listDatasets", firstPage, null);
        JsonNode page = JSON.readTree(firstPage.body());
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.at("/items/0/id").asText())
                .isEqualTo(JSON.readTree(second.body()).get("id").asText());
        String cursor = page.get("nextCursor").asText();
        HttpResponse<String> nextPage = call("GET", "/datasets?limit=1&cursor=" + cursor, null, credentials);
        verified("分页末项与空游标", "listDatasets", nextPage, null);
        assertThat(JSON.readTree(nextPage.body()).at("/items/0/id").asText()).isEqualTo(id);
        assertThat(JSON.readTree(nextPage.body()).get("nextCursor").isNull()).isTrue();
        HttpResponse<String> detail = call("GET", path, null, credentials);
        verified("数据集详情字段序列化", "getDataset", detail, null);
        assertThat(JSON.readTree(detail.body())).isEqualTo(dataset);
        HttpResponse<String> renamed = call("PATCH", path, "{\"name\":\" 新名称 \"}", credentials);
        verified("仅改名称保留说明", "updateDataset", renamed, "{\"name\":\" 新名称 \"}");
        JsonNode changed = JSON.readTree(renamed.body());
        assertThat(changed.get("id")).isEqualTo(dataset.get("id"));
        assertThat(changed.get("createdAt")).isEqualTo(dataset.get("createdAt"));
        assertThat(Instant.parse(changed.get("updatedAt").asText()))
                .isAfterOrEqualTo(Instant.parse(dataset.get("updatedAt").asText()));
        assertThat(changed.get("description").asText()).isEqualTo("资料说明");
        HttpResponse<String> collision =
                call("PATCH", path, "{\"name\":\"第二组\",\"description\":\"不应写入\"}", credentials);
        assertError(collision, 409, "NAME_EXISTS");
        verified("修改冲突保持原对象", "updateDataset", collision, null);
        assertThat(JSON.readTree(call("GET", path, null, credentials).body())).isEqualTo(changed);
        HttpResponse<String> cleared = call("PATCH", path, "{\"description\":\"\"}", credentials);
        verified("省略名称且空串清除说明", "updateDataset", cleared, "{\"description\":\"\"}");
        assertThat(JSON.readTree(cleared.body()).get("name").asText()).isEqualTo("新名称");
        assertThat(JSON.readTree(cleared.body()).get("description").asText()).isEmpty();
        HttpResponse<String> maximum = call("GET", "/datasets?limit=100", null, credentials);
        verified("最大分页数量", "listDatasets", maximum, null);
    }

    @Test
    @Order(20)
    @DisplayName("数据集拒绝非法输入并保留字段错误")
    void rejectInvalidDatasetInputsWithFieldErrors() throws Exception {
        Credentials credentials = credentials();
        String created =
                call("POST", "/datasets", "{\"name\":\"输入校验专用\"}", credentials).body();
        String path = "/datasets/" + JSON.readTree(created).get("id").asText();
        List<String> invalid = List.of(
                "null",
                "[]",
                "{",
                "{\"name\":null}",
                "{\"name\":1}",
                "{\"name\":true}",
                "{\"name\":[]}",
                "{\"name\":\"\"}",
                "{\"name\":\"  \\t\"}",
                "{\"name\":\"甲\",\"description\":null}",
                "{\"name\":\"甲\",\"description\":3}",
                "{\"name\":\"甲\",\"extra\":0}",
                "{\"name\":\"" + "甲".repeat(121) + "\"}",
                "{\"name\":\"甲\",\"description\":\"" + "😀".repeat(2001) + "\"}");
        for (String method : List.of("POST", "PATCH")) {
            String operation = method.equals("POST") ? "createDataset" : "updateDataset";
            for (int i = 0; i < invalid.size(); i++) {
                HttpResponse<String> response =
                        call(method, method.equals("POST") ? "/datasets" : path, invalid.get(i), credentials);
                assertError(response, 400, "INVALID_REQUEST");
                verified("非法字段边界" + method + i, operation, response, null);
            }
            HttpResponse<String> empty = call(method, method.equals("POST") ? "/datasets" : path, "{}", credentials);
            assertError(empty, 400, "INVALID_REQUEST");
            verified("缺少创建字段或空修改" + method, operation, empty, null);
            HttpResponse<String> query = call(
                    method,
                    (method.equals("POST") ? "/datasets" : path) + "?unknown=1",
                    "{\"name\":\"无效查询\"}",
                    credentials);
            assertError(query, 400, "INVALID_REQUEST");
        }
        for (String query : List.of(
                "limit=0",
                "limit=101",
                "limit=-1",
                "limit=1.2",
                "limit=abc",
                "limit=2147483648",
                "limit=1&limit=2",
                "cursor=",
                "cursor=" + "a".repeat(2049),
                "cursor=bad",
                "cursor=YQ",
                "cursor=MjAyNi0xMS0xM1QwMDowMDowMFovYmFk",
                "unknown=1")) {
            HttpResponse<String> response = call("GET", "/datasets?" + query, null, credentials);
            assertError(response, 400, "INVALID_REQUEST");
            verified("非法分页参数" + query.substring(0, Math.min(25, query.length())), "listDatasets", response, null);
        }
        for (String method : List.of("GET", "PATCH")) {
            String operation = method.equals("GET") ? "getDataset" : "updateDataset";
            HttpResponse<String> invalidId = call(
                    method, "/datasets/not-a-uuid", method.equals("PATCH") ? "{\"name\":\"甲\"}" : null, credentials);
            assertError(invalidId, 400, "INVALID_REQUEST");
            verified("非法数据集标识" + method, operation, invalidId, null);
            HttpResponse<String> missing = call(
                    method,
                    "/datasets/" + UUID.randomUUID(),
                    method.equals("PATCH") ? "{\"name\":\"甲\"}" : null,
                    credentials);
            assertError(missing, 404, "NOT_FOUND");
            verified("数据集资源不存在" + method, operation, missing, null);
        }
        String boundaryBody = "{\"name\":\"" + "😀".repeat(120) + "\",\"description\":\"" + "😀".repeat(2000) + "\"}";
        HttpResponse<String> boundary = call("POST", "/datasets", boundaryBody, credentials);
        verified("名称说明按Unicode码点合法上限", "createDataset", boundary, boundaryBody);
        HttpResponse<String> patchBoundary = call("PATCH", path, boundaryBody.replace("😀", "🧠"), credentials);
        verified("修改名称说明按Unicode码点合法上限", "updateDataset", patchBoundary, boundaryBody.replace("😀", "🧠"));
    }

    @Test
    @Order(30)
    @DisplayName("数据集四操作统一会话来源保护与存储故障")
    void datasetOperationsEnforceSessionOriginAndStorageFailureHandling() throws Exception {
        Credentials credentials = credentials();
        Path database = dataDirectory.resolve("application.sqlite");
        String id = UUID.randomUUID().toString();
        for (String operation : List.of("listDatasets", "createDataset", "getDataset", "updateDataset")) {
            String method =
                    operation.equals("createDataset") ? "POST" : operation.equals("updateDataset") ? "PATCH" : "GET";
            String path = operation.equals("getDataset") || operation.equals("updateDataset")
                    ? "/datasets/" + id
                    : "/datasets";
            String body = method.equals("GET") ? null : "{\"name\":\"故障测试\"}";
            for (Credentials missing :
                    List.of(new Credentials(null, null), new Credentials("KNOW_POOL_SESSION=invalid", null))) {
                HttpResponse<String> unauthorized = call(method, path, body, missing);
                assertError(unauthorized, 401, "SESSION_EXPIRED");
                verified(
                        "数据集会话资格" + operation + (missing.cookie() == null ? "缺失" : "失效"),
                        operation,
                        unauthorized,
                        null);
            }
            HttpRequest.Builder source =
                    request(method, path, body, credentials).header("Origin", "https://attacker.example");
            HttpResponse<String> hostile = HTTP.send(source.build(), HttpResponse.BodyHandlers.ofString());
            assertError(hostile, 403, "ORIGIN_REJECTED");
            verified("数据集来源保护" + operation, operation, hostile, null);
            if (!method.equals("GET")) {
                for (String token : List.of("", "invalid")) {
                    HttpResponse<String> csrf = call(
                            method, path, body, new Credentials(credentials.cookie(), token.isEmpty() ? null : token));
                    assertError(csrf, 403, "CSRF_REJECTED");
                    verified("数据集写保护" + operation + token, operation, csrf, null);
                }
            }
            byte[] original = Files.readAllBytes(database);
            try {
                Files.writeString(database, "损坏数据库故障注入");
                HttpResponse<String> failure = call(method, path, body, credentials);
                assertError(failure, 500, "INTERNAL_ERROR");
                assertThat(failure.body()).doesNotContain(dataDirectory.toString(), "SQLException", "损坏数据库");
                verified("数据集真实存储故障" + operation, operation, failure, null);
            } finally {
                Files.write(database, original);
            }
        }
    }

    @Test
    @DisplayName("旧库一致性备份迁移失败停止及重开持久化")
    void backupSchemaBeforeMigrationAndPreserveDataOnFailureAndReopen() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target/test-data"), "schema-one-");
        String url = "jdbc:sqlite:" + directory.resolve("application.sqlite");
        try (var connection = java.sql.DriverManager.getConnection(url);
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("CREATE TABLE application_metadata(name TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT INTO application_metadata VALUES ('created_at','保留原始元数据')");
            statement.execute("PRAGMA user_version=1");
            try (ApplicationData upgraded = new ApplicationData(directory.toString())) {
                assertThat(upgraded.status().schemaVersion()).isEqualTo(2);
                assertThat(upgraded.<String>read(jdbc -> jdbc.sql("SELECT value FROM application_metadata")
                                .query(String.class)
                                .single()))
                        .isEqualTo("保留原始元数据");
                var service = new cn.knowpool.datasets.DatasetService(
                        new cn.knowpool.datasets.data.DatasetRepository(upgraded));
                service.create("重启保留", "原始说明");
            }
        }
        try (var backups = Files.list(directory.resolve("schema-backups"))) {
            Path backup = backups.findFirst().orElseThrow();
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + backup);
                    var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("PRAGMA integrity_check")) {
                    assertThat(rows.getString(1)).isEqualTo("ok");
                }
                try (var rows = statement.executeQuery("PRAGMA user_version")) {
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
                try (var rows = statement.executeQuery("SELECT value FROM application_metadata")) {
                    assertThat(rows.getString(1)).isEqualTo("保留原始元数据");
                }
            }
        }
        try (ApplicationData reopened = new ApplicationData(directory.toString())) {
            var service =
                    new cn.knowpool.datasets.DatasetService(new cn.knowpool.datasets.data.DatasetRepository(reopened));
            assertThat(service.list(20, null).items().getFirst().name()).isEqualTo("重启保留");
            assertThat(service.list(20, null).items().getFirst().description()).isEqualTo("原始说明");
        }
        Path broken = Files.createTempDirectory(Path.of("target/test-data"), "blocked-backup-");
        try (var connection =
                        java.sql.DriverManager.getConnection("jdbc:sqlite:" + broken.resolve("application.sqlite"));
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=1");
        }
        Files.writeString(broken.resolve("schema-backups"), "备份目标被文件占用");
        assertThatThrownBy(() -> new ApplicationData(broken.toString()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("停止业务写入");
        try (var connection =
                        java.sql.DriverManager.getConnection("jdbc:sqlite:" + broken.resolve("application.sqlite"));
                var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA user_version")) {
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
        Path newer = Files.createTempDirectory(Path.of("target/test-data"), "newer-schema-");
        try (var connection =
                        java.sql.DriverManager.getConnection("jdbc:sqlite:" + newer.resolve("application.sqlite"));
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=3");
        }
        assertThatThrownBy(() -> new ApplicationData(newer.toString()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("停止业务写入");
        Path invalidSchema = Files.createTempDirectory(Path.of("target/test-data"), "failed-migration-");
        String invalidUrl = "jdbc:sqlite:" + invalidSchema.resolve("application.sqlite");
        try (var connection = java.sql.DriverManager.getConnection(invalidUrl);
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE datasets(legacy TEXT)");
            statement.execute("INSERT INTO datasets VALUES ('保留旧数据')");
            statement.execute("PRAGMA user_version=1");
        }
        assertThatThrownBy(() -> new ApplicationData(invalidSchema.toString()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("停止业务写入");
        try (var connection = java.sql.DriverManager.getConnection(invalidUrl);
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            try (var rows = statement.executeQuery("SELECT legacy FROM datasets")) {
                assertThat(rows.getString(1)).isEqualTo("保留旧数据");
            }
            try (var rows =
                    statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='application_metadata'")) {
                assertThat(rows.getInt(1)).isZero();
            }
        }
        try (var backups = Files.list(invalidSchema.resolve("schema-backups"))) {
            assertThat(backups.count()).isEqualTo(1);
        }
    }

    private record Credentials(String cookie, String token) {}

    @Test
    @DisplayName("名称空白集合规范化展开及并发唯一保持一致")
    void normalizeUnicodeNamesAndEnforceConcurrentUniqueness() throws Exception {
        Credentials credentials = credentials();
        String body = "{\"name\":\"\u0085\u00a0\u3000空白边界\u0085\u3000\"}";
        HttpResponse<String> trimmed = call("POST", "/datasets", body, credentials);
        assertThat(trimmed.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(trimmed.body()).get("name").asText()).isEqualTo("空白边界");
        for (String preserved : List.of("\ufeff", "\u001c")) {
            String encoded = JSON.writeValueAsString(Map.of("name", preserved + "保留字符" + preserved));
            HttpResponse<String> response = call("POST", "/datasets", encoded, credentials);
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(JSON.readTree(response.body()).get("name").asText()).isEqualTo(preserved + "保留字符" + preserved);
        }
        for (String name : List.of("\u0085", "\u0344".repeat(120))) {
            HttpResponse<String> rejected =
                    call("POST", "/datasets", JSON.writeValueAsString(Map.of("name", name)), credentials);
            assertError(rejected, 400, "INVALID_REQUEST");
            assertThat(JSON.readTree(rejected.body()).at("/fieldErrors/0/field").asText())
                    .isEqualTo("/name");
        }
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> call("POST", "/datasets", "{\"name\":\"并发唯一\"}", credentials));
            var second = executor.submit(() -> call("POST", "/datasets", "{\"name\":\" 并发唯一 \"}", credentials));
            assertThat(List.of(first.get().statusCode(), second.get().statusCode()))
                    .containsExactlyInAnyOrder(201, 409);
        }
    }

    private static Credentials credentials() throws Exception {
        HttpResponse<String> response = get("/session", null, null);
        JsonNode session = JSON.readTree(response.body());
        SENSITIVE_VALUES.add(session.get("sessionId").asText());
        SENSITIVE_VALUES.add(session.get("csrfToken").asText());
        return new Credentials(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                session.get("csrfToken").asText());
    }

    private static HttpRequest.Builder request(String method, String path, String body, Credentials credentials) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/api/v1" + path))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (credentials.cookie() != null) builder.header("Cookie", credentials.cookie());
        if (credentials.token() != null) builder.header("X-CSRF-Token", credentials.token());
        if (body != null) builder.header("Content-Type", "application/json");
        return builder;
    }

    private static HttpResponse<String> call(String method, String path, String body, Credentials credentials)
            throws Exception {
        return HTTP.send(request(method, path, body, credentials).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void verified(String name, String operation, HttpResponse<String> response, String validBody)
            throws Exception {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."))
                .toAbsolutePath()
                .normalize();
        JsonNode contract =
                JSON.readTree(root.resolve("docs/agents/api/openapi.yaml").toFile());
        String path = operation.equals("getDataset") || operation.equals("updateDataset")
                ? "/datasets/{datasetId}"
                : "/datasets";
        String method =
                operation.equals("createDataset") ? "post" : operation.equals("updateDataset") ? "patch" : "get";
        String pointer = "#/paths/" + path.replace("/", "~1") + "/" + method;
        JsonNode definition = contract.at(pointer.substring(1));
        List<String> constraints = new ArrayList<>();
        assertThat(response.request().method()).isEqualTo(method.toUpperCase(java.util.Locale.ROOT));
        assertThat(response.request().uri().getPath()).startsWith("/api/v1/datasets");
        constraints.addAll(List.of(pointer + "/method", pointer + "/path", pointer + "/parameters"));
        int expected = validBody != null && operation.equals("createDataset")
                ? 201
                : validBody != null || response.statusCode() < 400 ? 200 : response.statusCode();
        assertThat(response.statusCode()).isEqualTo(expected);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        constraints.add(pointer + "/responses/" + expected);
        JsonNode outputDefinition = definition.at("/responses/" + expected);
        if (outputDefinition.has("$ref"))
            outputDefinition = contract.at(outputDefinition.get("$ref").asText().substring(1));
        validateSchema(
                contract,
                outputDefinition.at("/content/application~1json/schema"),
                "",
                JSON.readTree(response.body()),
                constraints);
        if (expected < 400) {
            assertThat(response.request().headers().firstValue("Cookie")).isPresent();
            constraints.add(pointer + "/security");
            int index = 0;
            for (JsonNode parameter : definition.get("parameters")) {
                String parameterPointer = pointer + "/parameters/" + index++;
                if (parameter.has("$ref")) {
                    parameterPointer = parameter.get("$ref").asText();
                    parameter = contract.at(parameterPointer.substring(1));
                }
                String parameterName = parameter.get("name").asText();
                String value = null;
                if (parameter.get("in").asText().equals("header"))
                    value = response.request()
                            .headers()
                            .firstValue(parameterName)
                            .orElse(null);
                if (parameter.get("in").asText().equals("path"))
                    value = response.request().uri().getPath().substring("/api/v1/datasets/".length());
                if (parameter.get("in").asText().equals("query")) {
                    String query = response.request().uri().getQuery();
                    if (query != null)
                        for (String pair : query.split("&"))
                            if (pair.startsWith(parameterName + "="))
                                value = pair.substring(parameterName.length() + 1);
                }
                if (parameter.has("required")) {
                    if (parameter.get("required").booleanValue())
                        assertThat(value).isNotNull();
                    constraints.add(parameterPointer + "/required");
                }
                JsonNode schema = parameter.get("schema");
                if (value == null && schema.has("default")) {
                    value = schema.get("default").asText();
                    assertThat(value).isEqualTo("20");
                    constraints.add(parameterPointer + "/schema/default");
                }
                if (value != null)
                    validateSchema(
                            contract,
                            schema,
                            parameterPointer + "/schema",
                            schema.get("type").asText().equals("integer")
                                    ? JSON.valueToTree(Integer.parseInt(value))
                                    : JSON.valueToTree(value),
                            constraints);
            }
            if (validBody != null) {
                assertThat(response.request().headers().firstValue("Content-Type"))
                        .contains("application/json");
                assertThat(validBody).isNotBlank();
                constraints.addAll(List.of(pointer + "/requestBody", pointer + "/requestBody/required"));
                validateSchema(
                        contract,
                        definition.at("/requestBody/content/application~1json/schema"),
                        "",
                        JSON.readTree(validBody),
                        constraints);
            }
        }
        record(name, operation, response, constraints.stream().distinct().toList());
    }

    private static void validateSchema(
            JsonNode contract, JsonNode schema, String pointer, JsonNode value, List<String> constraints) {
        if (schema.has("$ref")) {
            pointer = schema.get("$ref").asText();
            schema = contract.at(pointer.substring(1));
        }
        if (schema.has("anyOf")) {
            int index = value.isNull() ? 1 : 0;
            validateSchema(contract, schema.get("anyOf").get(index), pointer + "/anyOf/" + index, value, constraints);
            constraints.add(pointer + "/anyOf");
            return;
        }
        String type = schema.get("type").asText();
        assertThat(
                        switch (type) {
                            case "object" -> value.isObject();
                            case "array" -> value.isArray();
                            case "string" -> value.isString();
                            case "integer" -> value.isIntegralNumber();
                            case "boolean" -> value.isBoolean();
                            case "null" -> value.isNull();
                            default -> false;
                        })
                .isTrue();
        constraints.add(pointer + "/type");
        for (String facet : List.of(
                "minLength",
                "maxLength",
                "minimum",
                "maximum",
                "pattern",
                "format",
                "required",
                "additionalProperties",
                "minProperties",
                "enum")) {
            if (!schema.has(facet)) continue;
            JsonNode expected = schema.get(facet);
            switch (facet) {
                case "minLength" ->
                    assertThat(value.asText().codePointCount(0, value.asText().length()))
                            .isGreaterThanOrEqualTo(expected.intValue());
                case "maxLength" ->
                    assertThat(value.asText().codePointCount(0, value.asText().length()))
                            .isLessThanOrEqualTo(expected.intValue());
                case "minimum" -> assertThat(value.longValue()).isGreaterThanOrEqualTo(expected.longValue());
                case "maximum" -> assertThat(value.longValue()).isLessThanOrEqualTo(expected.longValue());
                case "pattern" -> assertThat(value.asText()).containsPattern(expected.asText());
                case "format" -> {
                    if (expected.asText().equals("uuid")) UUID.fromString(value.asText());
                    else Instant.parse(value.asText());
                }
                case "required" ->
                    expected.forEach(
                            field -> assertThat(value.has(field.asText())).isTrue());
                case "additionalProperties" -> {
                    if (!expected.booleanValue())
                        assertThat(schema.get("properties").propertyNames()).containsAll(value.propertyNames());
                }
                case "minProperties" -> assertThat(value.size()).isGreaterThanOrEqualTo(expected.intValue());
                case "enum" -> {
                    List<JsonNode> allowed = new ArrayList<>();
                    expected.forEach(allowed::add);
                    assertThat(value).isIn(allowed);
                }
                default -> throw new AssertionError("未处理的校验约束");
            }
            constraints.add(pointer + "/" + facet);
        }
        if (value.isObject())
            for (String property : value.propertyNames())
                validateSchema(
                        contract,
                        schema.get("properties").get(property),
                        pointer + "/properties/" + property,
                        value.get(property),
                        constraints);
        if (value.isArray())
            for (JsonNode item : value)
                validateSchema(contract, schema.get("items"), pointer + "/items", item, constraints);
    }

    @Test
    @Order(Integer.MAX_VALUE)
    @DisplayName("会话容量通过真实接口有界拒绝")
    void enforceSessionCapacityOverHttp() throws Exception {
        HttpResponse<String> last = null;
        for (int i = 0; i < 129; i++) {
            last = get("/session", null, null);
            if (last.statusCode() == 429) {
                break;
            }
            assertThat(last.statusCode()).isEqualTo(200);
        }
        assertError(last, 429, "QUEUE_FULL");
        record("会话容量有界拒绝", "initializeSession", last, List.of("#/paths/~1session/get/responses/429"));
    }

    private static HttpResponse<String> get(String path, String cookie, String origin) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(base + "/api/v1" + path)).GET();
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Set<String> fields(JsonNode object) {
        return Set.copyOf(object.propertyNames());
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode error = JSON.readTree(response.body());
        assertThat(fields(error)).containsExactlyInAnyOrder("requestId", "code", "message", "fieldErrors", "retryable");
        assertThat(error.get("requestId").isString()).isTrue();
        UUID.fromString(error.get("requestId").asText());
        assertThat(error.get("code").isString()).isTrue();
        assertThat(error.get("code").asText()).isEqualTo(code);
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."))
                .toAbsolutePath()
                .normalize();
        JsonNode enumeration = JSON.readTree(
                        root.resolve("docs/agents/api/openapi.yaml").toFile())
                .at("/components/schemas/Error/properties/code/enum");
        List<String> allowed = new ArrayList<>();
        enumeration.forEach(value -> allowed.add(value.asText()));
        assertThat(error.get("code").asText()).isIn(allowed);
        assertThat(error.get("message").isString()).isTrue();
        assertThat(error.get("message").asText()).containsPattern("[\u4e00-\u9fff]");
        assertThat(error.get("fieldErrors").isArray()).isTrue();
        for (JsonNode field : error.get("fieldErrors")) {
            assertThat(fields(field)).containsExactlyInAnyOrder("field", "code", "message");
            for (String property : List.of("field", "code", "message")) {
                assertThat(field.get(property).isString()).isTrue();
            }
            assertThat(field.get("message").asText()).containsPattern("[\u4e00-\u9fff]");
        }
        assertThat(error.get("retryable").isBoolean()).isTrue();
        assertThat(error.get("retryable").booleanValue()).isEqualTo(status == 429 || status >= 500);
    }

    private static void record(
            String name, String operation, HttpResponse<String> response, List<String> constraintIds) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("name", name);
        evidence.put("passed", true);
        evidence.put("operationIds", List.of(operation));
        List<String> constraints = new ArrayList<>(constraintIds);
        if (response.statusCode() >= 400) {
            constraints.addAll(List.of(
                    "#/components/schemas/Error/type",
                    "#/components/schemas/Error/required",
                    "#/components/schemas/Error/additionalProperties",
                    "#/components/schemas/Error/properties/requestId/type",
                    "#/components/schemas/Error/properties/requestId/format",
                    "#/components/schemas/Error/properties/code/type",
                    "#/components/schemas/Error/properties/code/enum",
                    "#/components/schemas/Error/properties/message/type",
                    "#/components/schemas/Error/properties/fieldErrors/type",
                    "#/components/schemas/Error/properties/retryable/type"));
        }
        evidence.put("constraintIds", constraints.stream().distinct().toList());
        evidence.put(
                "request",
                Map.of(
                        "method",
                        response.request().method(),
                        "path",
                        response.request().uri().getPath()));
        evidence.put(
                "response",
                Map.of(
                        "status",
                        response.statusCode(),
                        "contentType",
                        response.headers().firstValue("Content-Type").orElse("")));
        evidence.put("assertions", List.of("真实 HTTP 响应在内存中逐字段验证；会话 Cookie 和保护令牌不写入报告。"));
        EVIDENCE.add(evidence);
    }

    @AfterAll
    static void report() throws Exception {
        if (context == null) {
            return;
        }
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."))
                .toAbsolutePath()
                .normalize();
        Process snapshot = new ProcessBuilder("python", "scripts/contract/verify.py", "--snapshot")
                .directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        String hash = new String(snapshot.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(snapshot.waitFor()).isZero();
        List<Map<String, String>> routes = new ArrayList<>();
        context.getBean(RequestMappingHandlerMapping.class)
                .getHandlerMethods()
                .keySet()
                .forEach(mapping -> {
                    for (String pattern : mapping.getPatternValues()) {
                        if (pattern.startsWith("/api/v1/")) {
                            mapping.getMethodsCondition()
                                    .getMethods()
                                    .forEach(method -> routes.add(Map.of("method", method.name(), "path", pattern)));
                        }
                    }
                });
        String contractHash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(root.resolve("docs/agents/api/openapi.yaml"))));
        Path output = Path.of("target/contract/provider-evidence.json");
        Files.createDirectories(output.getParent());
        Map<String, Object> report = Map.of(
                "contractSha256", contractHash, "implementationSha256", hash, "routes", routes, "tests", EVIDENCE);
        String serialized = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        for (String secret : SENSITIVE_VALUES) {
            assertThat(serialized).doesNotContain(secret);
            assertThat(Files.readString(Path.of("target/test-application.log"))).doesNotContain(secret);
        }
        Files.writeString(output, serialized, StandardCharsets.UTF_8);
        context.close();
    }
}
