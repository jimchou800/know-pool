package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ModelSettingsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("固定嵌入探测通过真实替身且同幂等键不再次外发")
    void embeddingProbeUsesSavedSnapshotAndIdempotency() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/embeddings", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer controlled-test-key");
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("X-CSRF-Token")).isNull();
            JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(request.get("model").asText()).isEqualTo("原样模型");
            assertThat(request.at("/input/0").asText()).isEqualTo("连接测试");
            assertThat(request.get("encoding_format").asText()).isEqualTo("float");
            assertThat(request.has("dimensions")).isFalse();
            byte[] body =
                    "{\"object\":\"list\",\"model\":\"原样模型\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.25,-0.5]}],\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        Path directory =
                Files.createTempDirectory(Path.of("target"), "model-probe-").toAbsolutePath();
        try (ConfigurableApplicationContext application = start(directory)) {
            String base = base(application);
            HttpResponse<String> session = send(base, "/session", "GET", null, null, null);
            String cookie =
                    session.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            String token = JSON.readTree(session.body()).get("csrfToken").asText();
            String config =
                    "{\"endpoint\":\"http://127.0.0.1:" + stub.getAddress().getPort()
                            + "/v1\",\"model\":\"原样模型\",\"contextWindowTokens\":null,\"maxOutputTokens\":null,\"credential\":{\"action\":\"replace\",\"value\":\"controlled-test-key\"}}";
            assertThat(send(base, "/settings/models/embedding", "PUT", config, cookie, token)
                            .statusCode())
                    .isEqualTo(200);
            String key = UUID.randomUUID().toString();
            HttpResponse<String> accepted =
                    probe(base, "/settings/models/embedding/test", cookie, token, key, "{\"allowChargedCall\":true}");
            assertThat(accepted.statusCode()).isEqualTo(202);
            String id = JSON.readTree(accepted.body()).at("/task/id").asText();
            JsonNode task = awaitTask(base, id, cookie);
            assertThat(task.get("state").asText()).isEqualTo("succeeded");
            JsonNode result = read(base, "/tasks/" + id + "/connection-test", cookie);
            assertThat(result.get("available").asBoolean()).isTrue();
            assertThat(result.get("mayHaveIncurredCost").asBoolean()).isTrue();
            assertThat(JSON.readTree(probe(
                                            base,
                                            "/settings/models/embedding/test",
                                            cookie,
                                            token,
                                            key,
                                            "{\"allowChargedCall\":true}")
                                    .body())
                            .at("/task/id")
                            .asText())
                    .isEqualTo(id);
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            stub.stop(0);
        }
    }

    private JsonNode awaitTask(String base, String id, String cookie) throws Exception {
        for (int count = 0; count < 100; count++) {
            JsonNode task = read(base, "/tasks/" + id, cookie);
            if (!java.util.Set.of("queued", "running")
                    .contains(task.get("state").asText())) return task;
            Thread.sleep(30);
        }
        throw new AssertionError("连接任务未在期限内完成。");
    }

    private HttpResponse<String> probe(String base, String path, String cookie, String token, String key, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", token)
                .header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(
                request.POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("模型保存三态与版本独立且跨源保持原子拒绝")
    void credentialAndVersionsAreIndependent() throws Exception {
        Path directory =
                Files.createTempDirectory(Path.of("target"), "model-save-").toAbsolutePath();
        try (ConfigurableApplicationContext application = start(directory)) {
            String base = base(application);
            HttpResponse<String> session = send(base, "/session", "GET", null, null, null);
            String cookie =
                    session.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            String token = JSON.readTree(session.body()).get("csrfToken").asText();
            String first =
                    "{\"endpoint\":\"http://localhost:9911/v1\",\"model\":\"原样模型\",\"contextWindowTokens\":null,\"maxOutputTokens\":null,\"credential\":{\"action\":\"replace\",\"value\":\"测试凭据甲\"}}";
            HttpResponse<String> saved = send(base, "/settings/models/embedding", "PUT", first, cookie, token);
            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(saved.body()).doesNotContain("测试凭据甲");
            JsonNode v1 = JSON.readTree(saved.body());
            assertThat(v1.get("complete").asBoolean()).isTrue();
            String changed = first.replace("原样模型", "模型乙")
                    .replace("\"action\":\"replace\",\"value\":\"测试凭据甲\"", "\"action\":\"keep\"");
            JsonNode v2 = JSON.readTree(send(base, "/settings/models/embedding", "PUT", changed, cookie, token)
                    .body());
            assertThat(v2.get("versionId")).isNotEqualTo(v1.get("versionId"));
            assertThat(v2.get("complete").asBoolean()).isTrue();
            assertThat(JSON.readTree(send(base, "/settings/models/embedding", "PUT", changed, cookie, token)
                            .body()))
                    .isEqualTo(v2);
            HttpResponse<String> rejected =
                    send(base, "/settings/models/embedding", "PUT", changed.replace(":9911", ":9912"), cookie, token);
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(JSON.readTree(rejected.body()).at("/fieldErrors/0/field").asText())
                    .isEqualTo("/credential/action");
            assertThat(read(base, "/settings/models/embedding", cookie)).isEqualTo(v2);
            String old =
                    "/settings/models/embedding/versions/" + v1.get("versionId").asText();
            assertThat(send(base, old + "/credential", "PUT", "{\"action\":\"clear\"}", cookie, token)
                            .statusCode())
                    .isEqualTo(200);
            assertThat(read(base, old, cookie).get("complete").asBoolean()).isFalse();
            assertThat(read(base, "/settings/models/embedding", cookie)).isEqualTo(v2);
            JsonNode page = read(base, "/settings/models/embedding/versions?limit=1", cookie);
            assertThat(page.get("items").size()).isEqualTo(1);
            assertThat(page.get("nextCursor").isString()).isTrue();
        }
    }

    private HttpResponse<String> send(String base, String path, String method, String body, String cookie, String token)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (cookie != null) request.header("Cookie", cookie);
        if (token != null) request.header("X-CSRF-Token", token);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(
                request.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("两类模型初始未设置状态持久化且重启不改变版本和时间")
    void initialConfigurationSurvivesRestart() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "models-").toAbsolutePath();
        JsonNode generation;
        JsonNode embedding;
        try (ConfigurableApplicationContext application = start(directory)) {
            String base = base(application);
            String cookie = session(base);
            generation = read(base, "/settings/models/generation", cookie);
            embedding = read(base, "/settings/models/embedding", cookie);
            for (JsonNode model : new JsonNode[] {generation, embedding}) {
                assertThat(model.get("complete").asBoolean()).isFalse();
                assertThat(model.get("endpoint").isNull()).isTrue();
                assertThat(model.get("model").isNull()).isTrue();
                assertThat(model.get("contextWindowTokens").isNull()).isTrue();
                assertThat(model.get("maxOutputTokens").isNull()).isTrue();
                assertThat(model.at("/credential/configured").asBoolean()).isFalse();
                assertThat(model.at("/credential/mask").isNull()).isTrue();
                assertThat(model.get("updatedAt").asText()).endsWith("Z");
            }
            assertThat(generation.get("versionId")).isNotEqualTo(embedding.get("versionId"));
        }
        try (ConfigurableApplicationContext application = start(directory)) {
            String base = base(application);
            String cookie = session(base);
            assertThat(read(base, "/settings/models/generation", cookie)).isEqualTo(generation);
            assertThat(read(base, "/settings/models/embedding", cookie)).isEqualTo(embedding);
            JsonNode vector = read(base, "/settings/vector-store", cookie);
            assertThat(vector.get("endpoint").isNull()).isTrue();
            assertThat(vector.get("configured").asBoolean()).isFalse();
        }
    }

    private static ConfigurableApplicationContext start(Path directory) {
        return SpringApplication.run(
                KnowledgePoolApplication.class,
                "--server.port=0",
                "--know-pool.data-directory=" + directory,
                "--logging.file.name=target/test-model-settings.log");
    }

    private static String base(ConfigurableApplicationContext application) {
        return "http://127.0.0.1:" + application.getEnvironment().getProperty("local.server.port") + "/api/v1";
    }

    private String session(String base) throws Exception {
        return http.send(
                        HttpRequest.newBuilder(URI.create(base + "/session"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Set-Cookie")
                .orElseThrow()
                .split(";", 2)[0];
    }

    private JsonNode read(String base, String path, String cookie) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Cookie", cookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
}
