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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IndexChunkingHttpTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("真实上传切片保留缩写小数引用和换行，不切断完整句")
    void sentencesRemainIntact() throws Exception {
        String first = "首".repeat(1800) + "。";
        String second = "Dr. Smith measured 3.14, e.g. a reference “引用\n续行” " + "b".repeat(240) + ".";
        assertThat(index(first + second, 0, "{}", "[]")).containsExactly(first, second);
    }

    @Test
    @DisplayName("重叠只复用完整句，并在下一句需要空间时取消")
    void overlapKeepsCompleteSentences() throws Exception {
        String first = "前".repeat(1500) + "。";
        String second = "中".repeat(100) + "。";
        String third = "后".repeat(500) + "。";
        assertThat(index(first + second + third, 200, "{}", "[]")).containsExactly(first + second, second + third);
        String large = "尾".repeat(1950) + "。";
        assertThat(index(first + second + large, 200, "{}", "[]")).containsExactly(first + second, large);
    }

    @Test
    @DisplayName("超长句按分隔符降级，补充平面字符按码点硬切且正文无丢失")
    void longSentencesKeepEveryCodePoint() throws Exception {
        String first = "甲".repeat(1900) + "，";
        String second = "乙".repeat(300);
        assertThat(index(first + second, 0, "{}", "[]")).containsExactly(first, second);
        String text = "😀甲".repeat(1300);
        java.util.List<String> chunks = index(text, 0, "{}", "[]");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst()).isEqualTo("😀甲".repeat(1000));
        assertThat(chunks.getLast()).isEqualTo("😀甲".repeat(300));
        assertThat(String.join("", chunks)).isEqualTo(text);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.codePointCount(0, chunk.length()))
                .isLessThanOrEqualTo(2000));
    }

    @Test
    @DisplayName("句末引用保留在原句，列表编号和首字母缩写不冒充句末")
    void quotationAndListBoundariesRemainTraceable() throws Exception {
        String first = "前".repeat(1948) + "。”";
        String second = "\n1. Dr. A. Smith in the U.S. measured 3.14, e.g. “引用\n续行” " + "后".repeat(200) + "！";
        assertThat(index(first + second, 0, "{}", "[]")).containsExactly(first, second);
    }

    @Test
    @DisplayName("嵌入输入仅加入选定自定义文本字段，未选中字段不外发")
    void onlySelectedMetadataEntersEmbedding() throws Exception {
        assertThat(index("固定正文", 0, "{\"user_label\":\"选中内容\",\"user_private\":\"未选内容\"}", "[\"user_label\"]"))
                .containsExactly("固定正文\nuser_label: 选中内容");
    }

    @Test
    @DisplayName("正文与选中元信息共同超过模型预算时拒绝外发和生效")
    void metadataBudgetPreventsDispatch() throws Exception {
        assertThat(index(
                        "正".repeat(1000),
                        0,
                        "{\"user_label\":\"" + "元".repeat(2000) + "\"}",
                        "[\"user_label\"]",
                        "failed"))
                .isEmpty();
    }

    @Test
    @DisplayName("首次嵌入外发期间覆盖上传，旧任务取消且迟到响应不能发布")
    void replacedVersionCannotPublishLateEmbedding() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector = indexedVector(inputs, () -> {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            .isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("受控嵌入等待被中断。", interrupted);
                }
            }
        });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "late-embedding-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"首次版本保护\"}", null)
                            .body())
                    .path("id")
                    .asText();
            uploadText(client, dataset, "旧正文迟到返回");
            JsonNode accepted = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"首次索引保护\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                    + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            String kb = await(client, accepted.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode initial = read(client, "/knowledge-bases/" + kb + "/files").at("/items/0");
            String oldTask = initial.at("/task/id").asText();
            assertThat(initial.path("effectiveIndexVersionId").isNull()).isTrue();
            JsonNode replacement = uploadText(client, dataset, "新正文唯一生效");
            JsonNode cancelled = await(client, oldTask);
            assertThat(cancelled.path("state").asText()).isEqualTo("cancelled");
            assertThat(cancelled.path("resultResourceId").isNull()).isTrue();
            release.countDown();
            JsonNode completed =
                    await(client, replacement.at("/items/0/indexTaskIds/0").asText());
            assertThat(completed.path("state").asText())
                    .as(completed.toString())
                    .isEqualTo("succeeded");
            JsonNode current = read(client, "/knowledge-bases/" + kb + "/files").at("/items/0");
            assertThat(current.path("effectiveIndexVersionId").asText())
                    .isEqualTo(completed.path("resultResourceId").asText());
            assertThat(current.path("effectiveContentVersionId").asText())
                    .isEqualTo(completed.path("targetContentVersionId").asText());
            assertThat(read(client, "/tasks/" + oldTask).path("state").asText()).isEqualTo("cancelled");
            JsonNode search = JSON.readTree(send(
                            client,
                            "/knowledge-bases/" + kb + "/search-test",
                            "POST",
                            "{\"query\":\"新正文\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            assertThat(await(client, search.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            JsonNode result =
                    read(client, "/search-requests/" + search.path("id").asText());
            assertThat(result.at("/result/evidence").size()).isEqualTo(1);
            assertThat(result.at("/result/evidence/0/text").asText()).isEqualTo("新正文唯一生效");
            assertThat(result.at("/result/evidence/0/contentVersionId").asText())
                    .isEqualTo(completed.path("targetContentVersionId").asText());
        } finally {
            release.countDown();
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("检索外发期间切换生效版本，当前请求仍返回捕获版本的完整证据")
    void searchRetainsCapturedVersionDuringSwitch() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {}, () -> {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            .isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("受控检索等待被中断。", interrupted);
                }
            }
        });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "search-snapshot-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"检索版本快照\"}", null)
                            .body())
                    .path("id")
                    .asText();
            uploadText(client, dataset, "原生效正文");
            JsonNode accepted = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"读取版本保护\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                    + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            String kb = await(client, accepted.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            JsonNode oldTask = await(
                    client,
                    read(client, "/knowledge-bases/" + kb + "/files")
                            .at("/items/0/task/id")
                            .asText());
            assertThat(oldTask.path("state").asText()).isEqualTo("succeeded");
            JsonNode search = JSON.readTree(send(
                            client,
                            "/knowledge-bases/" + kb + "/search-test",
                            "POST",
                            "{\"query\":\"版本快照\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode replacement = uploadText(client, dataset, "后续生效正文");
            JsonNode newTask =
                    await(client, replacement.at("/items/0/indexTaskIds/0").asText());
            assertThat(newTask.path("state").asText()).as(newTask.toString()).isEqualTo("succeeded");
            assertThat(newTask.path("targetContentVersionId").asText())
                    .isNotEqualTo(oldTask.path("targetContentVersionId").asText());
            release.countDown();
            assertThat(await(client, search.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            JsonNode result =
                    read(client, "/search-requests/" + search.path("id").asText());
            assertThat(result.at("/result/outcome").asText())
                    .as(result.toString())
                    .isEqualTo("matches");
            assertThat(result.at("/result/evidence").size()).isEqualTo(1);
            assertThat(result.at("/result/evidence/0/text").asText()).isEqualTo("原生效正文");
            assertThat(result.at("/result/evidence/0/contentVersionId").asText())
                    .isEqualTo(oldTask.path("targetContentVersionId").asText());
            assertThat(result.at("/result/evidence/0/indexVersionId").asText())
                    .isEqualTo(oldTask.path("resultResourceId").asText());
            assertThat(read(client, "/knowledge-bases/" + kb + "/files")
                            .at("/items/0/effectiveIndexVersionId")
                            .asText())
                    .isEqualTo(newTask.path("resultResourceId").asText());
        } finally {
            release.countDown();
            vector.stop(0);
        }
    }

    private java.util.List<String> index(String text, int overlap, String metadata, String fields) throws Exception {
        return index(text, overlap, metadata, fields, "succeeded");
    }

    private java.util.List<String> index(String text, int overlap, String metadata, String fields, String expectedState)
            throws Exception {
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector = indexedVector(inputs);
        Path directory =
                Files.createTempDirectory(Path.of("target"), "chunking-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), overlap, metadata, fields);
            String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"切片固定样例\"}", null)
                            .body())
                    .path("id")
                    .asText();
            uploadText(client, dataset, text);
            HttpResponse<String> response = send(
                    client,
                    "/knowledge-bases",
                    "POST",
                    "{\"name\":\"切片校验\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset + "\"]}",
                    UUID.randomUUID().toString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            JsonNode created =
                    await(client, JSON.readTree(response.body()).at("/task/id").asText());
            assertThat(created.path("state").asText()).as(created.toString()).isEqualTo("succeeded");
            String kb = created.path("resultResourceId").asText();
            JsonNode file = read(client, "/knowledge-bases/" + kb + "/files")
                    .path("items")
                    .get(0);
            JsonNode indexed = await(client, file.at("/task/id").asText());
            assertThat(indexed.path("state").asText()).as(indexed.toString()).isEqualTo(expectedState);
            if (expectedState.equals("failed")) {
                assertThat(indexed.at("/failure/code").asText()).isEqualTo("MODEL_CONFIG_INVALID");
                assertThat(read(client, "/knowledge-bases/" + kb)
                                .path("searchableFileCount")
                                .asInt())
                        .isZero();
            }
            return java.util.List.copyOf(inputs);
        } finally {
            vector.stop(0);
        }
    }

    private String processing(Client client, int port, int overlap, String metadata, String fields) throws Exception {
        configure(client, port);
        send(
                client,
                "/settings/models/embedding",
                "PUT",
                "{\"endpoint\":\"http://127.0.0.1:" + port
                        + "/v1\",\"model\":\"text-embedding-v4\",\"contextWindowTokens\":null,\"maxOutputTokens\":null,\"credential\":{\"action\":\"replace\",\"value\":\"fixed-test-key\"}}",
                null);
        JsonNode saved = read(client, "/settings/models/embedding");
        return "{\"chunking\":{\"strategy\":\"structure\",\"overlapCodePoints\":" + overlap
                + ",\"removePageHeadersFooters\":false,\"customMetadata\":" + metadata
                + ",\"embeddedMetadataFields\":" + fields
                + "},\"embedding\":{\"modelConfigVersionId\":\""
                + saved.path("versionId").asText()
                + "\",\"dimensions\":3,\"distance\":\"cosine\",\"similarityThreshold\":0.5,\"candidatesPerQuery\":10,\"modelSnapshot\":{\"endpoint\":\"http://127.0.0.1:"
                + port + "/v1\",\"model\":\"text-embedding-v4\"}}}";
    }

    private void configure(Client client, int port) throws Exception {
        String endpoint = "http://127.0.0.1:" + port;
        assertThat(send(
                                client,
                                "/settings/vector-store",
                                "PUT",
                                "{\"endpoint\":\"" + endpoint + "\",\"credential\":{\"action\":\"clear\"}}",
                                null)
                        .statusCode())
                .isEqualTo(200);
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
        return JSON.readTree(response.body());
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
        return response;
    }

    private Client client(ConfigurableApplicationContext app) throws Exception {
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";
        HttpResponse<String> response = send(new Client(base, null, null), "/session", "GET", null, null);
        return new Client(
                base,
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(response.body()).get("csrfToken").asText());
    }

    private HttpServer indexedVector(java.util.List<String> inputs) throws Exception {
        return indexedVector(inputs, () -> {});
    }

    private HttpServer indexedVector(java.util.List<String> inputs, Runnable beforeEmbedding) throws Exception {
        return indexedVector(inputs, beforeEmbedding, () -> {});
    }

    private HttpServer indexedVector(java.util.List<String> inputs, Runnable beforeEmbedding, Runnable beforeQuery)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(command -> Thread.ofVirtual().start(command));
        java.util.Set<String> collections = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Map<String, JsonNode> points = new java.util.concurrent.ConcurrentHashMap<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] request = exchange.getRequestBody().readAllBytes();
            JsonNode body = request.length == 0 ? JSON.createObjectNode() : JSON.readTree(request);
            String response;
            int status = 200;
            if (path.equals("/v1/embeddings")) {
                inputs.add(body.at("/input/0").asText());
                beforeEmbedding.run();
                response =
                        "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,0,0]}],\"model\":\"text-embedding-v4\",\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4}}";
            } else if (path.endsWith("/points") && exchange.getRequestMethod().equals("PUT")) {
                for (JsonNode point : body.path("points"))
                    points.put(point.path("id").asText(), point);
                response = "{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}";
            } else if (path.endsWith("/points") && exchange.getRequestMethod().equals("POST")) {
                var result = JSON.createArrayNode();
                for (JsonNode id : body.path("ids"))
                    if (points.containsKey(id.asText())) result.add(points.get(id.asText()));
                response = JSON.writeValueAsString(java.util.Map.of("status", "ok", "result", result));
            } else if (path.endsWith("/points/query")) {
                beforeQuery.run();
                var result = JSON.createArrayNode();
                for (JsonNode point : points.values())
                    for (JsonNode index : body.at("/filter/must/0/match/any"))
                        if (point.at("/payload/index_id").asText().equals(index.asText()))
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

    private ConfigurableApplicationContext start(Path directory) {
        return new SpringApplication(KnowledgePoolApplication.class)
                .run("--server.port=0", "--know-pool.data-directory=" + directory);
    }

    private record Client(String base, String cookie, String token) {}
}
