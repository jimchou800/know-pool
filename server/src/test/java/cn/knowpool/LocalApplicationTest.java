package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.knowpool.system.api.LocalSessionFilter;
import cn.knowpool.system.data.ApplicationData;
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
    void 本机会话和持久状态通过真实接口联通() throws Exception {
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
    void 会话失效和未声明参数返回契约错误() throws Exception {
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
    void 拒绝外部来源和伪造主机并允许明确开发来源() throws Exception {
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
    void 敏感写入缺少令牌被拒绝且未知路由不暴露内部错误() throws Exception {
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
    void 同一数据目录禁止双开且重开保留表结构版本() throws Exception {
        assertThatThrownBy(() -> new ApplicationData(dataDirectory.toString()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("另一应用进程");
        Path other = Files.createTempDirectory(Path.of("target/test-data"), "reopen-");
        try (ApplicationData first = new ApplicationData(other.toString())) {
            assertThat(first.status().schemaVersion()).isEqualTo(1);
        }
        try (ApplicationData reopened = new ApplicationData(other.toString())) {
            assertThat(reopened.status().schemaVersion()).isEqualTo(1);
        }
        assertThatThrownBy(() -> new ApplicationData("\\\\server\\share"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("本机磁盘");
    }

    @Test
    void 真实存储故障只返回统一脱敏错误() throws Exception {
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
    void 业务包不存在循环依赖() {
        SlicesRuleDefinition.slices()
                .matching("cn.knowpool.(*)..")
                .should()
                .beFreeOfCycles()
                .check(new ClassFileImporter().importPackages("cn.knowpool"));
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void 会话容量通过真实接口有界拒绝() throws Exception {
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
        assertThat(error.get("fieldErrors").isEmpty()).isTrue();
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
        evidence.put("constraintIds", constraints);
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
