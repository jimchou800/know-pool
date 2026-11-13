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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeBasesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private static final java.util.List<java.util.Map<String, Object>> OBSERVATIONS = new java.util.ArrayList<>();
    private static final java.util.List<java.util.Map<String, String>> ROUTES = new java.util.ArrayList<>();
    private static final java.util.Set<String> PASSED = new java.util.HashSet<>();

    @org.junit.jupiter.api.extension.RegisterExtension
    final org.junit.jupiter.api.extension.TestWatcher completed = new org.junit.jupiter.api.extension.TestWatcher() {
        @Override
        public void testSuccessful(org.junit.jupiter.api.extension.ExtensionContext context) {
            PASSED.add(context.getRequiredTestMethod().getName());
        }
    };

    @Test
    @DisplayName("空知识库经过真实集合初始化后可读且重启保留")
    void emptyKnowledgeBaseSurvivesRestart() throws Exception {
        HttpServer vector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.Set<String> collections = java.util.concurrent.ConcurrentHashMap.newKeySet();
        vector.createContext("/collections/", exchange -> {
            String name = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if (exchange.getRequestMethod().equals("PUT")) {
                assertThat(JSON.readTree(exchange.getRequestBody().readAllBytes())
                                .at("/vectors/size")
                                .asInt())
                        .isEqualTo(3);
                collections.add(name);
                body = "{\"status\":\"ok\",\"result\":true}";
            } else if (!collections.contains(name)) {
                status = 404;
                body = "{}";
            } else
                body =
                        "{\"status\":\"ok\",\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        vector.start();
        Path directory =
                Files.createTempDirectory(Path.of("target"), "knowledge-base-").toAbsolutePath();
        String id;
        try {
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                String processing = configure(client, vector.getAddress().getPort());
                HttpResponse<String> accepted = send(
                        client,
                        "/knowledge-bases",
                        "POST",
                        "{\"name\":\"  空库  \",\"processing\":" + processing + "}",
                        UUID.randomUUID().toString());
                assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
                JsonNode task = await(
                        client, JSON.readTree(accepted.body()).at("/task/id").asText());
                assertThat(task.get("state").asText()).as(task.toString()).isEqualTo("succeeded");
                id = task.get("resultResourceId").asText();
                assertThat(task.get("knowledgeBaseId").asText()).isEqualTo(id);
                JsonNode kb = read(client, "/knowledge-bases/" + id);
                assertThat(kb.get("name").asText()).isEqualTo("空库");
                assertThat(kb.get("publicationState").asText()).isEqualTo("unpublished");
                assertThat(kb.get("searchableFileCount").asInt()).isZero();
                assertThat(read(client, "/knowledge-bases/" + id + "/sources")
                                .path("items")
                                .size())
                        .isZero();
                assertThat(read(client, "/knowledge-bases/" + id + "/files")
                                .path("items")
                                .size())
                        .isZero();
                assertThat(read(client, "/knowledge-bases").get("items").size()).isEqualTo(1);
                assertThat(read(client, "/knowledge-bases/" + id + "/processing-config")
                                .get("input"))
                        .isEqualTo(JSON.readTree(processing));
                assertThat(read(
                                        client,
                                        "/knowledge-bases/" + id.toUpperCase(java.util.Locale.ROOT)
                                                + "/processing-config")
                                .get("input"))
                        .isEqualTo(JSON.readTree(processing));
            }
            try (ConfigurableApplicationContext app = start(directory)) {
                assertThat(read(client(app), "/knowledge-bases/" + id)
                                .get("name")
                                .asText())
                        .isEqualTo("空库");
            }
        } finally {
            vector.stop(0);
        }
    }

    private String configure(Client client, int port) throws Exception {
        String endpoint = "http://127.0.0.1:" + port;
        String model = "{\"endpoint\":\"" + endpoint
                + "/v1\",\"model\":\"固定模型\",\"contextWindowTokens\":null,\"maxOutputTokens\":null,\"credential\":{\"action\":\"clear\"}}";
        JsonNode saved = JSON.readTree(
                send(client, "/settings/models/embedding", "PUT", model, null).body());
        assertThat(send(
                                client,
                                "/settings/vector-store",
                                "PUT",
                                "{\"endpoint\":\"" + endpoint + "\",\"credential\":{\"action\":\"clear\"}}",
                                null)
                        .statusCode())
                .isEqualTo(200);
        return "{\"chunking\":{\"strategy\":\"structure\",\"overlapCodePoints\":200,\"removePageHeadersFooters\":false,\"customMetadata\":{},\"embeddedMetadataFields\":[]},\"embedding\":{\"modelConfigVersionId\":\""
                + saved.get("versionId").asText()
                + "\",\"dimensions\":3,\"distance\":\"cosine\",\"similarityThreshold\":0.5,\"candidatesPerQuery\":10,\"modelSnapshot\":{\"endpoint\":\""
                + endpoint + "/v1\",\"model\":\"固定模型\"}}}";
    }

    @Test
    @DisplayName("初始数据集与单文件来源保留并集且排队任务跨重启唯一")
    void sourcesKeepUniqueQueuedIntent() throws Exception {
        HttpServer vector = vector();
        Path directory = Files.createTempDirectory(Path.of("target"), "knowledge-sources-")
                .toAbsolutePath();
        String id;
        String queued;
        try {
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                String processing = configure(client, vector.getAddress().getPort());
                String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"来源\"}", null)
                                .body())
                        .get("id")
                        .asText();
                String file = upload(client, dataset);
                HttpResponse<String> accepted = send(
                        client,
                        "/knowledge-bases",
                        "POST",
                        "{\"name\":\"关联库\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset + "\"]}",
                        UUID.randomUUID().toString());
                assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
                id = await(client, JSON.readTree(accepted.body()).at("/task/id").asText())
                        .get("resultResourceId")
                        .asText();
                assertThat(read(client, "/knowledge-bases/" + id + "/sources")
                                .get("items")
                                .size())
                        .isEqualTo(1);
                JsonNode initial = read(client, "/knowledge-bases/" + id + "/files")
                        .get("items")
                        .get(0);
                queued = initial.at("/task/id").asText();
                assertThat(initial.at("/task/state").asText()).isEqualTo("queued");
                assertThat(send(
                                        client,
                                        "/knowledge-bases/" + id + "/sources",
                                        "POST",
                                        "{\"type\":\"file\",\"resourceId\":\"" + file + "\"}",
                                        null)
                                .statusCode())
                        .isEqualTo(201);
                JsonNode merged = read(client, "/knowledge-bases/" + id + "/files");
                assertThat(merged.get("items").size()).isEqualTo(1);
                assertThat(merged.at("/items/0/sources").size()).isEqualTo(2);
                assertThat(merged.at("/items/0/task/id").asText()).isEqualTo(queued);
                assertThat(send(
                                        client,
                                        "/knowledge-bases/" + id + "/sources",
                                        "POST",
                                        "{\"type\":\"file\",\"resourceId\":\"" + file + "\"}",
                                        null)
                                .statusCode())
                        .isEqualTo(409);
                assertThat(read(client, "/knowledge-bases/" + id)
                                .path("includedFileCount")
                                .asInt())
                        .isEqualTo(1);
                String secondDataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"另一来源\"}", null)
                                .body())
                        .path("id")
                        .asText();
                upload(client, secondDataset);
                assertThat(send(
                                        client,
                                        "/knowledge-bases/" + id + "/sources",
                                        "POST",
                                        "{\"type\":\"dataset\",\"resourceId\":\"" + secondDataset + "\"}",
                                        null)
                                .statusCode())
                        .isEqualTo(201);
                for (String suffix : java.util.List.of("sources", "files")) {
                    String path = "/knowledge-bases/" + id + "/" + suffix;
                    JsonNode first = read(client, path + "?limit=1");
                    assertThat(first.path("items").size()).isEqualTo(1);
                    assertThat(first.path("nextCursor").isTextual()).isTrue();
                    assertThat(read(
                                            client,
                                            path + "?limit=1&cursor="
                                                    + first.path("nextCursor").asText())
                                    .path("items")
                                    .size())
                            .isEqualTo(1);
                }
            }
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                java.util.Set<String> taskIds = new java.util.HashSet<>();
                for (JsonNode item :
                        read(client, "/knowledge-bases/" + id + "/files").path("items"))
                    taskIds.add(item.at("/task/id").asText());
                assertThat(taskIds).contains(queued);
                assertThat(read(client, "/tasks/" + queued).path("state").asText())
                        .isEqualTo("queued");
            }
        } finally {
            vector.stop(0);
        }
    }

    private HttpServer vector() throws Exception {
        HttpServer vector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.Set<String> names = java.util.concurrent.ConcurrentHashMap.newKeySet();
        vector.createContext("/collections/", exchange -> {
            String name = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if (exchange.getRequestMethod().equals("PUT")) {
                names.add(name);
                body = "{\"status\":\"ok\",\"result\":true}";
            } else if (!names.contains(name)) {
                status = 404;
                body = "{}";
            } else
                body =
                        "{\"status\":\"ok\",\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        vector.start();
        return vector;
    }

    @Test
    @DisplayName("名称并发唯一、同键重放和不可变模型快照保持一致")
    void namesIdempotencyAndSnapshotsAreStable() throws Exception {
        HttpServer vector = vector();
        Path directory =
                Files.createTempDirectory(Path.of("target"), "knowledge-names-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = configure(client, vector.getAddress().getPort());
            String body = "{\"name\":\" 库甲 \",\"description\":\"创建说明\",\"processing\":" + processing + "}";
            String key = UUID.randomUUID().toString();
            HttpResponse<String> accepted = send(client, "/knowledge-bases", "POST", body, key);
            String taskId = JSON.readTree(accepted.body()).at("/task/id").asText();
            JsonNode finished = await(client, taskId);
            String id = finished.path("resultResourceId").asText();
            assertThat(finished.path("createdAt"))
                    .isEqualTo(JSON.readTree(accepted.body()).at("/task/createdAt"));
            assertThat(JSON.readTree(send(client, "/knowledge-bases", "POST", body, key)
                                    .body())
                            .at("/task/id")
                            .asText())
                    .isEqualTo(taskId);
            assertThat(send(client, "/knowledge-bases", "POST", body.replace("库甲", "库乙"), key)
                            .statusCode())
                    .isEqualTo(409);
            assertThat(send(
                                    client,
                                    "/knowledge-bases",
                                    "POST",
                                    body,
                                    UUID.randomUUID().toString())
                            .statusCode())
                    .isEqualTo(409);
            HttpResponse<String> second = send(
                    client,
                    "/knowledge-bases",
                    "POST",
                    body.replace("库甲", "库乙"),
                    UUID.randomUUID().toString());
            String other = await(
                            client, JSON.readTree(second.body()).at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            assertThat(send(client, "/knowledge-bases/" + other, "PATCH", "{\"name\":\"库甲\"}", null)
                            .statusCode())
                    .isEqualTo(409);
            assertThat(send(
                                    client,
                                    "/knowledge-bases/" + id,
                                    "PATCH",
                                    "{\"name\":\" 新名称 \",\"description\":\"说明\"}",
                                    null)
                            .statusCode())
                    .isEqualTo(200);
            String changed =
                    "{\"endpoint\":\"http://localhost:9555/v1\",\"model\":\"另一个模型\",\"contextWindowTokens\":null,\"maxOutputTokens\":null,\"credential\":{\"action\":\"clear\"}}";
            send(client, "/settings/models/embedding", "PUT", changed, null);
            assertThat(read(client, "/knowledge-bases/" + id + "/processing-config")
                            .path("input"))
                    .isEqualTo(JSON.readTree(processing));
            HttpResponse<String> historical = send(
                    client,
                    "/knowledge-bases",
                    "POST",
                    body.replace("库甲", "历史库"),
                    UUID.randomUUID().toString());
            assertThat(historical.statusCode()).as(historical.body()).isEqualTo(202);
            assertThat(await(
                                    client,
                                    JSON.readTree(historical.body())
                                            .at("/task/id")
                                            .asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            String cursor =
                    read(client, "/knowledge-bases?limit=1").path("nextCursor").asText();
            assertThat(read(client, "/knowledge-bases?limit=1&cursor=" + cursor)
                            .path("items")
                            .size())
                    .isEqualTo(1);
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2),
                    release = new java.util.concurrent.CountDownLatch(1);
            try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
                for (int i = 0; i < 2; i++)
                    results.add(workers.submit(() -> {
                        ready.countDown();
                        release.await();
                        return send(
                                        client,
                                        "/knowledge-bases",
                                        "POST",
                                        body.replace("库甲", "并发唯一"),
                                        UUID.randomUUID().toString())
                                .statusCode();
                    }));
                assertThat(ready.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                release.countDown();
                assertThat(java.util.List.of(
                                results.get(0).get(), results.get(1).get()))
                        .containsExactlyInAnyOrder(202, 409);
            }
        } finally {
            vector.stop(0);
        }
    }

    private String upload(Client client, String dataset) throws Exception {
        String boundary = "knowledge-boundary";
        String body = "--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"source.txt\"\r\nContent-Type: text/plain\r\n\r\n固定资料\r\n--"
                + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(client.base() + "/datasets/" + dataset + "/files:upload"))
                .header("Cookie", client.cookie())
                .header("X-CSRF-Token", client.token())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).at("/items/0/file/id").asText();
    }

    @Test
    @DisplayName("有界创建队列满返回四二九且半初始化不可见")
    void queueCapacityAndHiddenInitialization() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1),
                release = new java.util.concurrent.CountDownLatch(1);
        HttpServer vector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vector.createContext("/collections/", exchange -> {
            entered.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        vector.start();
        Path directory =
                Files.createTempDirectory(Path.of("target"), "knowledge-queue-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = configure(client, vector.getAddress().getPort());
            String firstBody = "{\"name\":\"队列0\",\"processing\":" + processing + "}";
            String key = UUID.randomUUID().toString();
            HttpResponse<String> first = send(client, "/knowledge-bases", "POST", firstBody, key);
            assertThat(first.statusCode()).isEqualTo(202);
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String id = JSON.readTree(first.body()).at("/task/knowledgeBaseId").asText();
            assertThat(send(client, "/knowledge-bases/" + id, "GET", null, null).statusCode())
                    .isEqualTo(404);
            assertThat(read(client, "/knowledge-bases").path("items").size()).isZero();
            for (int i = 1; i < 17; i++)
                assertThat(send(
                                        client,
                                        "/knowledge-bases",
                                        "POST",
                                        firstBody.replace("队列0", "队列" + i),
                                        UUID.randomUUID().toString())
                                .statusCode())
                        .isEqualTo(202);
            HttpResponse<String> full = send(
                    client,
                    "/knowledge-bases",
                    "POST",
                    firstBody.replace("队列0", "容量之外"),
                    UUID.randomUUID().toString());
            assertThat(full.statusCode()).as(full.body()).isEqualTo(429);
            assertThat(JSON.readTree(send(client, "/knowledge-bases", "POST", firstBody, key)
                                    .body())
                            .at("/task/id"))
                    .isEqualTo(JSON.readTree(first.body()).at("/task/id"));
        } finally {
            release.countDown();
            vector.stop(0);
        }
    }

    private JsonNode await(Client client, String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode task = read(client, "/tasks/" + id);
            if (!java.util.Set.of("queued", "running")
                    .contains(task.path("state").asText())) return task;
            Thread.sleep(25);
        }
        throw new AssertionError("创建任务没有按期结束。");
    }

    @Test
    @DisplayName("持久提交失败保留原原因且补偿失败可在重启后清理")
    void compensationResumesWithoutReplacingOriginalFailure() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean exists = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean allowDelete = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger deletes = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer vector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vector.createContext("/collections/", exchange -> {
            String body = "{\"status\":\"ok\",\"result\":true}";
            int status = 200;
            switch (exchange.getRequestMethod()) {
                case "PUT" -> exists.set(true);
                case "DELETE" -> {
                    deletes.incrementAndGet();
                    if (allowDelete.get()) exists.set(false);
                    else body = "{\"status\":\"ok\",\"result\":false}";
                }
                default -> {
                    if (!exists.get()) {
                        status = 404;
                        body = "{}";
                    } else
                        body =
                                "{\"status\":\"ok\",\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}";
                }
            }
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        vector.start();
        Path directory = Files.createTempDirectory(Path.of("target"), "knowledge-cleanup-")
                .toAbsolutePath();
        String taskId;
        JsonNode failure;
        try {
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                String processing = configure(client, vector.getAddress().getPort());
                try (java.sql.Connection db = java.sql.DriverManager.getConnection(
                                "jdbc:sqlite:" + directory.resolve("application.sqlite"));
                        java.sql.Statement statement = db.createStatement()) {
                    statement.execute(
                            "CREATE TRIGGER fail_kb_commit BEFORE UPDATE OF visible ON knowledge_bases WHEN NEW.visible=1 BEGIN SELECT RAISE(ABORT,'固定提交故障'); END");
                }
                HttpResponse<String> response = send(
                        client,
                        "/knowledge-bases",
                        "POST",
                        "{\"name\":\"补偿库\",\"processing\":" + processing + "}",
                        UUID.randomUUID().toString());
                taskId = JSON.readTree(response.body()).at("/task/id").asText();
                JsonNode failed = await(client, taskId);
                failure = failed.path("failure");
                assertThat(failed.path("state").asText()).isEqualTo("failed");
                assertThat(failed.path("resultResourceId").isNull()).isTrue();
                assertThat(failure.path("code").asText()).isEqualTo("INTERNAL_ERROR");
                for (int i = 0; i < 100 && deletes.get() == 0; i++) Thread.sleep(20);
                assertThat(deletes.get()).isEqualTo(1);
                assertThat(exists.get()).isTrue();
                assertThat(read(client, "/knowledge-bases").path("items").size())
                        .isZero();
            }
            allowDelete.set(true);
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                for (int i = 0; i < 100 && exists.get(); i++) Thread.sleep(20);
                assertThat(exists.get()).isFalse();
                assertThat(deletes.get()).isEqualTo(2);
                assertThat(read(client, "/tasks/" + taskId).path("failure")).isEqualTo(failure);
                assertThat(read(client, "/knowledge-bases").path("items").size())
                        .isZero();
            }
        } finally {
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("已有或创建结果不确定的集合从不接管删除")
    void foreignAndUnknownCollectionsAreNeverDeleted() throws Exception {
        for (String mode : java.util.List.of("existing", "uncertain", "rejected")) {
            java.util.concurrent.atomic.AtomicInteger deletes = new java.util.concurrent.atomic.AtomicInteger(),
                    puts = new java.util.concurrent.atomic.AtomicInteger();
            HttpServer vector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            vector.createContext("/collections/", exchange -> {
                int status = 200;
                String body =
                        "{\"status\":\"ok\",\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}";
                if (exchange.getRequestMethod().equals("DELETE")) deletes.incrementAndGet();
                if (exchange.getRequestMethod().equals("PUT")) {
                    puts.incrementAndGet();
                    if (mode.equals("uncertain")) {
                        exchange.close();
                        return;
                    }
                    status = 500;
                } else if (!mode.equals("existing") && puts.get() == 0) {
                    status = 404;
                    body = "{}";
                }
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            vector.start();
            Path directory = Files.createTempDirectory(Path.of("target"), "knowledge-ownership-")
                    .toAbsolutePath();
            try {
                try (ConfigurableApplicationContext app = start(directory)) {
                    Client client = client(app);
                    String processing = configure(client, vector.getAddress().getPort());
                    String key = UUID.randomUUID().toString();
                    String body = "{\"name\":\"安全补偿\",\"processing\":" + processing + "}";
                    HttpResponse<String> response = send(client, "/knowledge-bases", "POST", body, key);
                    String taskId =
                            JSON.readTree(response.body()).at("/task/id").asText();
                    JsonNode failed = await(client, taskId);
                    assertThat(failed.path("state").asText()).isEqualTo("failed");
                    assertThat(failed.path("resultResourceId").isNull()).isTrue();
                    if (mode.equals("uncertain"))
                        assertThat(failed.path("warnings").size()).isPositive();
                    assertThat(JSON.readTree(send(client, "/knowledge-bases", "POST", body, key)
                                            .body())
                                    .at("/task/id")
                                    .asText())
                            .isEqualTo(taskId);
                    assertThat(read(client, "/knowledge-bases").path("items").size())
                            .isZero();
                }
                try (ConfigurableApplicationContext app = start(directory)) {
                    assertThat(read(client(app), "/knowledge-bases")
                                    .path("items")
                                    .size())
                            .isZero();
                }
                assertThat(deletes.get()).isZero();
                assertThat(puts.get()).isEqualTo(mode.equals("existing") ? 0 : 1);
            } finally {
                vector.stop(0);
            }
        }
    }

    @Test
    @DisplayName("未知字段及非法处理配置被原子拒绝且合法元数据原样保存")
    void rejectsInvalidInputWithoutSideEffects() throws Exception {
        HttpServer vector = vector();
        Path directory = Files.createTempDirectory(Path.of("target"), "knowledge-validation-")
                .toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = configure(client, vector.getAddress().getPort());
            String body = "{\"name\":\"校验库\",\"processing\":" + processing + "}";
            for (String invalid : java.util.List.of(
                    "null",
                    "[]",
                    "{}",
                    body.replace("\"校验库\"", "null"),
                    body.replace("\"校验库\"", "\" \""),
                    body.replace("\"name\"", "\"unknown\""),
                    body.replace("\"structure\"", "\"unknown\""),
                    body.replace("\"overlapCodePoints\":200", "\"overlapCodePoints\":2000"),
                    body.replace("\"overlapCodePoints\":200", "\"overlapCodePoints\":-1"),
                    body.replace("\"removePageHeadersFooters\":false", "\"removePageHeadersFooters\":null"),
                    body.replace("\"customMetadata\":{}", "\"customMetadata\":{\"bad\":\"x\"}"),
                    body.replace("\"embeddedMetadataFields\":[]", "\"embeddedMetadataFields\":[\"user_missing\"]"),
                    body.replace("\"dimensions\":3", "\"dimensions\":0"),
                    body.replace("\"candidatesPerQuery\":10", "\"candidatesPerQuery\":1001"),
                    body.replace("\"distance\":\"cosine\"", "\"distance\":\"wrong\""),
                    body.replace("\"similarityThreshold\":0.5", "\"similarityThreshold\":\"0.5\""),
                    body.replace("\"modelSnapshot\":{", "\"modelSnapshot\":{\"unknown\":true,"),
                    body.replace("\"model\":\"固定模型\"", "\"model\":\"\""),
                    body.replace("\"processing\":{", "\"processing\":{\"extra\":true,"))) {
                HttpResponse<String> response = send(
                        client,
                        "/knowledge-bases",
                        "POST",
                        invalid,
                        UUID.randomUUID().toString());
                assertThat(response.statusCode()).as(invalid + response.body()).isEqualTo(400);
                assertThat(read(client, "/knowledge-bases").path("items").size())
                        .isZero();
            }
            HttpResponse<String> mismatch = send(
                    client,
                    "/knowledge-bases",
                    "POST",
                    body.replace("固定模型", "伪造模型"),
                    UUID.randomUUID().toString());
            assertThat(mismatch.statusCode()).isEqualTo(422);
            assertThat(send(
                                    client,
                                    "/knowledge-bases",
                                    "POST",
                                    body.substring(0, body.length() - 1) + ",\"datasetIds\":[\"" + UUID.randomUUID()
                                            + "\"]}",
                                    UUID.randomUUID().toString())
                            .statusCode())
                    .isEqualTo(404);
            String legal = body.replace("\"customMetadata\":{}", "\"customMetadata\":{\"user_topic\":\"中文主题\"}")
                    .replace("\"embeddedMetadataFields\":[]", "\"embeddedMetadataFields\":[\"user_topic\"]");
            HttpResponse<String> response = send(
                    client, "/knowledge-bases", "POST", legal, UUID.randomUUID().toString());
            String id = await(
                            client,
                            JSON.readTree(response.body()).at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            assertThat(read(client, "/knowledge-bases/" + id + "/processing-config")
                            .at("/input/chunking/customMetadata/user_topic")
                            .asText())
                    .isEqualTo("中文主题");
            for (String invalid : java.util.List.of(
                    "{}", "null", "{\"name\":null}", "{\"name\":\" \"}", "{\"description\":null}", "{\"unknown\":1}"))
                assertThat(send(client, "/knowledge-bases/" + id, "PATCH", invalid, null)
                                .statusCode())
                        .isEqualTo(400);
            for (String invalid : java.util.List.of(
                    "null",
                    "{}",
                    "{\"type\":\"wrong\",\"resourceId\":\"" + id + "\"}",
                    "{\"type\":\"file\",\"resourceId\":\"invalid\"}",
                    "{\"type\":\"file\",\"resourceId\":\"" + id + "\",\"unknown\":1}"))
                assertThat(send(client, "/knowledge-bases/" + id + "/sources", "POST", invalid, null)
                                .statusCode())
                        .isEqualTo(400);
            assertThat(send(
                                    client,
                                    "/knowledge-bases/" + id + "/sources",
                                    "POST",
                                    "{\"type\":\"file\",\"resourceId\":\"" + UUID.randomUUID() + "\"}",
                                    null)
                            .statusCode())
                    .isEqualTo(404);
            assertThat(read(client, "/knowledge-bases/" + id + "/sources")
                            .path("items")
                            .size())
                    .isZero();
        } finally {
            vector.stop(0);
        }
    }

    private JsonNode read(Client client, String path) throws Exception {
        HttpResponse<String> response = send(client, path, "GET", null, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(Client client, String path, String method, String body, String key)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(client.base() + path));
        if (client.cookie() != null) request.header("Cookie", client.cookie());
        if (client.token() != null) request.header("X-CSRF-Token", client.token());
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        HttpResponse<String> response = http.send(
                request.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        observed(response, body);
        return response;
    }

    @Test
    @DisplayName("超过执行队列容量的补偿记录不阻断重启与本地资料管理")
    void manyCompensationsDoNotBlockStartup() throws Exception {
        java.util.Set<String> collections = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicBoolean allowDelete = new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer vector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vector.createContext("/collections/", exchange -> {
            String name = exchange.getRequestURI().getPath(), body = "{\"status\":\"ok\",\"result\":true}";
            int status = 200;
            switch (exchange.getRequestMethod()) {
                case "PUT" -> collections.add(name);
                case "DELETE" -> {
                    if (allowDelete.get()) {
                        try {
                            Thread.sleep(30);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        collections.remove(name);
                    } else body = "{\"status\":\"ok\",\"result\":false}";
                }
                default -> {
                    if (!collections.contains(name)) {
                        status = 404;
                        body = "{}";
                    } else
                        body =
                                "{\"status\":\"ok\",\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":4,\"distance\":\"Cosine\"}}}}}";
                }
            }
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        vector.start();
        Path directory = Files.createTempDirectory(Path.of("target"), "knowledge-many-cleanups-")
                .toAbsolutePath();
        try {
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                String processing = configure(client, vector.getAddress().getPort());
                for (int i = 0; i < 20; i++) {
                    HttpResponse<String> response = send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"补偿" + i + "\",\"processing\":" + processing + "}",
                            UUID.randomUUID().toString());
                    JsonNode task = await(
                            client,
                            JSON.readTree(response.body()).at("/task/id").asText());
                    assertThat(task.path("failure").path("code").asText()).isEqualTo("INTEGRITY_FAILED");
                }
                assertThat(collections.size()).isEqualTo(20);
            }
            allowDelete.set(true);
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                assertThat(read(client, "/system/status").path("writeState").asText())
                        .isEqualTo("ready");
                assertThat(send(client, "/datasets", "POST", "{\"name\":\"恢复期间仍可保存资料\"}", null)
                                .statusCode())
                        .isEqualTo(201);
                for (int i = 0; i < 300 && !collections.isEmpty(); i++) Thread.sleep(20);
                assertThat(collections).isEmpty();
            }
        } finally {
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("八操作严格执行会话保护、查询边界及本机存储故障")
    void everyOperationRejectsUnsafeRequests() throws Exception {
        HttpServer vector = vector();
        Path directory = Files.createTempDirectory(Path.of("target"), "knowledge-security-")
                .toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = configure(client, vector.getAddress().getPort());
            String body = "{\"name\":\"安全库\",\"processing\":" + processing + "}";
            HttpResponse<String> response = send(
                    client, "/knowledge-bases", "POST", body, UUID.randomUUID().toString());
            String task = JSON.readTree(response.body()).at("/task/id").asText();
            String id = await(client, task).path("resultResourceId").asText();
            java.util.List<String[]> operations = java.util.List.of(
                    new String[] {"GET", "/knowledge-bases", null},
                    new String[] {"POST", "/knowledge-bases", body},
                    new String[] {"GET", "/knowledge-bases/" + id, null},
                    new String[] {"PATCH", "/knowledge-bases/" + id, "{\"description\":\"说明\"}"},
                    new String[] {"GET", "/knowledge-bases/" + id + "/processing-config", null},
                    new String[] {"GET", "/knowledge-bases/" + id + "/sources", null},
                    new String[] {
                        "POST",
                        "/knowledge-bases/" + id + "/sources",
                        "{\"type\":\"file\",\"resourceId\":\"" + id + "\"}"
                    },
                    new String[] {"GET", "/knowledge-bases/" + id + "/files", null},
                    new String[] {"GET", "/tasks/" + task, null});
            for (String[] operation : operations) {
                HttpRequest.Builder unsafe = HttpRequest.newBuilder(URI.create(client.base() + operation[1]))
                        .header("Cookie", client.cookie())
                        .header("Origin", "https://untrusted.example")
                        .header("X-CSRF-Token", client.token())
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .method(
                                operation[0],
                                operation[2] == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(operation[2]));
                HttpResponse<String> forbidden = http.send(unsafe.build(), HttpResponse.BodyHandlers.ofString());
                observed(forbidden, operation[2]);
                assertThat(forbidden.statusCode()).isEqualTo(403);
                assertThat(send(
                                        new Client(client.base(), null, null),
                                        operation[1],
                                        operation[0],
                                        operation[2],
                                        UUID.randomUUID().toString())
                                .statusCode())
                        .isEqualTo(401);
                if (!operation[0].equals("GET"))
                    assertThat(send(
                                            new Client(client.base(), client.cookie(), null),
                                            operation[1],
                                            operation[0],
                                            operation[2],
                                            UUID.randomUUID().toString())
                                    .statusCode())
                            .isEqualTo(403);
                assertThat(send(
                                        client,
                                        operation[1] + "?unknown=1",
                                        operation[0],
                                        operation[2],
                                        UUID.randomUUID().toString())
                                .statusCode())
                        .isEqualTo(400);
            }
            for (String path : java.util.List.of(
                    "/knowledge-bases", "/knowledge-bases/" + id + "/sources", "/knowledge-bases/" + id + "/files"))
                for (String query : java.util.List.of(
                        "?limit=0", "?limit=101", "?limit=no", "?limit=1&limit=2", "?cursor=invalid", "?cursor="))
                    assertThat(send(client, path + query, "GET", null, null).statusCode())
                            .isEqualTo(400);
            String absent = UUID.randomUUID().toString();
            for (String path : java.util.List.of(
                    "/knowledge-bases/" + absent,
                    "/knowledge-bases/" + absent + "/sources",
                    "/knowledge-bases/" + absent + "/files",
                    "/knowledge-bases/" + absent + "/processing-config"))
                assertThat(send(client, path, "GET", null, null).statusCode()).isEqualTo(404);
            assertThat(send(client, "/knowledge-bases/" + absent, "PATCH", "{\"description\":\"说明\"}", null)
                            .statusCode())
                    .isEqualTo(404);
            assertThat(send(
                                    client,
                                    "/knowledge-bases/" + absent + "/sources",
                                    "POST",
                                    "{\"type\":\"file\",\"resourceId\":\"" + id + "\"}",
                                    null)
                            .statusCode())
                    .isEqualTo(404);
            assertThat(send(client, "/knowledge-bases", "POST", body, null).statusCode())
                    .isEqualTo(400);
            try (java.sql.Connection db = java.sql.DriverManager.getConnection(
                            "jdbc:sqlite:" + directory.resolve("application.sqlite"));
                    java.sql.Statement statement = db.createStatement()) {
                statement.execute("ALTER TABLE knowledge_bases RENAME TO blocked_knowledge_bases");
                for (String[] operation : operations)
                    if (!operation[1].startsWith("/tasks/"))
                        assertThat(send(
                                                client,
                                                operation[1],
                                                operation[0],
                                                operation[2],
                                                UUID.randomUUID().toString())
                                        .statusCode())
                                .isEqualTo(500);
                statement.execute("ALTER TABLE blocked_knowledge_bases RENAME TO knowledge_bases");
            }
        } finally {
            vector.stop(0);
        }
    }

    private Client client(ConfigurableApplicationContext app) throws Exception {
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";
        HttpResponse<String> response = send(new Client(base, null, null), "/session", "GET", null, null);
        return new Client(
                base,
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(response.body()).get("csrfToken").asText());
    }

    private ConfigurableApplicationContext start(Path directory) {
        ConfigurableApplicationContext application = new SpringApplication(KnowledgePoolApplication.class)
                .run("--server.port=0", "--know-pool.data-directory=" + directory);
        if (ROUTES.isEmpty())
            application
                    .getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
                    .getHandlerMethods()
                    .forEach((mapping, handler) -> {
                        for (String path : mapping.getPatternValues())
                            for (org.springframework.web.bind.annotation.RequestMethod method :
                                    mapping.getMethodsCondition().getMethods())
                                ROUTES.add(java.util.Map.of("method", method.name(), "path", path));
                    });
        return application;
    }

    private static synchronized void observed(HttpResponse<String> response, String body) {
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put(
                "path",
                response.request().uri().getRawPath()
                        + (response.request().uri().getRawQuery() == null
                                ? ""
                                : "?" + response.request().uri().getRawQuery()));
        item.put("method", response.request().method());
        item.put("status", response.statusCode());
        item.put("contentType", response.headers().firstValue("Content-Type").orElse(""));
        item.put("response", JSON.readTree(response.body()));
        item.put("body", body);
        String behavior = java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                .filter(frame -> frame.getClassName().equals(KnowledgeBasesTest.class.getName()))
                .map(StackTraceElement::getMethodName)
                .filter(name -> !java.util.Set.of("observed", "send", "read", "await", "client", "configure")
                        .contains(name))
                .findFirst()
                .orElse("");
        item.put("behavior", behavior);
        java.util.Map<String, String> parameters = new java.util.LinkedHashMap<>();
        response.request().headers().map().forEach((name, values) -> parameters.put(name, values.getFirst()));
        item.put("parameters", parameters);
        OBSERVATIONS.add(item);
    }

    @AfterAll
    static void evidence() throws Exception {
        if (OBSERVATIONS.isEmpty()) return;
        OBSERVATIONS.getFirst().put("routes", ROUTES);
        for (java.util.Map<String, Object> item : OBSERVATIONS)
            if (!PASSED.contains((String) item.get("behavior"))) item.put("behavior", "");
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."))
                .toAbsolutePath()
                .normalize();
        Process process = new ProcessBuilder(
                        "python",
                        "server/src/test/resources/contract/settings-evidence.py",
                        root.toString(),
                        "knowledge-bases")
                .directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try (var output = process.getOutputStream()) {
            output.write(JSON.writeValueAsBytes(OBSERVATIONS));
        }
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(result).isZero();
    }

    private record Client(String base, String cookie, String token) {}
}
