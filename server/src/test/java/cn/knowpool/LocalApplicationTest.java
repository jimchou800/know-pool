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
            assertThat(first.status().schemaVersion()).isEqualTo(3);
        }
        try (ApplicationData reopened = new ApplicationData(other.toString())) {
            assertThat(reopened.status().schemaVersion()).isEqualTo(3);
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
                assertThat(upgraded.status().schemaVersion()).isEqualTo(3);
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
            statement.execute("PRAGMA user_version=4");
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

    private record UploadPart(String name, String mediaType, byte[] bytes) {}

    private static HttpResponse<String> upload(String datasetId, Credentials credentials, UploadPart... parts)
            throws Exception {
        String boundary = "fixed-upload-boundary";
        var output = new java.io.ByteArrayOutputStream();
        for (UploadPart part : parts) {
            output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                            + part.name() + "\"\r\nContent-Type: " + part.mediaType() + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.write(part.bytes());
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/datasets/" + datasetId + "/files:upload"))
                        .header("Cookie", credentials.cookie())
                        .header("X-CSRF-Token", credentials.token())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        verified("真实多部分上传" + parts.length, "uploadFiles", response, null);
        if (response.statusCode() == 200) {
            assertThat(parts.length).isBetween(1, 1000);
            JsonNode items = JSON.readTree(response.body()).get("items");
            assertThat(items.size()).isEqualTo(parts.length);
            for (int index = 0; index < parts.length; index++) {
                assertThat(items.get(index).get("index").intValue()).isEqualTo(index);
                assertThat(items.get(index).get("indexTaskIds").size()).isZero();
            }
            assertThat(response.request().headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("multipart/form-data; boundary=");
            String prefix = "#/paths/~1datasets~1{datasetId}~1files:upload/post/requestBody";
            record(
                    "真实多部分文件数组结构与逐项索引无伪造任务",
                    "uploadFiles",
                    response,
                    List.of(
                            prefix,
                            prefix + "/required",
                            prefix + "/content/multipart~1form-data/schema/type",
                            prefix + "/content/multipart~1form-data/schema/required",
                            prefix + "/content/multipart~1form-data/schema/additionalProperties",
                            prefix + "/content/multipart~1form-data/schema/properties/files/type",
                            prefix + "/content/multipart~1form-data/schema/properties/files/minItems",
                            prefix + "/content/multipart~1form-data/schema/properties/files/maxItems",
                            prefix + "/content/multipart~1form-data/schema/properties/files/items/type",
                            prefix + "/content/multipart~1form-data/schema/properties/files/items/format",
                            "#/paths/~1datasets~1{datasetId}~1files:upload/post/description"));
        }
        return response;
    }

    private static String createDataset(String name, Credentials credentials) throws Exception {
        return JSON.readTree(call("POST", "/datasets", JSON.writeValueAsString(Map.of("name", name)), credentials)
                        .body())
                .get("id")
                .asText();
    }

    @Test
    @Order(41)
    @DisplayName("纯文本列表分页与安全原文预览联通")
    void listFilesAndPreviewOriginalTextWithSafeHeaders() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("原文预览验收", credentials);
        byte[] bytes = "\ufeff固定中文\r\n<script>不执行</script>".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> uploaded = upload(datasetId, credentials, new UploadPart("原文.txt", "text/plain", bytes));
        assertThat(uploaded.statusCode()).isEqualTo(200);
        String fileId = JSON.readTree(uploaded.body()).at("/items/0/file/id").asText();
        HttpResponse<String> page = call("GET", "/datasets/" + datasetId + "/files?limit=1", null, credentials);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(page.body()).at("/items/0/id").asText()).isEqualTo(fileId);
        assertThat(JSON.readTree(page.body()).get("nextCursor").isNull()).isTrue();
        for (String operation : List.of("preview", "download")) {
            HttpResponse<String> content = call("GET", "/files/" + fileId + "/latest/" + operation, null, credentials);
            assertThat(content.statusCode()).isEqualTo(200);
            assertThat(content.body().getBytes(StandardCharsets.UTF_8)).isEqualTo(bytes);
            assertThat(content.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("text/plain");
            assertThat(content.headers().firstValue("Content-Disposition").orElseThrow())
                    .startsWith(operation.equals("preview") ? "inline;" : "attachment;");
            assertThat(content.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(content.headers().firstValue("Cache-Control")).contains("no-store");
            if (operation.equals("preview"))
                assertThat(content.headers()
                                .firstValue("Content-Security-Policy")
                                .orElseThrow())
                        .contains("sandbox", "default-src 'none'");
            HttpResponse<byte[]> binary = HTTP.send(
                    request("GET", "/files/" + fileId + "/latest/" + operation, null, credentials)
                            .header("Range", "bytes=0-0")
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            verifiedBinary(binary, bytes, "text/plain", operation.equals("preview"));
        }
    }

    @Test
    @Order(42)
    @DisplayName("批量中非法文本与路径逐项失败且其他资料继续保存")
    void invalidTextAndPathsFailPerItemWithoutRollingBackOtherFiles() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("逐项校验验收", credentials);
        HttpResponse<String> response = upload(
                datasetId,
                credentials,
                new UploadPart("empty.txt", "text/plain", new byte[0]),
                new UploadPart("../escape.txt", "text/plain", new byte[] {65}),
                new UploadPart("nul.txt", "text/plain", new byte[] {65, 0, 66}),
                new UploadPart("invalid.txt", "text/plain", new byte[] {(byte) 0xc3, 0x28}),
                new UploadPart("wrong.exe", "application/octet-stream", new byte[] {65}),
                new UploadPart("last.txt", "text/plain", "合法正文".getBytes(StandardCharsets.UTF_8)));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode items = JSON.readTree(response.body()).get("items");
        assertThat(items.size()).isEqualTo(6);
        for (int index = 0; index < items.size(); index++) {
            JsonNode item = items.get(index);
            assertThat(item.get("index").intValue()).isEqualTo(index);
            assertThat(item.get("indexTaskIds").isEmpty()).isTrue();
            if (index == 0 || index == 5) {
                assertThat(item.get("outcome").asText()).isEqualTo("created");
                assertThat(item.get("error").isNull()).isTrue();
            } else {
                assertThat(item.get("outcome").asText()).isEqualTo("failed");
                assertThat(item.get("file").isNull()).isTrue();
                assertThat(item.at("/error/code").asText())
                        .isEqualTo(index == 1 ? "INVALID_REQUEST" : "UNSUPPORTED_FILE");
            }
        }
        assertThat(JSON.readTree(call("GET", "/datasets/" + datasetId + "/files", null, credentials)
                                .body())
                        .get("items")
                        .size())
                .isEqualTo(2);
    }

    @Test
    @Order(43)
    @DisplayName("同名未变跳过且变化保留文件身份并生成内容版本")
    void unchangedUploadsSkipAndChangedUploadsKeepIdentityWithNewVersion() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("内容版本验收", credentials);
        UploadPart first = new UploadPart("Version.txt", "text/plain", "第一版".getBytes(StandardCharsets.UTF_8));
        JsonNode created =
                JSON.readTree(upload(datasetId, credentials, first).body()).at("/items/0");
        JsonNode original = created.get("file");
        JsonNode unchanged = JSON.readTree(
                        upload(datasetId, credentials, new UploadPart("VERSION.TXT", "text/plain", first.bytes()))
                                .body())
                .at("/items/0");
        assertThat(unchanged.get("outcome").asText()).isEqualTo("unchanged");
        assertThat(unchanged.get("file")).isEqualTo(original);
        JsonNode replaced = JSON.readTree(upload(
                                datasetId,
                                credentials,
                                new UploadPart("version.txt", "text/plain", "第二版不同字节".getBytes(StandardCharsets.UTF_8)))
                        .body())
                .at("/items/0");
        assertThat(replaced.get("outcome").asText()).isEqualTo("replaced");
        assertThat(replaced.at("/file/id")).isEqualTo(original.get("id"));
        assertThat(replaced.at("/file/createdAt")).isEqualTo(original.get("createdAt"));
        assertThat(replaced.at("/file/latestVersion/id")).isNotEqualTo(original.at("/latestVersion/id"));
        HttpResponse<String> content =
                call("GET", "/files/" + original.get("id").asText() + "/latest/download", null, credentials);
        assertThat(content.body()).isEqualTo("第二版不同字节");
    }

    @Test
    @Order(58)
    @DisplayName("多页中文及动作PDF与危险Markdown保留原格式且图像扫描件独立拒绝")
    void preserveChinesePdfAndUnsafeMarkdownBytesWhileRejectingImageOnlyPdf() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("安全文档预览验收", credentials);
        byte[] pdf;
        byte[] scanned;
        try (var input = LocalApplicationTest.class.getResourceAsStream("/documents/chinese-two-pages-actions.pdf")) {
            assertThat(input).isNotNull();
            pdf = input.readAllBytes();
        }
        try (var input = LocalApplicationTest.class.getResourceAsStream("/documents/image-only.pdf")) {
            assertThat(input).isNotNull();
            scanned = input.readAllBytes();
        }
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
                    .contains("第一页：中文资料预览", "第二页：保留原文与页码");
            assertThat(document.getDocumentCatalog().getOpenAction()).isNotNull();
            assertThat(document.getPage(0).getAnnotations()).hasSize(1);
        }
        try (var document = org.apache.pdfbox.Loader.loadPDF(scanned)) {
            assertThat(document.getPage(0).getResources().getXObjectNames()).isNotEmpty();
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
                    .isBlank();
        }
        byte[] markdown = ("\ufeff# 中文原文\r\n\r\n<script>globalThis.documentAttack=true</script>\r"
                        + "[危险链接](javascript:alert(1))\n![外部图片](https://example.invalid/image.png)\n"
                        + "<img src=x onerror=alert(1)>\n\n")
                .getBytes(StandardCharsets.UTF_8);
        JsonNode items = JSON.readTree(upload(
                                datasetId,
                                credentials,
                                new UploadPart("中文两页与动作.pdf", "application/pdf", pdf),
                                new UploadPart("危险内容与原始换行.md", "text/markdown", markdown),
                                new UploadPart("图像扫描件.pdf", "application/pdf", scanned))
                        .body())
                .get("items");
        for (int index = 0; index < 2; index++) {
            assertThat(items.get(index).get("outcome").asText()).isEqualTo("created");
            String fileId = items.get(index).at("/file/id").asText();
            for (String route : List.of("preview", "download")) {
                HttpResponse<byte[]> response = HTTP.send(
                        request("GET", "/files/" + fileId + "/latest/" + route, null, credentials)
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                verifiedBinary(
                        response,
                        index == 0 ? pdf : markdown,
                        index == 0 ? "application/pdf" : "text/markdown",
                        route.equals("preview"));
            }
        }
        assertThat(items.get(2).get("outcome").asText()).isEqualTo("failed");
        assertThat(items.get(2).get("file").isNull()).isTrue();
        assertThat(items.get(2).at("/error/code").asText()).isEqualTo("UNSUPPORTED_FILE");
        assertThat(JSON.readTree(call("GET", "/datasets/" + datasetId + "/files", null, credentials)
                                .body())
                        .get("items"))
                .hasSize(2);
    }

    private static byte[] textPdf() throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(
                        new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
                        12);
                stream.newLineAtOffset(20, 700);
                stream.showText("Fixed fixture text");
                stream.endText();
            }
            var output = new java.io.ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    @Test
    @Order(44)
    @DisplayName("Markdown与文字PDF按原媒体和原字节保存下载")
    void markdownAndTextPdfPreserveMediaAndOriginalBytes() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("三媒体验收", credentials);
        byte[] markdown = "# 固定标题\n\n[链接](https://example.invalid)\n".getBytes(StandardCharsets.UTF_8);
        byte[] pdf = textPdf();
        HttpResponse<String> response = upload(
                datasetId,
                credentials,
                new UploadPart("资料.md", "text/markdown", markdown),
                new UploadPart("资料.pdf", "application/pdf", pdf));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode items = JSON.readTree(response.body()).get("items");
        for (int index = 0; index < 2; index++) {
            JsonNode item = items.get(index);
            assertThat(item.get("outcome").asText()).isEqualTo("created");
            String id = item.at("/file/id").asText();
            for (String operation : List.of("preview", "download")) {
                HttpResponse<byte[]> result = HTTP.send(
                        request("GET", "/files/" + id + "/latest/" + operation, null, credentials)
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                verifiedBinary(
                        result,
                        index == 0 ? markdown : pdf,
                        index == 0 ? "text/markdown" : "application/pdf",
                        operation.equals("preview"));
            }
        }
    }

    @Test
    @Order(45)
    @DisplayName("同批规范化重名组全部失败且其他文件独立保存")
    void duplicateFilenameGroupsFailTogetherAndOtherFilesContinue() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("同批冲突验收", credentials);
        JsonNode result = JSON.readTree(upload(
                        datasetId,
                        credentials,
                        new UploadPart("Café.txt", "text/plain", new byte[] {65}),
                        new UploadPart("Cafe\u0301.TXT", "text/plain", new byte[] {66}),
                        new UploadPart("other.txt", "text/plain", new byte[] {65}))
                .body());
        for (int index = 0; index < 2; index++) {
            assertThat(result.at("/items/" + index + "/outcome").asText()).isEqualTo("failed");
            assertThat(result.at("/items/" + index + "/error/code").asText()).isEqualTo("CONFLICT");
        }
        assertThat(result.at("/items/2/outcome").asText()).isEqualTo("created");
        JsonNode list = JSON.readTree(call("GET", "/datasets/" + datasetId + "/files", null, credentials)
                .body());
        assertThat(list.get("items").size()).isEqualTo(1);
        assertThat(list.at("/items/0/name").asText()).isEqualTo("other.txt");
    }

    @Test
    @Order(46)
    @DisplayName("并发同名上传保留一个文件身份且数据集计数反映最新副本")
    void concurrentUploadsKeepOneIdentityAndCorrectDatasetCounts() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("并发资料验收", credentials);
        byte[] first = "并发第一份".getBytes(StandardCharsets.UTF_8);
        byte[] second = "并发第二份不同长度".getBytes(StandardCharsets.UTF_8);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(
                    () -> upload(datasetId, credentials, new UploadPart("race.txt", "text/plain", first)));
            var b = executor.submit(
                    () -> upload(datasetId, credentials, new UploadPart("RACE.TXT", "text/plain", second)));
            JsonNode one = JSON.readTree(a.get().body()).at("/items/0");
            JsonNode two = JSON.readTree(b.get().body()).at("/items/0");
            assertThat(List.of(one.get("outcome").asText(), two.get("outcome").asText()))
                    .containsExactlyInAnyOrder("created", "replaced");
            assertThat(one.at("/file/id")).isEqualTo(two.at("/file/id"));
            String id = one.at("/file/id").asText();
            HttpResponse<byte[]> downloaded = HTTP.send(
                    request("GET", "/files/" + id + "/latest/download", null, credentials)
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(List.of(new String(first, StandardCharsets.UTF_8), new String(second, StandardCharsets.UTF_8)))
                    .contains(new String(downloaded.body(), StandardCharsets.UTF_8));
            JsonNode dataset = JSON.readTree(
                    call("GET", "/datasets/" + datasetId, null, credentials).body());
            assertThat(dataset.get("fileCount").longValue()).isEqualTo(1);
            assertThat(dataset.get("totalBytes").longValue()).isEqualTo(downloaded.body().length);
        }
    }

    @Test
    @Order(47)
    @DisplayName("单份超限逐项失败不丢失其他成功项")
    void oversizedFileFailsPerItemAndOtherFilesStillCommit() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("资源边界验收", credentials);
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, (byte) 'a');
        HttpResponse<String> response = upload(
                datasetId,
                credentials,
                new UploadPart("large.txt", "text/plain", oversized),
                new UploadPart("small.txt", "text/plain", new byte[] {65}));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = JSON.readTree(response.body());
        assertThat(result.at("/items/0/outcome").asText()).isEqualTo("failed");
        assertThat(result.at("/items/0/error/code").asText()).isEqualTo("RESOURCE_LIMIT");
        assertThat(result.at("/items/1/outcome").asText()).isEqualTo("created");
        assertThat(JSON.readTree(call("GET", "/datasets/" + datasetId, null, credentials)
                                .body())
                        .get("fileCount")
                        .longValue())
                .isEqualTo(1);
    }

    @Test
    @Order(48)
    @DisplayName("真实容器允许一千文件并在超数量或未知字段时零提交拒绝")
    void realContainerAcceptsThousandPartsAndRejectsInvalidStructureWithoutCommits() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("多部分数量验收", credentials);
        UploadPart[] thousand = new UploadPart[1000];
        java.util.Arrays.fill(thousand, new UploadPart("duplicate.txt", "text/plain", new byte[] {65}));
        HttpResponse<String> accepted = upload(datasetId, credentials, thousand);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(accepted.body()).get("items").size()).isEqualTo(1000);
        UploadPart[] tooMany = java.util.Arrays.copyOf(thousand, 1001);
        tooMany[1000] = new UploadPart("would-create.txt", "text/plain", new byte[] {65});
        HttpResponse<String> rejected = upload(datasetId, credentials, tooMany);
        assertError(rejected, 400, "INVALID_REQUEST");
        UploadPart[] aboveContainerLimit = java.util.Arrays.copyOf(tooMany, 1002);
        aboveContainerLimit[1001] = new UploadPart("also-invalid-count.txt", "text/plain", new byte[] {65});
        assertError(upload(datasetId, credentials, aboveContainerLimit), 400, "INVALID_REQUEST");
        String multipart =
                "--boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"valid.txt\"\r\nContent-Type: text/plain\r\n\r\na\r\n--boundary\r\nContent-Disposition: form-data; name=\"extra\"\r\n\r\nunknown\r\n--boundary--\r\n";
        HttpResponse<String> unknown = HTTP.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/datasets/" + datasetId + "/files:upload"))
                        .header("Cookie", credentials.cookie())
                        .header("X-CSRF-Token", credentials.token())
                        .header("Content-Type", "multipart/form-data; boundary=boundary")
                        .POST(HttpRequest.BodyPublishers.ofString(multipart))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertError(unknown, 400, "INVALID_REQUEST");
        assertThat(JSON.readTree(call("GET", "/datasets/" + datasetId, null, credentials)
                                .body())
                        .get("fileCount")
                        .longValue())
                .isZero();
    }

    @Test
    @Order(60)
    @DisplayName("合法大写数据集标识与小写标识使用同一文件集合")
    void uppercaseDatasetIdentifiersUseCanonicalFileOwnership() throws Exception {
        Credentials credentials = credentials();
        String id = createDataset("数据集标识规范化验收", credentials);
        String uppercase = id.toUpperCase(java.util.Locale.ROOT);
        JsonNode item = JSON.readTree(
                        upload(uppercase, credentials, new UploadPart("canonical.txt", "text/plain", new byte[] {65}))
                                .body())
                .at("/items/0");
        assertThat(item.get("outcome").asText()).isEqualTo("created");
        assertThat(item.at("/file/datasetId").asText()).isEqualTo(id);
        JsonNode lower = JSON.readTree(
                call("GET", "/datasets/" + id + "/files", null, credentials).body());
        JsonNode upper = JSON.readTree(call("GET", "/datasets/" + uppercase + "/files", null, credentials)
                .body());
        assertThat(upper).isEqualTo(lower);
        assertThat(upper.get("items").size()).isEqualTo(1);
    }

    @Test
    @Order(59)
    @DisplayName("应用重启后重新建立会话仍可读取相同文件版本与原字节")
    void reopenApplicationAndReadPersistedFileOverHttp() throws Exception {
        Credentials before = credentials();
        String datasetId = createDataset("重启文件验收", before);
        byte[] original = "持久原文\r\n".getBytes(StandardCharsets.UTF_8);
        JsonNode file = JSON.readTree(upload(datasetId, before, new UploadPart("restart.txt", "text/plain", original))
                        .body())
                .at("/items/0/file");
        context.close();
        context = SpringApplication.run(
                KnowledgePoolApplication.class,
                "--server.port=0",
                "--know-pool.data-directory=" + dataDirectory,
                "--logging.file.name=target/test-application.log");
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        assertError(call("GET", "/files/" + file.get("id").asText(), null, before), 401, "SESSION_EXPIRED");
        Credentials after = credentials();
        assertThat(JSON.readTree(call("GET", "/files/" + file.get("id").asText(), null, after)
                        .body()))
                .isEqualTo(file);
        HttpResponse<byte[]> downloaded = HTTP.send(
                request("GET", "/files/" + file.get("id").asText() + "/latest/download", null, after)
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        verifiedBinary(downloaded, original, "text/plain", false);
    }

    @Test
    @Order(58)
    @DisplayName("可配置文本提取上限按Unicode码点计数且失败保留其他文件")
    void configuredTextLimitCountsUnicodeCodePoints() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target/test-data"), "text-codepoints-")
                .toAbsolutePath();
        String priorBase = base;
        try (var isolated = SpringApplication.run(
                KnowledgePoolApplication.class,
                "--server.port=0",
                "--know-pool.data-directory=" + directory,
                "--know-pool.processing.max-extracted-code-points=3",
                "--logging.file.name=target/test-codepoints.log")) {
            base = "http://127.0.0.1:" + isolated.getEnvironment().getProperty("local.server.port");
            Credentials credentials = credentials();
            String datasetId = createDataset("可配置码点验收", credentials);
            JsonNode items = JSON.readTree(upload(
                                    datasetId,
                                    credentials,
                                    new UploadPart("three.txt", "text/plain", "😀甲乙".getBytes(StandardCharsets.UTF_8)),
                                    new UploadPart(
                                            "four.md", "text/markdown", "😀甲乙丙".getBytes(StandardCharsets.UTF_8)))
                            .body())
                    .get("items");
            assertThat(items.get(0).get("outcome").asText()).isEqualTo("created");
            assertThat(items.get(1).at("/error/code").asText()).isEqualTo("RESOURCE_LIMIT");
            assertThat(JSON.readTree(call("GET", "/datasets/" + datasetId + "/files", null, credentials)
                                    .body())
                            .get("items")
                            .size())
                    .isEqualTo(1);
        } finally {
            base = priorBase;
        }
    }

    @Test
    @Order(57)
    @DisplayName("省略分页数量实际默认为二十且整体超限在任何文件提交前拒绝")
    void defaultPageSizeAndTotalUploadLimitHaveNoPartialCommits() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("默认分页验收", credentials);
        UploadPart[] parts = new UploadPart[21];
        for (int index = 0; index < parts.length; index++)
            parts[index] = new UploadPart("entry-" + index + ".txt", "text/plain", new byte[] {65});
        upload(datasetId, credentials, parts);
        JsonNode implicit = JSON.readTree(call("GET", "/datasets/" + datasetId + "/files", null, credentials)
                .body());
        JsonNode explicit = JSON.readTree(call("GET", "/datasets/" + datasetId + "/files?limit=20", null, credentials)
                .body());
        assertThat(implicit).isEqualTo(explicit);
        assertThat(implicit.get("items").size()).isEqualTo(20);
        assertThat(implicit.get("nextCursor").isString()).isTrue();
        String emptyDataset = createDataset("整体资源拒绝验收", credentials);
        Path oversized = Files.createTempFile(Path.of("target/test-data"), "overall-limit-", ".bin");
        try {
            try (var file = new java.io.RandomAccessFile(oversized.toFile(), "rw")) {
                file.setLength(104857600L);
            }
            String beginning =
                    "--overall\r\nContent-Disposition: form-data; name=\"files\"; filename=\"first.txt\"\r\nContent-Type: text/plain\r\n\r\nA\r\n--overall\r\nContent-Disposition: form-data; name=\"files\"; filename=\"large.txt\"\r\nContent-Type: text/plain\r\n\r\n";
            var publisher = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofString(beginning),
                    HttpRequest.BodyPublishers.ofFile(oversized),
                    HttpRequest.BodyPublishers.ofString("\r\n--overall--\r\n"));
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/datasets/" + emptyDataset + "/files:upload"))
                            .header("Cookie", credentials.cookie())
                            .header("X-CSRF-Token", credentials.token())
                            .header("Content-Type", "multipart/form-data; boundary=overall")
                            .POST(publisher)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertError(response, 413, "RESOURCE_LIMIT");
            verified("整体超限且合法前置文件零提交", "uploadFiles", response, null);
            assertThat(JSON.readTree(call("GET", "/datasets/" + emptyDataset, null, credentials)
                                    .body())
                            .get("fileCount")
                            .longValue())
                    .isZero();
            assertThat(JSON.readTree(call("GET", "/datasets/" + emptyDataset + "/files", null, credentials)
                                    .body())
                            .get("items")
                            .size())
                    .isZero();
        } finally {
            Files.delete(oversized);
        }
    }

    @Test
    @Order(56)
    @DisplayName("五个文件操作逐一校验会话来源资源缺失与真实存储故障")
    void fileOperationsEnforceSecurityAndSanitizeStorageFailures() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("文件安全矩阵", credentials);
        String id = JSON.readTree(
                        upload(datasetId, credentials, new UploadPart("safe.txt", "text/plain", new byte[] {65}))
                                .body())
                .at("/items/0/file/id")
                .asText();
        String multipart =
                "--matrix\r\nContent-Disposition: form-data; name=\"files\"; filename=\"safe.txt\"\r\nContent-Type: text/plain\r\n\r\nA\r\n--matrix--\r\n";
        Path database = dataDirectory.resolve("application.sqlite");
        for (String operation :
                List.of("listDatasetFiles", "uploadFiles", "getFile", "previewLatestFile", "downloadLatestFile")) {
            boolean uploading = operation.equals("uploadFiles");
            String method = uploading ? "POST" : "GET";
            String path =
                    switch (operation) {
                        case "listDatasetFiles" -> "/datasets/" + datasetId + "/files";
                        case "uploadFiles" -> "/datasets/" + datasetId + "/files:upload";
                        case "previewLatestFile" -> "/files/" + id + "/latest/preview";
                        case "downloadLatestFile" -> "/files/" + id + "/latest/download";
                        default -> "/files/" + id;
                    };
            for (Credentials invalid :
                    List.of(new Credentials(null, null), new Credentials("KNOW_POOL_SESSION=invalid", null))) {
                HttpRequest.Builder builder = request(method, path, uploading ? multipart : null, invalid);
                if (uploading) builder.setHeader("Content-Type", "multipart/form-data; boundary=matrix");
                HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                assertError(response, 401, "SESSION_EXPIRED");
                verified("文件会话保护" + operation, operation, response, null);
            }
            HttpRequest.Builder builder = request(method, path, uploading ? multipart : null, credentials)
                    .header("Origin", "https://attacker.example");
            if (uploading) builder.setHeader("Content-Type", "multipart/form-data; boundary=matrix");
            HttpResponse<String> hostile = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertError(hostile, 403, "ORIGIN_REJECTED");
            verified("文件来源保护" + operation, operation, hostile, null);
            String missingPath = path.replace(datasetId, UUID.randomUUID().toString())
                    .replace(id, UUID.randomUUID().toString());
            builder = request(method, missingPath, uploading ? multipart : null, credentials);
            if (uploading) builder.setHeader("Content-Type", "multipart/form-data; boundary=matrix");
            HttpResponse<String> missing = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertError(missing, 404, "NOT_FOUND");
            verified("文件资源缺失" + operation, operation, missing, null);
            byte[] original = Files.readAllBytes(database);
            try {
                Files.writeString(database, "损坏数据库故障注入");
                builder = request(method, path, uploading ? multipart : null, credentials);
                if (uploading) builder.setHeader("Content-Type", "multipart/form-data; boundary=matrix");
                HttpResponse<String> failure = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                assertError(failure, 500, "INTERNAL_ERROR");
                assertThat(failure.body()).doesNotContain(dataDirectory.toString(), "SQLException", "损坏数据库");
                verified("文件存储故障" + operation, operation, failure, null);
            } finally {
                Files.write(database, original);
            }
        }
        HttpResponse<String> csrf = call(
                "POST", "/datasets/" + datasetId + "/files:upload", "{}", new Credentials(credentials.cookie(), null));
        assertError(csrf, 403, "CSRF_REJECTED");
        verified("上传写保护", "uploadFiles", csrf, null);
        HttpResponse<String> unsupported = call("POST", "/datasets/" + datasetId + "/files:upload", "{}", credentials);
        assertError(unsupported, 415, "UNSUPPORTED_FILE");
        verified("上传整体媒体拒绝", "uploadFiles", unsupported, null);
    }

    @Test
    @Order(55)
    @DisplayName("版本提交失败回滚旧元数据并清理本次副本")
    void preservePriorVersionAndCleanCopyWhenDatabaseCommitFails() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("版本事务失败验收", credentials);
        JsonNode original = JSON.readTree(
                        upload(datasetId, credentials, new UploadPart("stable.txt", "text/plain", new byte[] {65}))
                                .body())
                .at("/items/0/file");
        Path versions = dataDirectory.resolve("files/versions");
        long count;
        try (var children = Files.list(versions)) {
            count = children.count();
        }
        try (var connection = java.sql.DriverManager.getConnection(
                        "jdbc:sqlite:" + dataDirectory.resolve("application.sqlite"));
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TRIGGER reject_test_version BEFORE INSERT ON file_versions BEGIN SELECT RAISE(ABORT,'固定故障'); END");
            try {
                JsonNode failed = JSON.readTree(
                                upload(datasetId, credentials, new UploadPart("stable.txt", "text/plain", new byte[] {66
                                        }))
                                        .body())
                        .at("/items/0");
                assertThat(failed.at("/error/code").asText()).isEqualTo("INTERNAL_ERROR");
                assertThat(failed.get("file").isNull()).isTrue();
                assertThat(JSON.readTree(
                                call("GET", "/files/" + original.get("id").asText(), null, credentials)
                                        .body()))
                        .isEqualTo(original);
                assertThat(call("GET", "/files/" + original.get("id").asText() + "/latest/download", null, credentials)
                                .body())
                        .isEqualTo("A");
                try (var children = Files.list(versions)) {
                    assertThat(children.count()).isEqualTo(count);
                }
            } finally {
                statement.execute("DROP TRIGGER reject_test_version");
            }
        }
    }

    @Test
    @Order(54)
    @DisplayName("拒绝被目录链接替换的副本写入路径且不写出数据目录")
    void rejectLinkedStorageDirectoryWithoutExternalWrites() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("链接路径验收", credentials);
        Path versions = dataDirectory.resolve("files/versions");
        Files.createDirectories(versions);
        Path saved = dataDirectory.resolve("files/versions-saved");
        Path external = Files.createTempDirectory(Path.of("target/test-data").toAbsolutePath(), "external-files-");
        Files.move(versions, saved);
        try {
            if (System.getProperty("os.name").startsWith("Windows")) {
                Process junction = new ProcessBuilder(
                                "cmd", "/c", "mklink", "/J", versions.toString(), external.toString())
                        .redirectErrorStream(true)
                        .start();
                junction.getInputStream().readAllBytes();
                assertThat(junction.waitFor()).isZero();
            } else Files.createSymbolicLink(versions, external);
            JsonNode item = JSON.readTree(
                            upload(datasetId, credentials, new UploadPart("blocked.txt", "text/plain", new byte[] {65}))
                                    .body())
                    .at("/items/0");
            assertThat(item.get("outcome").asText()).isEqualTo("failed");
            assertThat(item.at("/error/code").asText()).isEqualTo("INTERNAL_ERROR");
            try (var children = Files.list(external)) {
                assertThat(children.count()).isZero();
            }
        } finally {
            Files.deleteIfExists(versions);
            Files.move(saved, versions);
        }
    }

    @Test
    @Order(53)
    @DisplayName("PDF文字检查保留有效压缩资料且无文字损坏和加密PDF独立失败")
    void rejectUnreadablePdfAndBoundTextInspection() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("PDF解析边界验收", credentials);
        byte[] whitespace;
        byte[] empty;
        byte[] encrypted;
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(
                        new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
                        12);
                content.showText(" ".repeat(100001) + "A");
                content.endText();
            }
            var output = new java.io.ByteArrayOutputStream();
            document.save(output);
            whitespace = output.toByteArray();
        }
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var output = new java.io.ByteArrayOutputStream();
            document.save(output);
            empty = output.toByteArray();
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner", "reader", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            output.reset();
            document.save(output);
            encrypted = output.toByteArray();
        }
        JsonNode items = JSON.readTree(upload(
                                datasetId,
                                credentials,
                                new UploadPart("bounded.pdf", "application/pdf", whitespace),
                                new UploadPart("empty.pdf", "application/pdf", empty),
                                new UploadPart("encrypted.pdf", "application/pdf", encrypted),
                                new UploadPart("broken.pdf", "application/pdf", new byte[] {65}))
                        .body())
                .get("items");
        assertThat(items.get(0).get("outcome").asText()).isEqualTo("created");
        for (int index = 1; index < 4; index++)
            assertThat(items.get(index).at("/error/code").asText()).isEqualTo("UNSUPPORTED_FILE");
    }

    @Test
    @Order(52)
    @DisplayName("跨两代迁移失败保持原版本与原数据且不留下中间表")
    void rollbackEntireSchemaUpgradeWhenFileTablesFail() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target/test-data"), "atomic-migration-");
        String url = "jdbc:sqlite:" + directory.resolve("application.sqlite");
        try (var connection = java.sql.DriverManager.getConnection(url);
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE files(legacy TEXT)");
            statement.execute("INSERT INTO files VALUES ('保留旧文件')");
            statement.execute("PRAGMA user_version=1");
        }
        assertThatThrownBy(() -> new ApplicationData(directory.toString())).isInstanceOf(IOException.class);
        try (var connection = java.sql.DriverManager.getConnection(url);
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='datasets'")) {
                assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery("SELECT legacy FROM files")) {
                assertThat(rows.getString(1)).isEqualTo("保留旧文件");
            }
        }
    }

    @Test
    @Order(51)
    @DisplayName("系统上传人来自后端运行账号且空值时不阻断上传")
    void useSystemUploaderAndAllowMissingAccountWithoutFabrication() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("上传人验收", credentials);
        String original = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "固定测试运行账号");
            JsonNode known = JSON.readTree(
                            upload(datasetId, credentials, new UploadPart("known.txt", "text/plain", new byte[] {65}))
                                    .body())
                    .at("/items/0/file");
            assertThat(known.get("createdBy").asText()).isEqualTo("固定测试运行账号");
            assertThat(known.at("/latestVersion/uploadedBy").asText()).isEqualTo("固定测试运行账号");
            System.setProperty("user.name", " ");
            JsonNode unknown = JSON.readTree(
                            upload(datasetId, credentials, new UploadPart("unknown.txt", "text/plain", new byte[] {65}))
                                    .body())
                    .at("/items/0/file");
            assertThat(unknown.get("createdBy").isNull()).isTrue();
            assertThat(unknown.at("/latestVersion/uploadedBy").isNull()).isTrue();
            JsonNode detail =
                    JSON.readTree(call("GET", "/files/" + unknown.get("id").asText(), null, credentials)
                            .body());
            assertThat(detail).isEqualTo(unknown);
            JsonNode page = JSON.readTree(call("GET", "/datasets/" + datasetId + "/files", null, credentials)
                    .body());
            assertThat(page.get("items").toString()).contains("\"createdBy\":null", "\"uploadedBy\":null");
        } finally {
            if (original == null) System.clearProperty("user.name");
            else System.setProperty("user.name", original);
        }
    }

    @Test
    @Order(50)
    @DisplayName("明确不可用副本返回已清理而磁盘异常返回内部错误")
    void distinguishUnavailableContentFromUnexpectedDiskFailure() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("副本故障验收", credentials);
        String id = JSON.readTree(
                        upload(datasetId, credentials, new UploadPart("content.txt", "text/plain", new byte[] {65}))
                                .body())
                .at("/items/0/file/id")
                .asText();
        try (var connection = java.sql.DriverManager.getConnection(
                        "jdbc:sqlite:" + dataDirectory.resolve("application.sqlite"));
                var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE file_versions SET available=0 WHERE file_id='" + id + "'");
            assertError(
                    call("GET", "/files/" + id + "/latest/download", null, credentials), 410, "CONTENT_UNAVAILABLE");
            assertError(call("GET", "/files/" + id + "/latest/preview", null, credentials), 410, "CONTENT_UNAVAILABLE");
            statement.executeUpdate("UPDATE file_versions SET available=1 WHERE file_id='" + id + "'");
            String relative;
            try (var rows =
                    statement.executeQuery("SELECT relative_path FROM file_versions WHERE file_id='" + id + "'")) {
                relative = rows.getString(1);
            }
            Files.delete(dataDirectory.resolve(relative));
            assertError(call("GET", "/files/" + id + "/latest/preview", null, credentials), 500, "INTERNAL_ERROR");
        }
    }

    @Test
    @Order(49)
    @DisplayName("资料标识查询与不透明分页游标按契约校验")
    void validateFileIdentifiersQueriesAndPagination() throws Exception {
        Credentials credentials = credentials();
        String datasetId = createDataset("资料分页验收", credentials);
        upload(
                datasetId,
                credentials,
                new UploadPart("first.txt", "text/plain", new byte[] {65}),
                new UploadPart("second.txt", "text/plain", new byte[] {66}));
        for (String query : List.of(
                "limit=0", "limit=101", "limit=-1", "limit=1&limit=2", "extra=1", "cursor=", "cursor=invalid")) {
            assertError(
                    call("GET", "/datasets/" + datasetId + "/files?" + query, null, credentials),
                    400,
                    "INVALID_REQUEST");
        }
        assertError(call("GET", "/files/invalid", null, credentials), 400, "INVALID_REQUEST");
        JsonNode first = JSON.readTree(call("GET", "/datasets/" + datasetId + "/files?limit=1", null, credentials)
                .body());
        String id = first.at("/items/0/id").asText();
        assertThat(first.get("nextCursor").asText()).isNotEqualTo(id);
        JsonNode second = JSON.readTree(call(
                        "GET",
                        "/datasets/" + datasetId + "/files?limit=1&cursor="
                                + first.get("nextCursor").asText(),
                        null,
                        credentials)
                .body());
        assertThat(second.at("/items/0/id").asText()).isNotEqualTo(id);
        assertThat(second.get("nextCursor").isNull()).isTrue();
        for (String suffix : List.of("", "/latest/preview", "/latest/download")) {
            assertError(call("GET", "/files/" + id + suffix + "?extra=1", null, credentials), 400, "INVALID_REQUEST");
        }
    }

    @Test
    @Order(40)
    @DisplayName("上传纯文本后可读取集中元数据与原字节")
    void uploadTextAndReadMetadataAndOriginalBytes() throws Exception {
        Credentials credentials = credentials();
        String datasetId = JSON.readTree(call("POST", "/datasets", "{\"name\":\"资料上传验收\"}", credentials)
                        .body())
                .get("id")
                .asText();
        String boundary = "knowpool-test-boundary";
        String content = "固定资料正文\r\n<script>仅作为文本</script>\n";
        String multipart = "--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"sample.txt\"\r\nContent-Type: text/plain\r\n\r\n"
                + content + "\r\n--" + boundary + "--\r\n";
        HttpResponse<String> upload = HTTP.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/datasets/" + datasetId + "/files:upload"))
                        .header("Cookie", credentials.cookie())
                        .header("X-CSRF-Token", credentials.token())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofString(multipart))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(upload.statusCode()).isEqualTo(200);
        JsonNode item = JSON.readTree(upload.body()).at("/items/0");
        assertThat(item.get("outcome").asText()).isEqualTo("created");
        String fileId = item.at("/file/id").asText();
        HttpResponse<String> metadata = call("GET", "/files/" + fileId, null, credentials);
        assertThat(metadata.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(metadata.body()).get("datasetId").asText()).isEqualTo(datasetId);
        HttpResponse<String> downloaded = call("GET", "/files/" + fileId + "/latest/download", null, credentials);
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(downloaded.body()).isEqualTo(content);
    }

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
        HttpResponse<String> response =
                HTTP.send(request(method, path, body, credentials).build(), HttpResponse.BodyHandlers.ofString());
        String route = path.split("\\?", 2)[0];
        String operation = route.startsWith("/files/")
                ? (route.endsWith("/latest/preview")
                        ? "previewLatestFile"
                        : route.endsWith("/latest/download") ? "downloadLatestFile" : "getFile")
                : route.matches("/datasets/[^/]+/files") ? "listDatasetFiles" : null;
        if (operation != null) verified("真实资料HTTP边界" + response.statusCode(), operation, response, null);
        return response;
    }

    private static void verifiedBinary(HttpResponse<byte[]> response, byte[] expected, String media, boolean preview) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.request().method()).isEqualTo("GET");
        assertThat(response.request().headers().firstValue("Cookie")).isPresent();
        String route = preview ? "preview" : "download";
        String operation = preview ? "previewLatestFile" : "downloadLatestFile";
        String prefix = "#/paths/~1files~1{fileId}~1latest~1" + route + "/get";
        String[] segments = response.request().uri().getPath().split("/");
        assertThat(segments).hasSize(7);
        assertThat(segments[1]).isEqualTo("api");
        assertThat(segments[2]).isEqualTo("v1");
        assertThat(segments[3]).isEqualTo("files");
        UUID.fromString(segments[4]);
        assertThat(segments[5]).isEqualTo("latest");
        assertThat(segments[6]).isEqualTo(route);
        assertThat(response.body()).isEqualTo(expected);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow().split(";", 2)[0])
                .isEqualTo(media);
        if (media.startsWith("text/"))
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .containsIgnoringCase("charset=UTF-8");
        String disposition =
                response.headers().firstValue("Content-Disposition").orElseThrow();
        assertThat(disposition).startsWith(preview ? "inline;" : "attachment;").contains("filename*=");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("sandbox", "default-src 'none'");
        List<String> constraints = new ArrayList<>(List.of(
                prefix + "/path",
                prefix + "/method",
                prefix + "/parameters",
                prefix + "/security",
                prefix + "/parameters/0/required",
                prefix + "/parameters/0/schema/type",
                prefix + "/parameters/0/schema/format",
                prefix + "/responses/200",
                prefix + "/responses/200/content/" + media.replace("/", "~1") + "/schema/type",
                prefix + "/responses/200/content/" + media.replace("/", "~1") + "/schema/format"));
        for (String header :
                List.of("Content-Disposition", "Content-Security-Policy", "Cache-Control", "X-Content-Type-Options"))
            constraints.add(prefix + "/responses/200/headers/" + header + "/schema/type");
        for (String header : List.of("Cache-Control", "X-Content-Type-Options"))
            constraints.add(prefix + "/responses/200/headers/" + header + "/schema/const");
        constraints.add(prefix + "/description");
        record("三媒体安全原字节响应" + media, operation, response, constraints);
    }

    private static void verified(String name, String operation, HttpResponse<String> response, String validBody)
            throws Exception {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."))
                .toAbsolutePath()
                .normalize();
        JsonNode contract =
                JSON.readTree(root.resolve("docs/agents/api/openapi.yaml").toFile());
        String path = null;
        String method = null;
        JsonNode definition = null;
        for (String candidatePath : contract.get("paths").propertyNames()) {
            JsonNode item = contract.get("paths").get(candidatePath);
            for (String candidateMethod : item.propertyNames()) {
                JsonNode candidate = item.get(candidateMethod);
                if (candidate.has("operationId")
                        && candidate.get("operationId").asText().equals(operation)) {
                    assertThat(definition).isNull();
                    path = candidatePath;
                    method = candidateMethod;
                    definition = candidate;
                }
            }
        }
        assertThat(definition).isNotNull();
        String pointer = "#/paths/" + path.replace("~", "~0").replace("/", "~1") + "/" + method;
        List<String> constraints = new ArrayList<>();
        assertThat(response.request().method()).isEqualTo(method.toUpperCase(java.util.Locale.ROOT));
        String actualPath = response.request().uri().getPath();
        assertThat(actualPath).startsWith("/api/v1/");
        String[] expectedSegments = path.split("/", -1);
        String[] actualSegments = actualPath.substring("/api/v1".length()).split("/", -1);
        assertThat(actualSegments).hasSameSizeAs(expectedSegments);
        Map<String, String> pathParameters = new LinkedHashMap<>();
        for (int index = 0; index < expectedSegments.length; index++) {
            String segment = expectedSegments[index];
            if (segment.startsWith("{") && segment.endsWith("}")) {
                assertThat(actualSegments[index]).isNotEmpty();
                pathParameters.put(segment.substring(1, segment.length() - 1), actualSegments[index]);
            } else assertThat(actualSegments[index]).isEqualTo(segment);
        }
        constraints.addAll(List.of(pointer + "/method", pointer + "/path"));
        int expected = validBody != null && operation.equals("createDataset") ? 201 : response.statusCode();
        assertThat(response.statusCode()).isEqualTo(expected);
        JsonNode outputDefinition = definition.at("/responses/" + expected);
        assertThat(outputDefinition.isMissingNode()).isFalse();
        constraints.add(pointer + "/responses/" + expected);
        if (outputDefinition.has("$ref"))
            outputDefinition = contract.at(outputDefinition.get("$ref").asText().substring(1));
        String mediaType =
                response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0];
        assertThat(outputDefinition.get("content").has(mediaType)).isTrue();
        if (mediaType.equals("application/json")) {
            validateSchema(
                    contract,
                    outputDefinition.get("content").get(mediaType).get("schema"),
                    pointer + "/responses/" + expected + "/content/application~1json/schema",
                    JSON.readTree(response.body()),
                    constraints);
        }
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
                if (parameter.get("in").asText().equals("path")) value = pathParameters.get(parameterName);
                if (parameter.get("in").asText().equals("query")) {
                    String query = response.request().uri().getRawQuery();
                    if (query != null)
                        for (String pair : query.split("&")) {
                            String[] parts = pair.split("=", 2);
                            if (java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                                    .equals(parameterName)) {
                                assertThat(value).isNull();
                                value = parts.length == 2
                                        ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                                        : "";
                            }
                        }
                }
                if (parameter.has("required")) {
                    if (parameter.get("required").booleanValue())
                        assertThat(value).isNotNull();
                    constraints.add(parameterPointer + "/required");
                }
                JsonNode schema = parameter.get("schema");
                if (value == null && schema.has("default")) {
                    value = schema.get("default").asText();
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
            constraints.add(pointer + "/parameters");
            if (validBody != null) {
                assertThat(response.request().headers().firstValue("Content-Type"))
                        .contains("application/json");
                assertThat(validBody).isNotBlank();
                JsonNode requestSchema = definition.at("/requestBody/content/application~1json/schema");
                assertThat(requestSchema.isMissingNode()).isFalse();
                validateSchema(
                        contract,
                        requestSchema,
                        pointer + "/requestBody/content/application~1json/schema",
                        JSON.readTree(validBody),
                        constraints);
                constraints.addAll(List.of(pointer + "/requestBody", pointer + "/requestBody/required"));
            }
        }
        record(name, operation, response, constraints.stream().distinct().toList());
    }

    private static void validateSchema(
            JsonNode contract, JsonNode schema, String pointer, JsonNode value, List<String> constraints) {
        assertThat(schema).isNotNull();
        assertThat(schema.isMissingNode()).isFalse();
        if (schema.has("$ref")) {
            pointer = schema.get("$ref").asText();
            schema = contract.at(pointer.substring(1));
        }
        if (schema.has("anyOf")) {
            boolean matched = false;
            for (int index = 0; index < schema.get("anyOf").size(); index++) {
                List<String> branch = new ArrayList<>();
                try {
                    validateSchema(
                            contract, schema.get("anyOf").get(index), pointer + "/anyOf/" + index, value, branch);
                    constraints.addAll(branch);
                    matched = true;
                    break;
                } catch (AssertionError rejected) {
                    // 仅记录实际通过的分支，失败分支不产生通过证据。
                }
            }
            assertThat(matched).isTrue();
            constraints.add(pointer + "/anyOf");
        }
        if (schema.has("allOf")) {
            for (int index = 0; index < schema.get("allOf").size(); index++)
                validateSchema(
                        contract, schema.get("allOf").get(index), pointer + "/allOf/" + index, value, constraints);
            constraints.add(pointer + "/allOf");
        }
        if (schema.has("if")) {
            boolean matched;
            List<String> condition = new ArrayList<>();
            try {
                validateSchema(contract, schema.get("if"), pointer + "/if", value, condition);
                matched = true;
            } catch (AssertionError rejected) {
                matched = false;
            }
            if (matched) constraints.addAll(condition);
            constraints.add(pointer + "/if");
            String branch = matched ? "then" : "else";
            if (schema.has(branch)) {
                validateSchema(contract, schema.get(branch), pointer + "/" + branch, value, constraints);
                constraints.add(pointer + "/" + branch);
            }
        }
        if (schema.has("type")) {
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
        }
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
                "enum",
                "const",
                "minItems",
                "maxItems")) {
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
                    else if (expected.asText().equals("date-time")) Instant.parse(value.asText());
                    else throw new AssertionError("格式须由实际媒体校验，不能按文本冒充：" + expected.asText());
                }
                case "required" ->
                    expected.forEach(
                            field -> assertThat(value.has(field.asText())).isTrue());
                case "additionalProperties" -> {
                    if (!expected.booleanValue())
                        assertThat(schema.get("properties").propertyNames()).containsAll(value.propertyNames());
                }
                case "minProperties", "minItems" ->
                    assertThat(value.size()).isGreaterThanOrEqualTo(expected.intValue());
                case "maxItems" -> assertThat(value.size()).isLessThanOrEqualTo(expected.intValue());
                case "enum" -> {
                    List<JsonNode> allowed = new ArrayList<>();
                    expected.forEach(allowed::add);
                    assertThat(value).isIn(allowed);
                }
                case "const" -> assertThat(value).isEqualTo(expected);
                default -> throw new AssertionError("未处理的校验约束");
            }
            constraints.add(pointer + "/" + facet);
        }
        if (value.isObject() && schema.has("properties"))
            for (String property : schema.get("properties").propertyNames())
                if (value.has(property))
                    validateSchema(
                            contract,
                            schema.get("properties").get(property),
                            pointer + "/properties/" + property,
                            value.get(property),
                            constraints);
        if (value.isArray() && schema.has("items"))
            for (JsonNode item : value)
                validateSchema(contract, schema.get("items"), pointer + "/items", item, constraints);
    }

    @Test
    @DisplayName("契约校验器拒绝常量数组边界及条件分支违规")
    void rejectInvalidSchemaConstantsArrayBoundsAndConditionalBranches() throws Exception {
        JsonNode schema = JSON.readTree(
                "{\"type\":\"array\",\"minItems\":1,\"maxItems\":2,\"items\":{\"type\":\"string\",\"const\":\"fixed\"}}");
        validateSchema(schema, schema, "#", JSON.readTree("[\"fixed\"]"), new ArrayList<>());
        for (String invalid : List.of("[]", "[\"fixed\",\"fixed\",\"fixed\"]", "[\"wrong\"]"))
            assertThatThrownBy(() -> validateSchema(schema, schema, "#", JSON.readTree(invalid), new ArrayList<>()))
                    .isInstanceOf(AssertionError.class);
        JsonNode conditional = JSON.readTree(
                "{\"allOf\":[{\"if\":{\"properties\":{\"failed\":{\"const\":true}}},\"then\":{\"properties\":{\"result\":{\"type\":\"null\"}}},\"else\":{\"properties\":{\"result\":{\"type\":\"string\"}}}}]}");
        validateSchema(
                conditional, conditional, "#", JSON.readTree("{\"failed\":true,\"result\":null}"), new ArrayList<>());
        List<String> branches = new ArrayList<>();
        validateSchema(
                conditional, conditional, "#", JSON.readTree("{\"failed\":false,\"result\":\"fixed\"}"), branches);
        assertThat(branches)
                .contains("#/allOf/0/else")
                .doesNotContain("#/allOf/0/then", "#/allOf/0/if/properties/failed/const");
        for (String invalid : List.of("{\"failed\":true,\"result\":\"wrong\"}", "{\"failed\":false,\"result\":null}"))
            assertThatThrownBy(() ->
                            validateSchema(conditional, conditional, "#", JSON.readTree(invalid), new ArrayList<>()))
                    .isInstanceOf(AssertionError.class);
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

    @Test
    @DisplayName("提供方证据同名断言保留稳定且唯一的编号")
    void preserveUniqueStableEvidenceNamesForRepeatedAssertions() {
        List<String> names = new ArrayList<>();
        for (int index = 1; index <= 3; index++) names.add(evidenceName("uploadFiles", index, "重复上传断言"));
        names.add(evidenceName("getFile", 4, "重复上传断言"));
        assertThat(names).hasSize(4).doesNotHaveDuplicates();
        assertThat(names.getFirst()).isEqualTo(evidenceName("uploadFiles", 1, "重复上传断言"));
        assertThat(names).allSatisfy(name -> assertThat(name).contains("重复上传断言"));
    }

    private static String evidenceName(String operation, int sequence, String name) {
        return sequence + "：" + operation + "：" + name;
    }

    private static void record(String name, String operation, HttpResponse<?> response, List<String> constraintIds) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("name", evidenceName(operation, EVIDENCE.size() + 1, name));
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
        assertThat(EVIDENCE.stream().map(item -> item.get("name")).toList()).doesNotHaveDuplicates();
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
