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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ModelSettingsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private static final java.util.List<java.util.Map<String, Object>> OBSERVATIONS = new java.util.ArrayList<>();
    private static final java.util.List<java.util.Map<String, String>> ROUTES = new java.util.ArrayList<>();
    private static final java.util.Set<String> PASSED_BEHAVIORS = new java.util.HashSet<>();

    @org.junit.jupiter.api.extension.RegisterExtension
    final org.junit.jupiter.api.extension.TestWatcher completed = new org.junit.jupiter.api.extension.TestWatcher() {
        @Override
        public void testSuccessful(org.junit.jupiter.api.extension.ExtensionContext context) {
            PASSED_BEHAVIORS.add(context.getRequiredTestMethod().getName());
        }
    };

    @Test
    @DisplayName("十四操作真实安全错误和本机存储故障使用统一包装")
    void everyOperationEnforcesSecurityAndStorageFailure() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "settings-security-")
                .toAbsolutePath();
        try (ConfigurableApplicationContext application = start(directory)) {
            String base = base(application);
            Login login = login(base);
            String model = config(
                    "http://localhost:9911/v1",
                    "模型",
                    "embedding",
                    "{\"action\":\"replace\",\"value\":\"security-key\"}");
            JsonNode embedding =
                    JSON.readTree(send(base, "/settings/models/embedding", "PUT", model, login.cookie(), login.token())
                            .body());
            String version = embedding.get("versionId").asText();
            String initial = read(base, "/settings/models/embedding/versions?limit=100", login.cookie())
                    .get("items")
                    .get(0)
                    .get("versionId")
                    .asText();
            for (JsonNode item : read(base, "/settings/models/embedding/versions?limit=100", login.cookie())
                    .get("items")) {
                if (item.get("endpoint").isNull())
                    initial = item.get("versionId").asText();
            }
            read(base, "/settings/models/embedding/versions/" + initial, login.cookie());
            String clearedEmbedding = config("http://localhost:9911/v1", "模型", "embedding", "{\"action\":\"clear\"}");
            assertThat(send(base, "/settings/models/embedding", "PUT", clearedEmbedding, login.cookie(), login.token())
                            .statusCode())
                    .isEqualTo(200);
            String vectorState =
                    "{\"endpoint\":\"http://localhost:9911\",\"credential\":{\"action\":\"replace\",\"value\":\"vector-test-key\"}}";
            assertThat(send(base, "/settings/vector-store", "PUT", vectorState, login.cookie(), login.token())
                            .statusCode())
                    .isEqualTo(200);
            String vectorKeep = "{\"endpoint\":\"http://localhost:9911\",\"credential\":{\"action\":\"keep\"}}";
            JsonNode vectorBefore = read(base, "/settings/vector-store", login.cookie());
            assertThat(send(base, "/settings/vector-store", "PUT", vectorKeep, login.cookie(), login.token())
                            .statusCode())
                    .isEqualTo(200);
            assertError(
                    send(
                            base,
                            "/settings/vector-store",
                            "PUT",
                            vectorKeep.replace(":9911", ":9912"),
                            login.cookie(),
                            login.token()),
                    400,
                    "INVALID_REQUEST");
            assertThat(read(base, "/settings/vector-store", login.cookie())).isEqualTo(vectorBefore);
            String taskId = UUID.randomUUID().toString();
            java.util.List<String[]> operations = java.util.List.of(
                    new String[] {"GET", "/settings/models/generation", null},
                    new String[] {
                        "PUT",
                        "/settings/models/generation",
                        config("http://localhost:9911/v1", "模型", "generation", "{\"action\":\"clear\"}")
                    },
                    new String[] {"POST", "/settings/models/generation/test", "{\"allowChargedCall\":true}"},
                    new String[] {"GET", "/settings/models/embedding", null},
                    new String[] {"PUT", "/settings/models/embedding", model},
                    new String[] {"POST", "/settings/models/embedding/test", "{\"allowChargedCall\":true}"},
                    new String[] {"GET", "/settings/models/embedding/versions", null},
                    new String[] {"GET", "/settings/models/embedding/versions/" + version, null},
                    new String[] {
                        "PUT", "/settings/models/embedding/versions/" + version + "/credential", "{\"action\":\"keep\"}"
                    },
                    new String[] {"GET", "/tasks/" + taskId + "/connection-test", null},
                    new String[] {"GET", "/settings/vector-store", null},
                    new String[] {
                        "PUT",
                        "/settings/vector-store",
                        "{\"endpoint\":\"http://localhost:9911\",\"credential\":{\"action\":\"clear\"}}"
                    },
                    new String[] {"POST", "/settings/vector-store/test", null},
                    new String[] {"GET", "/tasks/" + taskId, null});
            for (String[] operation : operations) {
                assertError(
                        send(base, operation[1], operation[0], operation[2], null, login.token()),
                        401,
                        "SESSION_EXPIRED");
                HttpRequest.Builder hostile = HttpRequest.newBuilder(URI.create(base + operation[1]))
                        .header("Cookie", login.cookie())
                        .header("X-CSRF-Token", login.token())
                        .header("Origin", "https://untrusted.example");
                if (operation[2] != null) hostile.header("Content-Type", "application/json");
                assertError(
                        observed(
                                http.send(
                                        hostile.method(
                                                        operation[0],
                                                        operation[2] == null
                                                                ? HttpRequest.BodyPublishers.noBody()
                                                                : HttpRequest.BodyPublishers.ofString(operation[2]))
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString()),
                                operation[2]),
                        403,
                        "ORIGIN_REJECTED");
                if (operation[0].equals("GET"))
                    assertError(
                            send(base, operation[1] + "?unknown=1", operation[0], null, login.cookie(), null),
                            400,
                            "INVALID_REQUEST");
                else
                    assertError(
                            send(base, operation[1], operation[0], operation[2], login.cookie(), null),
                            403,
                            "CSRF_REJECTED");
            }
            for (String value : java.util.List.of("【密钥已设置】", "")) {
                HttpResponse<String> rejected = send(
                        base,
                        "/settings/models/embedding/versions/" + version + "/credential",
                        "PUT",
                        "{\"action\":\"replace\",\"value\":\"" + value + "\"}",
                        login.cookie(),
                        login.token());
                assertError(rejected, 400, "INVALID_REQUEST");
                assertThat(JSON.readTree(rejected.body())
                                .at("/fieldErrors/0/field")
                                .asText())
                        .isEqualTo("/value");
            }
            assertThat(send(
                                    base,
                                    "/settings/models/embedding/versions/" + version + "/credential",
                                    "PUT",
                                    "{\"action\":\"replace\",\"value\":\"***\"}",
                                    login.cookie(),
                                    login.token())
                            .statusCode())
                    .isEqualTo(200);
            assertThat(send(
                                    base,
                                    "/settings/models/embedding/versions/" + initial + "/credential",
                                    "PUT",
                                    "{\"action\":\"keep\"}",
                                    login.cookie(),
                                    login.token())
                            .statusCode())
                    .isEqualTo(200);
            read(base, "/settings/models/embedding", login.cookie());
            read(base, "/settings/models/embedding/versions?limit=100", login.cookie());
            read(base, "/settings/models/embedding/versions/" + version, login.cookie());
            String generation = config(
                    "http://localhost:9911/v1",
                    "模型",
                    "generation",
                    "{\"action\":\"replace\",\"value\":\"security-key\"}");
            assertThat(send(base, "/settings/models/generation", "PUT", generation, login.cookie(), login.token())
                            .statusCode())
                    .isEqualTo(200);
            read(base, "/settings/models/generation", login.cookie());
            assertThat(send(
                                    base,
                                    "/settings/vector-store",
                                    "PUT",
                                    "{\"endpoint\":\"http://localhost:9911\",\"credential\":{\"action\":\"replace\",\"value\":\"security-key\"}}",
                                    login.cookie(),
                                    login.token())
                            .statusCode())
                    .isEqualTo(200);
            read(base, "/settings/vector-store", login.cookie());
            try (var connection = java.sql.DriverManager.getConnection(
                            "jdbc:sqlite:" + directory.resolve("application.sqlite"));
                    var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE model_configs RENAME TO blocked_model_configs");
                statement.execute("ALTER TABLE vector_config RENAME TO blocked_vector_config");
                statement.execute("ALTER TABLE tasks RENAME TO blocked_tasks");
                for (String[] operation : operations) {
                    HttpResponse<String> response = operation[0].equals("POST")
                            ? probe(
                                    base,
                                    operation[1],
                                    login.cookie(),
                                    login.token(),
                                    UUID.randomUUID().toString(),
                                    operation[2])
                            : send(base, operation[1], operation[0], operation[2], login.cookie(), login.token());
                    assertError(response, 500, "INTERNAL_ERROR");
                }
                statement.execute("ALTER TABLE blocked_model_configs RENAME TO model_configs");
                statement.execute("ALTER TABLE blocked_vector_config RENAME TO vector_config");
                statement.execute("ALTER TABLE blocked_tasks RENAME TO tasks");
            }
        }
    }

    @Test
    @DisplayName("模型任务固定完整旧快照且不接受不可信TLS证书")
    void preservesSnapshotAndValidatesTls() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        stub.createContext("/", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            seen.add(exchange.getRequestURI().getPath() + "|"
                    + exchange.getRequestHeaders().getFirst("Authorization") + "|"
                    + request.get("model").asText() + "|"
                    + request.get("max_tokens").asInt());
            if (seen.size() == 1) {
                entered.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body =
                    "{\"id\":\"snapshot\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"snapshot\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"正常\"},\"finish_reason\":\"stop\"}]}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        Path directory =
                Files.createTempDirectory(Path.of("target"), "probe-snapshot-").toAbsolutePath();
        Path tls = Files.createTempDirectory(Path.of("target"), "tls-controlled-");
        char[] password = "controlled-fixture".toCharArray();
        Path keyStore = tls.resolve("untrusted.p12");
        Process keytool = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool.exe")
                                .toString(),
                        "-genkeypair",
                        "-alias",
                        "controlled",
                        "-keyalg",
                        "RSA",
                        "-validity",
                        "2",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost,ip:127.0.0.1",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        keyStore.toString(),
                        "-storepass",
                        "controlled-fixture",
                        "-noprompt")
                .redirectErrorStream(true)
                .start();
        keytool.getInputStream().readAllBytes();
        assertThat(keytool.waitFor()).isZero();
        java.security.KeyStore keys = java.security.KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(keyStore)) {
            keys.load(stream, password);
        }
        javax.net.ssl.KeyManagerFactory manager =
                javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        manager.init(keys, password);
        javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
        ssl.init(manager.getKeyManagers(), null, null);
        com.sun.net.httpserver.HttpsServer secure =
                com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secure.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ssl));
        AtomicInteger secureRequests = new AtomicInteger();
        secure.createContext("/", exchange -> {
            secureRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        secure.start();
        try (ConfigurableApplicationContext application = start(directory)) {
            String base = base(application);
            Login login = login(base);
            String endpoint = "http://127.0.0.1:" + stub.getAddress().getPort();
            String original = config(
                            endpoint + "/old",
                            "模型甲",
                            "generation",
                            "{\"action\":\"replace\",\"value\":\"snapshot-key-one\"}")
                    .replace("1024", "1");
            JsonNode old = JSON.readTree(
                    send(base, "/settings/models/generation", "PUT", original, login.cookie(), login.token())
                            .body());
            String first = JSON.readTree(probe(
                                    base,
                                    "/settings/models/generation/test",
                                    login.cookie(),
                                    login.token(),
                                    UUID.randomUUID().toString(),
                                    "{\"allowChargedCall\":true}")
                            .body())
                    .at("/task/id")
                    .asText();
            assertThat(entered.await(8, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String queued = JSON.readTree(probe(
                                    base,
                                    "/settings/models/generation/test",
                                    login.cookie(),
                                    login.token(),
                                    UUID.randomUUID().toString(),
                                    "{\"allowChargedCall\":true}")
                            .body())
                    .at("/task/id")
                    .asText();
            JsonNode changed = JSON.readTree(send(
                            base,
                            "/settings/models/generation",
                            "PUT",
                            original.replace("/old", "/new")
                                    .replace("模型甲", "模型乙")
                                    .replace("snapshot-key-one", "snapshot-key-two")
                                    .replace("\"maxOutputTokens\":1", "\"maxOutputTokens\":2"),
                            login.cookie(),
                            login.token())
                    .body());
            release.countDown();
            assertThat(awaitTask(base, first, login.cookie()).get("targetConfigVersionId"))
                    .isEqualTo(old.get("versionId"));
            assertThat(awaitTask(base, queued, login.cookie()).get("targetConfigVersionId"))
                    .isEqualTo(old.get("versionId"));
            String current = JSON.readTree(probe(
                                    base,
                                    "/settings/models/generation/test",
                                    login.cookie(),
                                    login.token(),
                                    UUID.randomUUID().toString(),
                                    "{\"allowChargedCall\":true}")
                            .body())
                    .at("/task/id")
                    .asText();
            assertThat(awaitTask(base, current, login.cookie()).get("targetConfigVersionId"))
                    .isEqualTo(changed.get("versionId"));
            assertThat(seen)
                    .containsExactly(
                            "/old/chat/completions|Bearer snapshot-key-one|模型甲|1",
                            "/old/chat/completions|Bearer snapshot-key-one|模型甲|1",
                            "/new/chat/completions|Bearer snapshot-key-two|模型乙|2");
            String vector = "{\"endpoint\":\"https://127.0.0.1:"
                    + secure.getAddress().getPort() + "\",\"credential\":{\"action\":\"clear\"}}";
            assertThat(send(base, "/settings/vector-store", "PUT", vector, login.cookie(), login.token())
                            .statusCode())
                    .isEqualTo(200);
            String failed = JSON.readTree(probe(
                                    base,
                                    "/settings/vector-store/test",
                                    login.cookie(),
                                    login.token(),
                                    UUID.randomUUID().toString(),
                                    null)
                            .body())
                    .at("/task/id")
                    .asText();
            assertThat(awaitTask(base, failed, login.cookie()).get("state").asText())
                    .isEqualTo("succeeded");
            assertThat(read(base, "/tasks/" + failed + "/connection-test", login.cookie())
                            .get("available")
                            .asBoolean())
                    .isFalse();
            assertThat(secureRequests.get()).isZero();
        } finally {
            release.countDown();
            stub.stop(0);
            secure.stop(0);
        }
    }

    @Test
    @DisplayName("结果完整性故障与本机失败不同于远端不可用")
    void localFailureAndResultIntegrityAreExplicit() throws Exception {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            byte[] body = "{\"result\":{\"collections\":[]},\"status\":\"ok\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        Path directory =
                Files.createTempDirectory(Path.of("target"), "probe-integrity-").toAbsolutePath();
        try (ConfigurableApplicationContext application = start(directory)) {
            String base = base(application);
            Login login = login(base);
            String config = "{\"endpoint\":\"http://127.0.0.1:"
                    + stub.getAddress().getPort() + "\",\"credential\":{\"action\":\"clear\"}}";
            assertError(
                    probe(
                            base,
                            "/settings/vector-store/test",
                            login.cookie(),
                            login.token(),
                            UUID.randomUUID().toString(),
                            null),
                    422,
                    "INVALID_STATE");
            assertThat(send(base, "/settings/vector-store", "PUT", config, login.cookie(), login.token())
                            .statusCode())
                    .isEqualTo(200);
            assertError(
                    probe(
                            base,
                            "/settings/vector-store/test",
                            login.cookie(),
                            login.token(),
                            UUID.randomUUID().toString(),
                            "{}"),
                    400,
                    "INVALID_REQUEST");
            assertError(
                    probe(base, "/settings/vector-store/test", login.cookie(), login.token(), "bad-key", null),
                    400,
                    "INVALID_REQUEST");
            String id = JSON.readTree(probe(
                                    base,
                                    "/settings/vector-store/test",
                                    login.cookie(),
                                    login.token(),
                                    UUID.randomUUID().toString(),
                                    null)
                            .body())
                    .at("/task/id")
                    .asText();
            assertThat(awaitTask(base, id, login.cookie()).get("state").asText())
                    .isEqualTo("succeeded");
            assertThat(read(base, "/tasks/" + id + "/connection-test", login.cookie())
                            .get("mayHaveIncurredCost")
                            .asBoolean())
                    .isFalse();
            try (var connection = java.sql.DriverManager.getConnection(
                            "jdbc:sqlite:" + directory.resolve("application.sqlite"));
                    var statement = connection.createStatement()) {
                statement.execute("UPDATE tasks SET result=NULL WHERE id='" + id + "'");
                assertError(
                        send(base, "/tasks/" + id + "/connection-test", "GET", null, login.cookie(), null),
                        500,
                        "INTERNAL_ERROR");
                statement.execute(
                        "UPDATE tasks SET payload=json_set(payload,'$.kind','cleanup') WHERE id='" + id + "'");
                assertError(
                        send(base, "/tasks/" + id + "/connection-test", "GET", null, login.cookie(), null),
                        404,
                        "NOT_FOUND");
                statement.execute(
                        "CREATE TRIGGER fail_connection_completion BEFORE UPDATE ON tasks WHEN NEW.state='succeeded' BEGIN SELECT RAISE(ABORT,'受控本机故障'); END");
                for (String kind : java.util.List.of("generation", "embedding")) {
                    assertThat(send(
                                            base,
                                            "/settings/models/" + kind,
                                            "PUT",
                                            config(
                                                    "http://127.0.0.1:"
                                                            + stub.getAddress().getPort(),
                                                    "模型",
                                                    kind,
                                                    "{\"action\":\"replace\",\"value\":\"failure-key\"}"),
                                            login.cookie(),
                                            login.token())
                                    .statusCode())
                            .isEqualTo(200);
                }
                for (String kind : java.util.List.of("generation", "embedding", "vector")) {
                    String operation = kind.equals("vector")
                            ? "/settings/vector-store/test"
                            : "/settings/models/" + kind + "/test";
                    String consent = kind.equals("vector") ? null : "{\"allowChargedCall\":true}";
                    String failureKey = UUID.randomUUID().toString();
                    String failedTask = JSON.readTree(
                                    probe(base, operation, login.cookie(), login.token(), failureKey, consent)
                                            .body())
                            .at("/task/id")
                            .asText();
                    assertThat(awaitTask(base, failedTask, login.cookie())
                                    .get("state")
                                    .asText())
                            .isEqualTo("failed");
                    JsonNode repeated = JSON.readTree(
                                    probe(base, operation, login.cookie(), login.token(), failureKey, consent)
                                            .body())
                            .get("task");
                    assertThat(repeated.get("state").asText()).isEqualTo("failed");
                    assertThat(repeated.get("id").asText()).isEqualTo(failedTask);
                }
                String failed = JSON.readTree(probe(
                                        base,
                                        "/settings/vector-store/test",
                                        login.cookie(),
                                        login.token(),
                                        UUID.randomUUID().toString(),
                                        null)
                                .body())
                        .at("/task/id")
                        .asText();
                JsonNode task = awaitTask(base, failed, login.cookie());
                assertThat(task.get("state").asText()).isEqualTo("failed");
                assertThat(task.at("/failure/code").asText()).isEqualTo("INTERNAL_ERROR");
                assertThat(task.at("/failure/retryable").asBoolean()).isFalse();
                assertError(
                        send(base, "/tasks/" + failed + "/connection-test", "GET", null, login.cookie(), null),
                        409,
                        "INVALID_STATE");
                statement.execute("DROP TRIGGER fail_connection_completion");
            }
            for (String path : java.util.List.of(
                    "/tasks/" + UUID.randomUUID(),
                    "/tasks/" + UUID.randomUUID() + "/connection-test",
                    "/settings/models/embedding/versions/" + UUID.randomUUID()))
                assertError(send(base, path, "GET", null, login.cookie(), null), 404, "NOT_FOUND");
            for (String path : java.util.List.of(
                    "/tasks/bad", "/tasks/bad/connection-test", "/settings/models/embedding/versions/bad"))
                assertError(send(base, path, "GET", null, login.cookie(), null), 400, "INVALID_REQUEST");
            assertError(
                    send(
                            base,
                            "/settings/models/embedding/versions/" + UUID.randomUUID() + "/credential",
                            "PUT",
                            "{\"action\":\"keep\"}",
                            login.cookie(),
                            login.token()),
                    404,
                    "NOT_FOUND");
            String page = "/settings/models/embedding/versions";
            for (String query : java.util.List.of(
                    "limit=0",
                    "limit=101",
                    "limit=1.5",
                    "limit=x",
                    "limit=999999999999",
                    "limit=1&limit=2",
                    "x=1",
                    "cursor=",
                    "cursor=bad",
                    "cursor=" + "x".repeat(2049)))
                assertError(send(base, page + "?" + query, "GET", null, login.cookie(), null), 400, "INVALID_REQUEST");
        } finally {
            stub.stop(0);
        }
    }

    @AfterAll
    static void evidence() throws Exception {
        if (OBSERVATIONS.isEmpty()) return;
        OBSERVATIONS.getFirst().put("routes", ROUTES);
        for (java.util.Map<String, Object> item : OBSERVATIONS) {
            String behavior = (String) item.get("behavior");
            if (!PASSED_BEHAVIORS.contains(behavior)) item.put("behavior", "");
        }
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."))
                .toAbsolutePath()
                .normalize();
        Process process = new ProcessBuilder(
                        "python", "server/src/test/resources/contract/settings-evidence.py", root.toString())
                .directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try (var input = process.getOutputStream()) {
            input.write(JSON.writeValueAsBytes(OBSERVATIONS));
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }

    private static synchronized HttpResponse<String> observed(HttpResponse<String> response, String body) {
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        java.util.Map<String, Object> observation = new java.util.LinkedHashMap<>();
        observation.put(
                "path",
                response.request().uri().getRawPath()
                        + (response.request().uri().getRawQuery() == null
                                ? ""
                                : "?" + response.request().uri().getRawQuery()));
        observation.put("method", response.request().method());
        observation.put("status", response.statusCode());
        observation.put(
                "contentType", response.headers().firstValue("Content-Type").orElse(""));
        observation.put("response", JSON.readTree(response.body()));
        observation.put("body", body);
        observation.put(
                "behavior",
                java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                        .filter(frame -> frame.getClassName().equals(ModelSettingsTest.class.getName()))
                        .map(StackTraceElement::getMethodName)
                        .filter(name -> java.util.Set.of(
                                        "preservesSnapshotAndValidatesTls",
                                        "probesRespectTransportBoundaries",
                                        "queuedSnapshotsAndRestartRemainSafe",
                                        "credentialAndVersionsAreIndependent",
                                        "initialConfigurationSurvivesRestart",
                                        "localFailureAndResultIntegrityAreExplicit",
                                        "rejectsInvalidConfigurationWithoutChanges",
                                        "embeddingProbeUsesSavedSnapshotAndIdempotency",
                                        "everyOperationEnforcesSecurityAndStorageFailure")
                                .contains(name))
                        .findFirst()
                        .orElse(""));
        java.util.Map<String, String> parameters = new java.util.LinkedHashMap<>();
        response.request().headers().map().forEach((name, values) -> parameters.put(name, values.getFirst()));
        observation.put("parameters", parameters);
        OBSERVATIONS.add(observation);
        return response;
    }

    @Test
    @DisplayName("排队快照独立与重启中断不自动外发")
    void queuedSnapshotsAndRestartRemainSafe() throws Exception {
        java.util.concurrent.CountDownLatch received = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        stub.createContext("/", exchange -> {
            calls.incrementAndGet();
            received.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        stub.start();
        Path directory =
                Files.createTempDirectory(Path.of("target"), "probe-restart-").toAbsolutePath();
        ConfigurableApplicationContext application = start(directory);
        String firstId;
        String queuedId;
        try {
            String base = base(application);
            Login login = login(base);
            String model = config(
                    "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1",
                    "模型甲",
                    "embedding",
                    "{\"action\":\"replace\",\"value\":\"restart-key\"}");
            assertThat(send(base, "/settings/models/embedding", "PUT", model, login.cookie(), login.token())
                            .statusCode())
                    .isEqualTo(200);
            firstId = JSON.readTree(probe(
                                    base,
                                    "/settings/models/embedding/test",
                                    login.cookie(),
                                    login.token(),
                                    UUID.randomUUID().toString(),
                                    "{\"allowChargedCall\":true}")
                            .body())
                    .at("/task/id")
                    .asText();
            assertThat(received.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String key = UUID.randomUUID().toString();
            HttpResponse<String> queued = probe(
                    base,
                    "/settings/models/embedding/test",
                    login.cookie(),
                    login.token(),
                    key,
                    "{\"allowChargedCall\":true}");
            assertThat(queued.statusCode()).isEqualTo(202);
            queuedId = JSON.readTree(queued.body()).at("/task/id").asText();
            assertThat(read(base, "/tasks/" + queuedId, login.cookie())
                            .get("state")
                            .asText())
                    .isEqualTo("queued");
            assertError(
                    send(base, "/tasks/" + queuedId + "/connection-test", "GET", null, login.cookie(), null),
                    409,
                    "INVALID_STATE");
            for (int count = 0; count < 15; count++)
                assertThat(probe(
                                        base,
                                        "/settings/models/embedding/test",
                                        login.cookie(),
                                        login.token(),
                                        UUID.randomUUID().toString(),
                                        "{\"allowChargedCall\":true}")
                                .statusCode())
                        .isEqualTo(202);
            assertThat(send(
                                    base,
                                    "/settings/models/generation",
                                    "PUT",
                                    config(
                                            "http://localhost:9911",
                                            "模型",
                                            "generation",
                                            "{\"action\":\"replace\",\"value\":\"queue-key\"}"),
                                    login.cookie(),
                                    login.token())
                            .statusCode())
                    .isEqualTo(200);
            assertThat(send(
                                    base,
                                    "/settings/vector-store",
                                    "PUT",
                                    "{\"endpoint\":\"http://localhost:9911\",\"credential\":{\"action\":\"clear\"}}",
                                    login.cookie(),
                                    login.token())
                            .statusCode())
                    .isEqualTo(200);
            assertError(
                    probe(
                            base,
                            "/settings/models/generation/test",
                            login.cookie(),
                            login.token(),
                            UUID.randomUUID().toString(),
                            "{\"allowChargedCall\":true}"),
                    429,
                    "QUEUE_FULL");
            assertError(
                    probe(
                            base,
                            "/settings/vector-store/test",
                            login.cookie(),
                            login.token(),
                            UUID.randomUUID().toString(),
                            null),
                    429,
                    "QUEUE_FULL");
            assertError(
                    probe(
                            base,
                            "/settings/models/embedding/test",
                            login.cookie(),
                            login.token(),
                            UUID.randomUUID().toString(),
                            "{\"allowChargedCall\":true}"),
                    429,
                    "QUEUE_FULL");
            assertThat(JSON.readTree(probe(
                                            base,
                                            "/settings/models/embedding/test",
                                            login.cookie(),
                                            login.token(),
                                            key,
                                            "{\"allowChargedCall\":true}")
                                    .body())
                            .at("/task/id")
                            .asText())
                    .isEqualTo(queuedId);
            assertThat(send(
                                    base,
                                    "/settings/models/embedding",
                                    "PUT",
                                    model.replace("模型甲", "模型乙").replace("restart-key", "new-restart-key"),
                                    login.cookie(),
                                    login.token())
                            .statusCode())
                    .isEqualTo(200);
            assertThat(JSON.readTree(probe(
                                            base,
                                            "/settings/models/embedding/test",
                                            login.cookie(),
                                            login.token(),
                                            key,
                                            "{\"allowChargedCall\":true}")
                                    .body())
                            .at("/task/id")
                            .asText())
                    .isEqualTo(queuedId);
            application.close();
            release.countDown();
            try (ConfigurableApplicationContext reopened = start(directory)) {
                base = base(reopened);
                login = login(base);
                for (String id : java.util.List.of(firstId, queuedId)) {
                    JsonNode task = read(base, "/tasks/" + id, login.cookie());
                    assertThat(task.get("state").asText()).isEqualTo("interrupted");
                    assertThat(task.get("phase").asText()).isEqualTo("completed");
                    assertThat(task.at("/failure/code").asText()).isEqualTo("TASK_INTERRUPTED");
                    assertThat(task.get("warnings").get(0).asText()).contains("可能已产生费用");
                    assertError(
                            send(base, "/tasks/" + id + "/connection-test", "GET", null, login.cookie(), null),
                            409,
                            "INVALID_STATE");
                }
                assertThat(calls.get()).isEqualTo(1);
            }
        } finally {
            release.countDown();
            application.close();
            stub.stop(0);
        }
    }

    @Test
    @DisplayName("生成向量探测不可用与重定向限额无隐式重试")
    void probesRespectTransportBoundaries() throws Exception {
        AtomicInteger sourceCalls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> stubFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger targetCalls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> mode =
                new java.util.concurrent.atomic.AtomicReference<>("generation");
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            targetCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        target.start();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        stub.createContext("/", exchange -> {
            try {
                sourceCalls.incrementAndGet();
                String current = mode.get();
                byte[] body;
                if (current.equals("generation")) {
                    assertThat(exchange.getRequestURI().getPath()).isEqualTo("/v1/chat/completions");
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                            .isEqualTo("Bearer boundary-key");
                    JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    assertThat(request.get("model").asText()).isEqualTo("原样模型");
                    assertThat(request.at("/messages/0/role").asText()).isEqualTo("user");
                    assertThat(request.at("/messages/0/content").asText()).isEqualTo("请回复连接正常。");
                    assertThat(request.get("messages").size()).isEqualTo(1);
                    assertThat(request.get("stream").asBoolean()).isFalse();
                    assertThat(request.get("max_tokens").asInt()).isEqualTo(1);
                    body =
                            "{\"id\":\"controlled\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"原样模型\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"正常\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } else if (current.equals("vector")) {
                    assertThat(exchange.getRequestURI().getPath()).isEqualTo("/collections");
                    assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                    assertThat(exchange.getRequestHeaders().getFirst("api-key")).isEqualTo("boundary-key");
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                            .isNull();
                    body = "{\"result\":{\"collections\":[]},\"status\":\"ok\"}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } else if (current.equals("redirect")) {
                    exchange.getResponseHeaders()
                            .set(
                                    "Location",
                                    "http://127.0.0.1:" + target.getAddress().getPort() + "/stolen");
                    exchange.sendResponseHeaders(307, -1);
                    exchange.close();
                    return;
                } else if (current.equals("disconnect")) {
                    exchange.close();
                    return;
                } else if (current.equals("limit")) body = new byte[262145];
                else if (current.equals("timeout")) {
                    try {
                        Thread.sleep(5500);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    }
                    body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } else body = "{\"error\":\"boundary-key不得回显\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(current.equals("denied") ? 401 : 200, body.length);
                try {
                    exchange.getResponseBody().write(body);
                } catch (java.io.IOException ignored) {
                } finally {
                    exchange.close();
                }
            } catch (Throwable failure) {
                stubFailure.set(failure.getClass().getName() + " " + failure.getStackTrace()[0]);
                exchange.close();
            }
        });
        stub.start();
        Path directory =
                Files.createTempDirectory(Path.of("target"), "probe-boundary-").toAbsolutePath();
        try (ConfigurableApplicationContext application = start(directory)) {
            String base = base(application);
            Login login = login(base);
            String endpoint = "http://127.0.0.1:" + stub.getAddress().getPort();
            assertThat(send(
                                    base,
                                    "/settings/models/generation",
                                    "PUT",
                                    config(
                                                    endpoint + "/v1",
                                                    "原样模型",
                                                    "generation",
                                                    "{\"action\":\"replace\",\"value\":\"boundary-key\"}")
                                            .replace("1024", "1"),
                                    login.cookie(),
                                    login.token())
                            .statusCode())
                    .isEqualTo(200);
            assertThat(send(
                                    base,
                                    "/settings/vector-store",
                                    "PUT",
                                    "{\"endpoint\":\"" + endpoint
                                            + "\",\"credential\":{\"action\":\"replace\",\"value\":\"boundary-key\"}}",
                                    login.cookie(),
                                    login.token())
                            .statusCode())
                    .isEqualTo(200);
            for (String current : java.util.List.of(
                    "generation", "vector", "redirect", "disconnect", "limit", "timeout", "denied", "malformed")) {
                mode.set(current);
                int before = sourceCalls.get();
                String path =
                        current.equals("vector") ? "/settings/vector-store/test" : "/settings/models/generation/test";
                HttpResponse<String> accepted = probe(
                        base,
                        path,
                        login.cookie(),
                        login.token(),
                        UUID.randomUUID().toString(),
                        current.equals("vector") ? null : "{\"allowChargedCall\":true}");
                assertThat(accepted.statusCode()).isEqualTo(202);
                String id = JSON.readTree(accepted.body()).at("/task/id").asText();
                JsonNode task = awaitTask(base, id, login.cookie());
                assertThat(task.get("state").asText()).isEqualTo("succeeded");
                JsonNode result = read(base, "/tasks/" + id + "/connection-test", login.cookie());
                assertThat(stubFailure.get()).isNull();
                assertThat(result.get("available").asBoolean())
                        .as(current)
                        .isEqualTo(current.equals("generation") || current.equals("vector"));
                assertThat(result.get("mayHaveIncurredCost").asBoolean()).isEqualTo(!current.equals("vector"));
                assertThat(result.toString()).doesNotContain("boundary-key");
                assertThat(sourceCalls.get() - before).as(current).isEqualTo(1);
                assertThat(targetCalls.get()).isZero();
            }
            assertThat(Files.readString(Path.of("target/test-model-settings.log")))
                    .doesNotContain("boundary-key");
        } finally {
            stub.stop(0);
            target.stop(0);
        }
    }

    @Test
    @DisplayName("配置非法输入原子拒绝且字段位置准确")
    void rejectsInvalidConfigurationWithoutChanges() throws Exception {
        Path directory =
                Files.createTempDirectory(Path.of("target"), "model-invalid-").toAbsolutePath();
        try (ConfigurableApplicationContext application = start(directory)) {
            String base = base(application);
            Login login = login(base);
            String generation = config(
                    "http://localhost:9911/v1",
                    "原样模型",
                    "generation",
                    "{\"action\":\"replace\",\"value\":\"test-boundary-key\"}");
            String embedding = config("http://localhost:9911/v1", "原样模型", "embedding", "{\"action\":\"keep\"}");
            for (String kind : java.util.List.of("generation", "embedding")) {
                String path = "/settings/models/" + kind;
                String valid = kind.equals("generation") ? generation : embedding;
                JsonNode before = read(base, path, login.cookie());
                for (String invalid : java.util.List.of(
                        "null",
                        "[]",
                        "{}",
                        valid.replace("\"model\":\"原样模型\",", ""),
                        valid.replace("原样模型", " "),
                        valid.replace("原样模型", "长".repeat(201)),
                        valid.replace("\"model\":\"原样模型\"", "\"model\":null"),
                        valid.replace("\"model\":\"原样模型\"", "\"model\":3"),
                        valid.replace("{\"endpoint\"", "{\"unexpected\":true,\"endpoint\""),
                        valid.replace("\"credential\":{", "\"credential\":{\"extra\":1,"),
                        valid.replace("\"credential\":{", "\"credential\":null,\"unused\":{ "))) {
                    assertError(
                            send(base, path, "PUT", invalid, login.cookie(), login.token()), 400, "INVALID_REQUEST");
                    assertThat(read(base, path, login.cookie())).isEqualTo(before);
                }
                for (String endpoint : java.util.List.of(
                        "",
                        "ftp://localhost/v1",
                        "mailto:test",
                        "http://user:pass@localhost/v1",
                        "http://localhost/v1?q=1",
                        "http://localhost/v1#f",
                        "http://",
                        "http://bad_host/v1",
                        "http://localhost:0/v1",
                        "http://localhost:65536/v1",
                        "http://localhost/v1/chat/completions/",
                        "http://localhost/v1/embeddings")) {
                    assertError(
                            send(
                                    base,
                                    path,
                                    "PUT",
                                    valid.replace("http://localhost:9911/v1", endpoint),
                                    login.cookie(),
                                    login.token()),
                            400,
                            "INVALID_REQUEST");
                }
                for (String change : java.util.List.of(
                        "null",
                        "{}",
                        "{\"action\":null}",
                        "{\"action\":false}",
                        "{\"action\":\"unknown\"}",
                        "{\"action\":\"keep\",\"value\":\"x\"}",
                        "{\"action\":\"clear\",\"value\":\"x\"}",
                        "{\"action\":\"replace\"}",
                        "{\"action\":\"replace\",\"value\":\"\"}",
                        "{\"action\":\"replace\",\"value\":false}",
                        "{\"action\":\"replace\",\"value\":\"【密钥已设置】\"}",
                        "{\"action\":\"replace\",\"value\":\"" + "k".repeat(8193) + "\"}")) {
                    assertError(
                            send(
                                    base,
                                    path,
                                    "PUT",
                                    config("http://localhost:9911/v1", "模型", kind, change),
                                    login.cookie(),
                                    login.token()),
                            400,
                            "INVALID_REQUEST");
                }
                for (String number : java.util.List.of(
                        "null", "0", "-1", "1.5", "9007199254740992", "\"1\"", "true", "9223372036854775808")) {
                    if (kind.equals("generation")) {
                        for (String field : java.util.List.of("contextWindowTokens", "maxOutputTokens")) {
                            String changed = valid.replace(
                                    "\"" + field + "\":" + (field.equals("contextWindowTokens") ? "8192" : "1024"),
                                    "\"" + field + "\":" + number);
                            assertError(
                                    send(base, path, "PUT", changed, login.cookie(), login.token()),
                                    400,
                                    "INVALID_REQUEST");
                            assertThat(read(base, path, login.cookie())).isEqualTo(before);
                        }
                    }
                }
                if (kind.equals("embedding"))
                    assertError(
                            send(
                                    base,
                                    path,
                                    "PUT",
                                    valid.replace("\"contextWindowTokens\":null", "\"contextWindowTokens\":1"),
                                    login.cookie(),
                                    login.token()),
                            400,
                            "INVALID_REQUEST");
                assertError(
                        send(base, path + "?unknown=1", "PUT", valid, login.cookie(), login.token()),
                        400,
                        "INVALID_REQUEST");
                assertError(send(base, path, "PUT", valid, login.cookie(), null), 403, "CSRF_REJECTED");
                assertError(send(base, path, "PUT", valid, null, login.token()), 401, "SESSION_EXPIRED");
                assertError(send(base, path + "?unknown=1", "GET", null, login.cookie(), null), 400, "INVALID_REQUEST");
                assertError(
                        probe(
                                base,
                                path + "/test",
                                login.cookie(),
                                login.token(),
                                UUID.randomUUID().toString(),
                                "{\"allowChargedCall\":true}"),
                        422,
                        "INVALID_STATE");
                for (String consent : java.util.List.of(
                        "{}",
                        "null",
                        "{\"allowChargedCall\":false}",
                        "{\"allowChargedCall\":\"true\"}",
                        "{\"allowChargedCall\":true,\"x\":1}"))
                    assertError(
                            probe(
                                    base,
                                    path + "/test",
                                    login.cookie(),
                                    login.token(),
                                    UUID.randomUUID().toString(),
                                    consent),
                            400,
                            "INVALID_REQUEST");
            }
            String gpath = "/settings/models/generation";
            JsonNode saved = JSON.readTree(send(base, gpath, "PUT", generation, login.cookie(), login.token())
                    .body());
            assertThat(saved.get("complete").asBoolean()).isTrue();
            String keep = "{\"action\":\"keep\"}";
            String change = config("http://localhost:9911/v1", "原样模型", "generation", keep)
                    .replace("8192", "9007199254740991");
            JsonNode changed = JSON.readTree(send(base, gpath, "PUT", change, login.cookie(), login.token())
                    .body());
            assertThat(changed.get("versionId")).isNotEqualTo(saved.get("versionId"));
            assertThat(changed.get("contextWindowTokens").longValue()).isEqualTo(9007199254740991L);
            JsonNode cleared = JSON.readTree(send(
                            base,
                            gpath,
                            "PUT",
                            change.replace(keep, "{\"action\":\"clear\"}"),
                            login.cookie(),
                            login.token())
                    .body());
            assertThat(cleared.get("complete").asBoolean()).isFalse();
            assertThat(cleared.get("versionId")).isEqualTo(changed.get("versionId"));
        }
    }

    private static String config(String endpoint, String model, String kind, String credential) {
        return "{\"endpoint\":\"" + endpoint + "\",\"model\":\"" + model + "\",\"contextWindowTokens\":"
                + (kind.equals("generation") ? "8192" : "null") + ",\"maxOutputTokens\":"
                + (kind.equals("generation") ? "1024" : "null") + ",\"credential\":" + credential + "}";
    }

    private Login login(String base) throws Exception {
        HttpResponse<String> response = send(base, "/session", "GET", null, null, null);
        return new Login(
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(response.body()).get("csrfToken").asText());
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode error = JSON.readTree(response.body());
        assertThat(error.propertyNames())
                .containsExactlyInAnyOrder("requestId", "code", "message", "fieldErrors", "retryable");
        assertThat(error.get("code").asText()).isEqualTo(code);
        assertThat(error.get("requestId").asText())
                .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        UUID.fromString(error.get("requestId").asText());
        assertThat(error.get("message").isString()).isTrue();
        assertThat(error.get("fieldErrors").isArray()).isTrue();
        assertThat(error.get("retryable").asBoolean()).isEqualTo(status >= 500);
    }

    private record Login(String cookie, String token) {}

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
        for (int count = 0; count < 250; count++) {
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
        return observed(
                http.send(
                        request.POST(
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()),
                body);
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
        HttpResponse<String> response = observed(
                http.send(
                        request.method(
                                        method,
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()),
                body);
        RuntimeTestFixtures.allowSavedModel(http, base, path, method, body, cookie, token, response.statusCode());
        return response;
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
        ConfigurableApplicationContext application = SpringApplication.run(
                KnowledgePoolApplication.class,
                "--server.port=0",
                "--know-pool.data-directory=" + directory,
                "--logging.file.name=target/test-model-settings.log");
        if (ROUTES.isEmpty())
            application
                    .getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
                    .getHandlerMethods()
                    .keySet()
                    .forEach(mapping -> {
                        for (String path : mapping.getPatternValues())
                            if (path.startsWith("/api/v1/"))
                                mapping.getMethodsCondition()
                                        .getMethods()
                                        .forEach(method ->
                                                ROUTES.add(java.util.Map.of("method", method.name(), "path", path)));
                    });
        return application;
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
        HttpResponse<String> response = observed(
                http.send(
                        HttpRequest.newBuilder(URI.create(base + path))
                                .header("Cookie", cookie)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()),
                null);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
}
