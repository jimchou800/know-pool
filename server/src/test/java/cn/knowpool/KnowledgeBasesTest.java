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
    @DisplayName("空库索引任务历史通过真实公开接口分页读取")
    void emptyIndexTaskHistory() throws Exception {
        HttpServer vector = vector();
        Path directory =
                Files.createTempDirectory(Path.of("target"), "index-history-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = configure(client, vector.getAddress().getPort());
            JsonNode accepted = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"索引历史\",\"processing\":" + processing + "}",
                            UUID.randomUUID().toString())
                    .body());
            String kb = await(client, accepted.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            HttpResponse<String> response =
                    send(client, "/knowledge-bases/" + kb + "/tasks?limit=1", "GET", null, null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(JSON.readTree(response.body()).path("items").isEmpty()).isTrue();
            assertThat(JSON.readTree(response.body()).path("nextCursor").isNull())
                    .isTrue();
        } finally {
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("关联纯文本后真实嵌入并提交生效版本")
    void textIndexBecomesEffective() throws Exception {
        var embeddingFailure = new java.util.concurrent.atomic.AtomicBoolean();
        var failedCalls = new java.util.concurrent.atomic.AtomicInteger();
        var vectorFailure = new java.util.concurrent.atomic.AtomicBoolean();
        var vectorFailedCalls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer vector = indexedVector(embeddingFailure, failedCalls, vectorFailure, vectorFailedCalls);
        Path directory =
                Files.createTempDirectory(Path.of("target"), "text-index-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = configure(client, vector.getAddress().getPort()).replace("固定模型", "text-embedding-v4");
            send(
                    client,
                    "/settings/models/embedding",
                    "PUT",
                    "{\"endpoint\":\"http://127.0.0.1:" + vector.getAddress().getPort()
                            + "/v1\",\"model\":\"text-embedding-v4\",\"contextWindowTokens\":null,\"maxOutputTokens\":null,\"credential\":{\"action\":\"replace\",\"value\":\"fixed-test-key\"}}",
                    null);
            JsonNode saved = read(client, "/settings/models/embedding");
            processing = processing.replace(
                    JSON.readTree(processing)
                            .at("/embedding/modelConfigVersionId")
                            .asText(),
                    saved.path("versionId").asText());
            String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"索引来源\"}", null)
                            .body())
                    .path("id")
                    .asText();
            upload(client, dataset);
            JsonNode accepted = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"文本索引\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                    + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            String kb = await(client, accepted.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            JsonNode item = read(client, "/knowledge-bases/" + kb + "/files")
                    .path("items")
                    .get(0);
            JsonNode task = await(client, item.at("/task/id").asText());
            assertThat(task.path("state").asText()).as(task.toString()).isEqualTo("succeeded");
            JsonNode effective = read(client, "/knowledge-bases/" + kb + "/files")
                    .path("items")
                    .get(0);
            assertThat(effective.path("effectiveIndexVersionId").asText())
                    .isEqualTo(task.path("resultResourceId").asText());
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("searchableFileCount")
                            .asInt())
                    .isEqualTo(1);
            String searchKey = UUID.randomUUID().toString();
            HttpResponse<String> search = send(
                    client,
                    "/knowledge-bases/" + kb + "/search-test",
                    "POST",
                    "{\"query\":\"固定资料\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                    searchKey);
            assertThat(search.statusCode()).as(search.body()).isEqualTo(202);
            JsonNode request = JSON.readTree(search.body());
            await(client, request.at("/task/id").asText());
            JsonNode result =
                    read(client, "/search-requests/" + request.path("id").asText());
            assertThat(result.at("/result/outcome").asText())
                    .as(result.toString())
                    .isEqualTo("matches");
            HttpResponse<String> replayed = send(
                    client,
                    "/knowledge-bases/" + kb + "/search-test",
                    "POST",
                    "{\"query\":\"固定资料\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                    searchKey);
            assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(202);
            assertThat(JSON.readTree(replayed.body())).isEqualTo(result);
            JsonNode evidence = result.at("/result/evidence/0");
            assertThat(evidence.path("text").asText()).isEqualTo("固定资料");
            assertThat(evidence.path("knowledgeBaseId").asText()).isEqualTo(kb);
            assertThat(evidence.path("fileId").asText())
                    .isEqualTo(item.path("fileId").asText());
            assertThat(evidence.path("contentVersionId").asText())
                    .isEqualTo(task.path("targetContentVersionId").asText());
            assertThat(evidence.path("configVersionId").asText())
                    .isEqualTo(task.path("targetConfigVersionId").asText());
            assertThat(evidence.path("indexVersionId").asText())
                    .isEqualTo(task.path("resultResourceId").asText());
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("publicationState")
                            .asText())
                    .isEqualTo("unpublished");
            assertThat(read(
                                    client,
                                    "/search-requests/" + request.path("id").asText() + "/evidence/"
                                            + evidence.path("id").asText())
                            .path("text")
                            .asText())
                    .isEqualTo("固定资料");
            var queryUnavailable = new java.util.concurrent.atomic.AtomicBoolean();
            vector.createContext("/collections/kp_" + kb.replace("-", "") + "/points/query", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = (queryUnavailable.get() ? "{}" : "{\"status\":\"ok\",\"result\":{\"points\":[]}}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(queryUnavailable.get() ? 503 : 200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JsonNode emptyRequest = JSON.readTree(send(
                            client,
                            "/knowledge-bases/" + kb + "/search-test",
                            "POST",
                            "{\"query\":\"无命中查询\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            assertThat(await(client, emptyRequest.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            JsonNode emptyResult =
                    read(client, "/search-requests/" + emptyRequest.path("id").asText());
            assertThat(emptyResult.at("/result/outcome").asText()).isEqualTo("no_matches");
            assertThat(emptyResult.at("/result/evidence").isEmpty()).isTrue();
            assertThat(send(
                                    client,
                                    "/search-requests/"
                                            + emptyRequest.path("id").asText() + "/evidence/"
                                            + evidence.path("id").asText(),
                                    "GET",
                                    null,
                                    null)
                            .statusCode())
                    .isEqualTo(404);
            JsonNode replacement = uploadText(client, dataset, "替换资料");
            assertThat(replacement.at("/items/0/indexTaskIds").size()).isEqualTo(1);
            JsonNode newTask =
                    await(client, replacement.at("/items/0/indexTaskIds/0").asText());
            assertThat(newTask.path("state").asText()).isEqualTo("succeeded");
            assertThat(newTask.path("targetContentVersionId").asText())
                    .isNotEqualTo(task.path("targetContentVersionId").asText());
            assertThat(read(client, "/search-requests/" + request.path("id").asText())
                            .at("/result/evidence/0"))
                    .isEqualTo(evidence);
            assertThat(read(
                            client,
                            "/search-requests/" + request.path("id").asText() + "/evidence/"
                                    + evidence.path("id").asText()))
                    .isEqualTo(evidence);
            embeddingFailure.set(true);
            JsonNode failedUpload = uploadText(client, dataset, "失败更新保留旧索引");
            JsonNode failedTask =
                    await(client, failedUpload.at("/items/0/indexTaskIds/0").asText());
            assertThat(failedTask.path("state").asText()).isEqualTo("failed");
            assertThat(failedTask.at("/failure/code").asText()).isEqualTo("MODEL_UNAVAILABLE");
            assertThat(failedCalls.get()).isEqualTo(3);
            JsonNode retained =
                    read(client, "/knowledge-bases/" + kb + "/files").at("/items/0");
            assertThat(retained.path("effectiveIndexVersionId").asText())
                    .isEqualTo(newTask.path("resultResourceId").asText());
            assertThat(retained.path("usingPreviousVersion").asBoolean()).isTrue();
            JsonNode modelRequest = JSON.readTree(send(
                            client,
                            "/knowledge-bases/" + kb + "/search-test",
                            "POST",
                            "{\"query\":\"模型故障查询\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            assertThat(await(client, modelRequest.at("/task/id").asText())
                            .at("/failure/code")
                            .asText())
                    .isEqualTo("MODEL_UNAVAILABLE");
            assertThat(failedCalls.get()).isEqualTo(4);
            assertThat(read(
                                    client,
                                    "/search-requests/"
                                            + modelRequest.path("id").asText())
                            .path("result")
                            .isNull())
                    .isTrue();
            embeddingFailure.set(false);
            queryUnavailable.set(true);
            JsonNode vectorRequest = JSON.readTree(send(
                            client,
                            "/knowledge-bases/" + kb + "/search-test",
                            "POST",
                            "{\"query\":\"向量故障查询\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            assertThat(await(client, vectorRequest.at("/task/id").asText())
                            .at("/failure/code")
                            .asText())
                    .isEqualTo("VECTOR_UNAVAILABLE");
            assertThat(read(
                                    client,
                                    "/search-requests/"
                                            + vectorRequest.path("id").asText())
                            .at("/scope/omitted/0/reason")
                            .asText())
                    .isEqualTo("unavailable");
            vectorFailure.set(true);
            JsonNode vectorUpload = uploadText(client, dataset, "向量失败保留旧索引");
            JsonNode vectorTask =
                    await(client, vectorUpload.at("/items/0/indexTaskIds/0").asText());
            assertThat(vectorTask.at("/failure/code").asText()).isEqualTo("VECTOR_UNAVAILABLE");
            assertThat(vectorFailedCalls.get()).isEqualTo(3);
            var taskIds = new java.util.HashSet<String>();
            String cursor = null;
            do {
                JsonNode page = read(
                        client,
                        "/knowledge-bases/" + kb + "/tasks?limit=1" + (cursor == null ? "" : "&cursor=" + cursor));
                assertThat(page.path("items").size()).isEqualTo(1);
                JsonNode listed = page.at("/items/0");
                assertThat(listed.path("kind").asText()).isEqualTo("index_file");
                assertThat(listed.path("knowledgeBaseId").asText()).isEqualTo(kb);
                assertThat(taskIds.add(listed.path("id").asText())).isTrue();
                cursor = page.path("nextCursor").isNull()
                        ? null
                        : page.path("nextCursor").asText();
            } while (cursor != null);
            assertThat(taskIds)
                    .containsExactlyInAnyOrder(
                            task.path("id").asText(),
                            newTask.path("id").asText(),
                            failedTask.path("id").asText(),
                            vectorTask.path("id").asText());
            assertThat(read(client, "/knowledge-bases/" + kb + "/files")
                            .at("/items/0/effectiveIndexVersionId")
                            .asText())
                    .isEqualTo(newTask.path("resultResourceId").asText());
        } finally {
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("管理检索无索引失败保持幂等身份并在重启后明确失效")
    void managementSearchFailureIsStableAndExpiresOnRestart() throws Exception {
        HttpServer vector = vector();
        Path directory =
                Files.createTempDirectory(Path.of("target"), "search-expiry-").toAbsolutePath();
        String requestId;
        try {
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                String processing = configure(client, vector.getAddress().getPort());
                JsonNode accepted = JSON.readTree(send(
                                client,
                                "/knowledge-bases",
                                "POST",
                                "{\"name\":\"无索引管理库\",\"processing\":" + processing + "}",
                                UUID.randomUUID().toString())
                        .body());
                String kb = await(client, accepted.at("/task/id").asText())
                        .path("resultResourceId")
                        .asText();
                String path = "/knowledge-bases/" + kb + "/search-test";
                String input = "{\"query\":\"固定查询\",\"knowledgeBaseIds\":[\"" + kb + "\"]}";
                String key = UUID.randomUUID().toString();
                HttpResponse<String> response = send(client, path, "POST", input, key);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
                JsonNode request = JSON.readTree(response.body());
                requestId = request.path("id").asText();
                JsonNode task = await(client, request.at("/task/id").asText());
                assertThat(task.path("state").asText()).isEqualTo("failed");
                assertThat(task.at("/failure/code").asText()).isEqualTo("NO_EFFECTIVE_INDEX");
                JsonNode result = read(client, "/search-requests/" + requestId);
                assertThat(result.path("result").isNull()).isTrue();
                assertThat(result.at("/scope/omitted/0/reason").asText()).isEqualTo("no_effective_index");
                assertThat(JSON.readTree(send(client, path, "POST", input, key).body())
                                .path("id")
                                .asText())
                        .isEqualTo(requestId);
                assertThat(send(client, path, "POST", input.replace("固定查询", "不同查询"), key)
                                .statusCode())
                        .isEqualTo(409);
                for (String invalid : new String[] {
                    "null",
                    "{}",
                    "{\"query\":\"固定\"}",
                    "{\"query\":false,\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                    "{\"query\":\" \",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                    "{\"query\":\"固定\",\"knowledgeBaseIds\":[]}",
                    "{\"query\":\"固定\",\"knowledgeBaseIds\":[\"" + UUID.randomUUID() + "\"]}",
                    "{\"query\":\"固定\",\"knowledgeBaseIds\":[false]}"
                }) {
                    assertThat(send(
                                            client,
                                            path,
                                            "POST",
                                            invalid,
                                            UUID.randomUUID().toString())
                                    .statusCode())
                            .as(invalid)
                            .isEqualTo(400);
                }
                assertThat(send(client, path, "POST", input, "invalid").statusCode())
                        .isEqualTo(400);
                assertThat(send(
                                        client,
                                        path + "?unexpected=1",
                                        "POST",
                                        input,
                                        UUID.randomUUID().toString())
                                .statusCode())
                        .isEqualTo(400);
                assertThat(send(
                                        client,
                                        "/search-requests/" + requestId + "/evidence/" + UUID.randomUUID(),
                                        "GET",
                                        null,
                                        null)
                                .statusCode())
                        .isEqualTo(404);
                assertThat(send(client, "/search-requests/invalid", "GET", null, null)
                                .statusCode())
                        .isEqualTo(400);
                assertThat(send(client, "/search-requests/" + UUID.randomUUID(), "GET", null, null)
                                .statusCode())
                        .isEqualTo(404);
            }
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                HttpResponse<String> expired = send(client, "/search-requests/" + requestId, "GET", null, null);
                assertThat(expired.statusCode()).as(expired.body()).isEqualTo(410);
                assertThat(JSON.readTree(expired.body()).path("code").asText()).isEqualTo("RESULT_EXPIRED");
                assertThat(send(
                                        client,
                                        "/search-requests/" + requestId + "/evidence/" + UUID.randomUUID(),
                                        "GET",
                                        null,
                                        null)
                                .statusCode())
                        .isEqualTo(410);
            }
        } finally {
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("管理检索队列有界且重启中断在途请求而不恢复结果")
    void managementQueueAndRestartAreSafe() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var embeddingCalls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer vector = indexedVector(
                new java.util.concurrent.atomic.AtomicBoolean(),
                new java.util.concurrent.atomic.AtomicInteger(),
                new java.util.concurrent.atomic.AtomicBoolean(),
                new java.util.concurrent.atomic.AtomicInteger(),
                entered,
                release,
                path -> {
                    if (path.equals("/v1/embeddings")) embeddingCalls.incrementAndGet();
                });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "search-recovery-").toAbsolutePath();
        String requestId;
        String taskId;
        try {
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                String processing =
                        configure(client, vector.getAddress().getPort()).replace("固定模型", "text-embedding-v4");
                send(
                        client,
                        "/settings/models/embedding",
                        "PUT",
                        "{\"endpoint\":\"http://127.0.0.1:"
                                + vector.getAddress().getPort()
                                + "/v1\",\"model\":\"text-embedding-v4\",\"contextWindowTokens\":null,\"maxOutputTokens\":null,\"credential\":{\"action\":\"replace\",\"value\":\"fixed-test-key\"}}",
                        null);
                processing = processing.replace(
                        JSON.readTree(processing)
                                .at("/embedding/modelConfigVersionId")
                                .asText(),
                        read(client, "/settings/models/embedding")
                                .path("versionId")
                                .asText());
                String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"恢复来源\"}", null)
                                .body())
                        .path("id")
                        .asText();
                upload(client, dataset);
                JsonNode accepted = JSON.readTree(send(
                                client,
                                "/knowledge-bases",
                                "POST",
                                "{\"name\":\"恢复管理库\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                        + "\"]}",
                                UUID.randomUUID().toString())
                        .body());
                String kb = await(client, accepted.at("/task/id").asText())
                        .path("resultResourceId")
                        .asText();
                assertThat(await(
                                        client,
                                        read(client, "/knowledge-bases/" + kb + "/files")
                                                .at("/items/0/task/id")
                                                .asText())
                                .path("state")
                                .asText())
                        .isEqualTo("succeeded");
                String path = "/knowledge-bases/" + kb + "/search-test";
                String input = "{\"query\":\"固定查询\",\"knowledgeBaseIds\":[\"" + kb + "\"]}";
                String key = UUID.randomUUID().toString();
                JsonNode first =
                        JSON.readTree(send(client, path, "POST", input, key).body());
                requestId = first.path("id").asText();
                taskId = first.at("/task/id").asText();
                assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                for (int i = 1; i < 17; i++)
                    assertThat(send(
                                            client,
                                            path,
                                            "POST",
                                            input,
                                            UUID.randomUUID().toString())
                                    .statusCode())
                            .isEqualTo(202);
                assertThat(send(client, path, "POST", input, UUID.randomUUID().toString())
                                .statusCode())
                        .isEqualTo(429);
                assertThat(JSON.readTree(send(client, path, "POST", input, key).body())
                                .path("id")
                                .asText())
                        .isEqualTo(requestId);
                assertThat(embeddingCalls.get()).isEqualTo(2);
            }
            release.countDown();
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                JsonNode task = read(client, "/tasks/" + taskId);
                assertThat(task.path("state").asText()).isEqualTo("interrupted");
                assertThat(task.at("/failure/code").asText()).isEqualTo("TASK_INTERRUPTED");
                assertThat(task.path("resultResourceId").isNull()).isTrue();
                assertThat(embeddingCalls.get()).isEqualTo(2);
                assertThat(send(client, "/search-requests/" + requestId, "GET", null, null)
                                .statusCode())
                        .isEqualTo(410);
            }
        } finally {
            release.countDown();
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("持久嵌入向量在重启后继续幂等写入而不再次调用模型")
    void persistedEmbeddingResumesWithoutModelCall() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var embeddingCalls = new java.util.concurrent.atomic.AtomicInteger();
        var block = new java.util.concurrent.atomic.AtomicBoolean(true);
        HttpServer vector = indexedVector(
                new java.util.concurrent.atomic.AtomicBoolean(),
                new java.util.concurrent.atomic.AtomicInteger(),
                new java.util.concurrent.atomic.AtomicBoolean(),
                new java.util.concurrent.atomic.AtomicInteger(),
                null,
                null,
                path -> {
                    if (path.equals("/v1/embeddings")) embeddingCalls.incrementAndGet();
                    if (path.endsWith("/points") && block.get()) {
                        entered.countDown();
                        try {
                            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "index-recovery-").toAbsolutePath();
        String taskId;
        String kb;
        try {
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                String processing =
                        configure(client, vector.getAddress().getPort()).replace("固定模型", "text-embedding-v4");
                send(
                        client,
                        "/settings/models/embedding",
                        "PUT",
                        "{\"endpoint\":\"http://127.0.0.1:"
                                + vector.getAddress().getPort()
                                + "/v1\",\"model\":\"text-embedding-v4\",\"contextWindowTokens\":null,\"maxOutputTokens\":null,\"credential\":{\"action\":\"replace\",\"value\":\"fixed-test-key\"}}",
                        null);
                processing = processing.replace(
                        JSON.readTree(processing)
                                .at("/embedding/modelConfigVersionId")
                                .asText(),
                        read(client, "/settings/models/embedding")
                                .path("versionId")
                                .asText());
                String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"恢复索引来源\"}", null)
                                .body())
                        .path("id")
                        .asText();
                upload(client, dataset);
                JsonNode accepted = JSON.readTree(send(
                                client,
                                "/knowledge-bases",
                                "POST",
                                "{\"name\":\"恢复索引库\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                        + "\"]}",
                                UUID.randomUUID().toString())
                        .body());
                kb = await(client, accepted.at("/task/id").asText())
                        .path("resultResourceId")
                        .asText();
                taskId = read(client, "/knowledge-bases/" + kb + "/files")
                        .at("/items/0/task/id")
                        .asText();
                assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                assertThat(embeddingCalls.get()).isEqualTo(1);
                assertThat(read(client, "/tasks/" + taskId).path("state").asText())
                        .isEqualTo("running");
            }
            block.set(false);
            release.countDown();
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                JsonNode task = await(client, taskId);
                assertThat(task.path("state").asText()).as(task.toString()).isEqualTo("succeeded");
                assertThat(embeddingCalls.get()).isEqualTo(1);
                assertThat(read(client, "/knowledge-bases/" + kb + "/files")
                                .at("/items/0/effectiveIndexVersionId")
                                .asText())
                        .isEqualTo(task.path("resultResourceId").asText());
            }
        } finally {
            release.countDown();
            vector.stop(0);
        }
    }

    private HttpServer indexedVector() throws Exception {
        return indexedVector(
                new java.util.concurrent.atomic.AtomicBoolean(), new java.util.concurrent.atomic.AtomicInteger(),
                new java.util.concurrent.atomic.AtomicBoolean(), new java.util.concurrent.atomic.AtomicInteger());
    }

    private HttpServer indexedVector(
            java.util.concurrent.atomic.AtomicBoolean embeddingFailure,
            java.util.concurrent.atomic.AtomicInteger failedCalls,
            java.util.concurrent.atomic.AtomicBoolean vectorFailure,
            java.util.concurrent.atomic.AtomicInteger vectorFailedCalls)
            throws Exception {
        return indexedVector(embeddingFailure, failedCalls, vectorFailure, vectorFailedCalls, null, null);
    }

    private HttpServer indexedVector(
            java.util.concurrent.atomic.AtomicBoolean embeddingFailure,
            java.util.concurrent.atomic.AtomicInteger failedCalls,
            java.util.concurrent.atomic.AtomicBoolean vectorFailure,
            java.util.concurrent.atomic.AtomicInteger vectorFailedCalls,
            java.util.concurrent.CountDownLatch entered,
            java.util.concurrent.CountDownLatch release)
            throws Exception {
        return indexedVector(
                embeddingFailure, failedCalls, vectorFailure, vectorFailedCalls, entered, release, path -> {});
    }

    private HttpServer indexedVector(
            java.util.concurrent.atomic.AtomicBoolean embeddingFailure,
            java.util.concurrent.atomic.AtomicInteger failedCalls,
            java.util.concurrent.atomic.AtomicBoolean vectorFailure,
            java.util.concurrent.atomic.AtomicInteger vectorFailedCalls,
            java.util.concurrent.CountDownLatch entered,
            java.util.concurrent.CountDownLatch release,
            java.util.function.Consumer<String> beforeRequest)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.Set<String> collections = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Map<String, JsonNode> points = new java.util.concurrent.ConcurrentHashMap<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            beforeRequest.accept(path);
            JsonNode body = exchange.getRequestBody().available() == 0
                    ? JSON.createObjectNode()
                    : JSON.readTree(exchange.getRequestBody().readAllBytes());
            String response;
            int status = 200;
            if (path.equals("/v1/embeddings")) {
                if (embeddingFailure.get()) {
                    failedCalls.incrementAndGet();
                    byte[] error =
                            "{\"error\":{\"message\":\"固定临时故障\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(503, error.length);
                    exchange.getResponseBody().write(error);
                    exchange.close();
                    return;
                }
                response =
                        "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,0,0]}],\"model\":\"text-embedding-v4\",\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4}}";
            } else if (path.endsWith("/points") && exchange.getRequestMethod().equals("PUT")) {
                if (vectorFailure.get()) {
                    vectorFailedCalls.incrementAndGet();
                    byte[] error = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(503, error.length);
                    exchange.getResponseBody().write(error);
                    exchange.close();
                    return;
                }
                for (JsonNode point : body.path("points"))
                    points.put(point.path("id").asText(), point);
                response = "{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}";
            } else if (path.endsWith("/points") && exchange.getRequestMethod().equals("POST")) {
                var result = JSON.createArrayNode();
                for (JsonNode id : body.path("ids"))
                    if (points.containsKey(id.asText())) result.add(points.get(id.asText()));
                response = JSON.writeValueAsString(java.util.Map.of("status", "ok", "result", result));
            } else if (path.endsWith("/points/query")) {
                if (entered != null) {
                    entered.countDown();
                    try {
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                var result = JSON.createArrayNode();
                for (JsonNode point : points.values())
                    result.add(((tools.jackson.databind.node.ObjectNode) point.deepCopy()).put("score", 0.9));
                response = JSON.writeValueAsString(
                        java.util.Map.of("status", "ok", "result", java.util.Map.of("points", result)));
            } else if (exchange.getRequestMethod().equals("PUT")) {
                collections.add(path);
                response = "{\"status\":\"ok\",\"result\":true}";
            } else if (collections.contains(path)) {
                response =
                        "{\"status\":\"ok\",\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}";
            } else {
                status = 404;
                response = "{}";
            }
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
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
                assertThat(initial.at("/task/kind").asText()).isEqualTo("index_file");
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
                assertThat(read(client, "/tasks/" + queued).path("id").asText()).isEqualTo(queued);
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
        return uploadText(client, dataset, "固定资料").at("/items/0/file/id").asText();
    }

    private JsonNode uploadText(Client client, String dataset, String text) throws Exception {
        String boundary = "knowledge-boundary";
        String body = "--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"source.txt\"\r\nContent-Type: text/plain\r\n\r\n"
                + text + "\r\n--"
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
        observed(response, null);
        return JSON.readTree(response.body());
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
        RuntimeTestFixtures.allowSavedModel(
                http, client.base(), path, method, body, client.cookie(), client.token(), response.statusCode());
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
                    new String[] {"GET", "/tasks/" + task, null},
                    new String[] {"GET", "/tasks/" + task + "/events", null},
                    new String[] {"GET", "/knowledge-bases/" + id + "/tasks", null},
                    new String[] {
                        "POST",
                        "/knowledge-bases/" + id + "/search-test",
                        "{\"query\":\"安全查询\",\"knowledgeBaseIds\":[\"" + id + "\"]}"
                    },
                    new String[] {"GET", "/search-requests/" + UUID.randomUUID(), null},
                    new String[] {
                        "GET", "/search-requests/" + UUID.randomUUID() + "/evidence/" + UUID.randomUUID(), null
                    });
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
                    "/knowledge-bases",
                    "/knowledge-bases/" + id + "/sources",
                    "/knowledge-bases/" + id + "/files",
                    "/knowledge-bases/" + id + "/tasks"))
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
                    if (!operation[1].startsWith("/tasks/") && !operation[1].startsWith("/search-requests/"))
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
            else if (item.get("behavior").equals("textIndexBecomesEffective"))
                item.put(
                        "behaviorChecks",
                        java.util.List.of("currentIndexVersions", "managementPublication", "indexRetriesAreBounded"));
            else if (item.get("behavior").equals("managementQueueAndRestartAreSafe"))
                item.put("behaviorChecks", java.util.List.of("queryDoesNotRepeatCalls"));
            else if (item.get("behavior").equals("persistedEmbeddingResumesWithoutModelCall"))
                item.put("behaviorChecks", java.util.List.of("persistedEmbeddingNoRepeat"));
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."))
                .toAbsolutePath()
                .normalize();
        for (String profile : java.util.List.of("knowledge-bases", "indexing")) {
            Process process = new ProcessBuilder(
                            "python",
                            "server/src/test/resources/contract/settings-evidence.py",
                            root.toString(),
                            profile)
                    .directory(root.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            try (var output = process.getOutputStream()) {
                output.write(JSON.writeValueAsBytes(OBSERVATIONS));
            }
            String result =
                    new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(result).isZero();
        }
    }

    private record Client(String base, String cookie, String token) {}
}
