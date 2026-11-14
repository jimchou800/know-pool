package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.knowledgebases.Indexes;
import cn.knowpool.knowledgebases.KnowledgeBases;
import cn.knowpool.questions.Questions;
import cn.knowpool.search.SearchRequests;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class IndexCoverageTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("千候选查询仅传标识与分数，正文仍从有效索引快照回查")
    void thousandCandidatesStayWithinVectorResponseBudget() throws Exception {
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 1000; i++) ids.add(UUID.randomUUID().toString());
        var unknown = new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer vector =
                indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {}, () -> {}, "4", hits -> {
                    ObjectNode template = ((ObjectNode) hits.get(0)).deepCopy();
                    hits.removeAll();
                    for (String id : ids) {
                        ObjectNode hit = template.deepCopy().put("id", id);
                        ((ObjectNode) hit.path("payload")).put("untrusted", "不可信向量载荷".repeat(50));
                        hits.add(hit);
                    }
                    if (unknown.get())
                        ((ObjectNode) hits.get(0)).put("id", UUID.randomUUID().toString());
                });
        try (var app = start(Files.createTempDirectory(Path.of("target"), "thousand-candidates-")
                .toAbsolutePath())) {
            Client client = client(app);
            ObjectNode config = (ObjectNode)
                    JSON.readTree(processing(client, vector.getAddress().getPort(), 0, "{}", "[]"));
            ((ObjectNode) config.path("embedding")).put("candidatesPerQuery", 1000);
            String kb = createIndexedBase(client, config.toString(), "千候选传输核验库", "只从本地索引读取的固定原文。");
            JsonNode file = read(client, "/knowledge-bases/" + kb + "/files").at("/items/0");
            String index = file.path("effectiveIndexVersionId").asText();
            // 固定已生效索引中的千条候选，回归真实查询传输与数据库回查，不重复执行千次嵌入。
            app.getBean(cn.knowpool.infrastructure.ApplicationData.class).write(jdbc -> {
                String original = jdbc.sql("SELECT id FROM index_chunks WHERE index_id=? LIMIT 1")
                        .param(index)
                        .query(String.class)
                        .single();
                for (int i = 0; i < ids.size(); i++)
                    jdbc.sql(
                                    "INSERT INTO index_chunks(id,index_id,ordinal,text,location,source_range) SELECT ?,index_id,?,text,location,source_range FROM index_chunks WHERE id=?")
                            .params(ids.get(i), i + 1, original)
                            .update();
                return null;
            });
            Indexes indexes = app.getBean(Indexes.class);
            JsonNode result = indexes.retrieve(kb, "固定千候选查询");
            assertThat(result.size()).isEqualTo(1000);
            assertThat(result).allSatisfy(hit -> {
                assertThat(hit.path("text").asText()).contains("只从本地索引读取的固定原文。");
                assertThat(hit.path("fileId").asText())
                        .isEqualTo(file.path("fileId").asText());
                assertThat(hit.path("indexVersionId").asText()).isEqualTo(index);
                assertThat(hit.path("text").asText()).doesNotContain("不可信向量载荷");
            });
            unknown.set(true);
            assertThat(indexes.retrieve(kb, "未知标识不得泄露原文").size()).isEqualTo(999);
        } finally {
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("整库旧失败项排在新重试之前仍允许子项执行，全部恢复后才原子切换")
    void rebuildWaitsForRetriedChildrenBeforeFailing() throws Exception {
        var embedded = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector = indexedVector(embedded);
        try (var app = start(Files.createTempDirectory(Path.of("target"), "rebuild-retry-order-")
                .toAbsolutePath())) {
            Client client = client(app);
            String config = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String kb = createIndexedBase(client, config, "重试排序核验库", "第一份固定原文。");
            JsonNode first = read(client, "/knowledge-bases/" + kb + "/files").at("/items/0");
            String dataset = read(client, "/files/" + first.path("fileId").asText())
                    .path("datasetId")
                    .asText();
            uploadText(client, dataset, "第二份固定原文。", "second.txt");
            for (JsonNode file :
                    read(client, "/knowledge-bases/" + kb + "/files").path("items"))
                assertThat(await(client, file.at("/task/id").asText())
                                .path("state")
                                .asText())
                        .isEqualTo("succeeded");
            KnowledgeBases bases = app.getBean(KnowledgeBases.class);
            Indexes indexes = app.getBean(Indexes.class);
            var data = app.getBean(cn.knowpool.infrastructure.ApplicationData.class);
            JsonNode before = bases.get(kb);
            String selected, retried, parent, target;
            try (var exclusive = data.gate().exclusive()) {
                ObjectNode updated = (ObjectNode) JSON.readTree(config);
                ((ObjectNode) updated.path("chunking")).put("overlapCodePoints", 1);
                var accepted =
                        bases.updateConfig(kb, "coverage", UUID.randomUUID().toString(), updated);
                target = accepted.task().targetConfigVersionId();
                // 固定真实持久化失败检查点，避免依赖外部故障速度或随机任务标识排序。
                data.write(jdbc -> {
                    for (String payload : jdbc.sql(
                                    "SELECT payload FROM tasks WHERE json_extract(payload,'$.targetConfigVersionId')=?")
                            .param(target)
                            .query(String.class)
                            .list()) {
                        ObjectNode task = (ObjectNode) JSON.readTree(payload);
                        task.put("state", "failed")
                                .put("phase", "completed")
                                .put("finishedAt", java.time.Instant.now().toString());
                        task.set(
                                "failure",
                                JSON.createObjectNode()
                                        .put("code", "MODEL_UNAVAILABLE")
                                        .put("message", "固定失败检查点。")
                                        .put("retryable", false));
                        jdbc.sql("UPDATE tasks SET state='failed',payload=? WHERE id=?")
                                .params(task.toString(), task.path("id").asText())
                                .update();
                    }
                    return null;
                });
                List<String> ordered = data.read(jdbc -> jdbc.sql(
                                "SELECT f.file_id FROM knowledge_base_files f JOIN tasks t ON t.id=f.task_id WHERE f.knowledge_base_id=? AND f.config_version_id=?")
                        .params(kb, target)
                        .query(String.class)
                        .list());
                assertThat(ordered).hasSize(2);
                selected = ordered.getLast();
                retried = bases.retryFiles(
                                kb, selected, "coverage", UUID.randomUUID().toString())
                        .at("/items/0/taskId")
                        .asText();
                parent = data.read(jdbc -> jdbc.sql(
                                "SELECT task_id FROM processing_rebuilds WHERE knowledge_base_id=? AND config_version_id=?")
                        .params(kb, target)
                        .query(String.class)
                        .single());
                List<String> states = data.read(jdbc -> jdbc.sql(
                                "SELECT t.state FROM knowledge_base_files f JOIN tasks t ON t.id=f.task_id WHERE f.knowledge_base_id=? AND f.config_version_id=?")
                        .params(kb, target)
                        .query(String.class)
                        .list());
                assertThat(states).containsExactly("failed", "queued");
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(indexes, "rebuilds");
                String state = data.read(jdbc -> jdbc.sql("SELECT state FROM tasks WHERE id=?")
                        .param(parent)
                        .query(String.class)
                        .single());
                assertThat(state).isEqualTo("running");
            }
            assertThat(await(client, retried).path("state").asText()).isEqualTo("succeeded");
            assertThat(await(client, parent).path("state").asText()).isEqualTo("failed");
            assertThat(bases.get(kb).path("effectiveConfigVersionId"))
                    .isEqualTo(before.path("effectiveConfigVersionId"));
            assertThat(bases.get(kb).path("effectiveIndexVersionId")).isEqualTo(before.path("effectiveIndexVersionId"));
            JsonNode batch =
                    bases.retryFiles(kb, null, "coverage", UUID.randomUUID().toString());
            assertThat(batch.path("items")
                            .valueStream()
                            .filter(item -> item.path("outcome").asText().equals("enqueued"))
                            .count())
                    .isEqualTo(1);
            assertThat(batch.path("items")
                            .valueStream()
                            .filter(item -> item.path("fileId").asText().equals(selected))
                            .noneMatch(item -> item.path("outcome").asText().equals("enqueued")))
                    .isTrue();
            String finalParent = data.read(jdbc -> jdbc.sql(
                            "SELECT task_id FROM processing_rebuilds WHERE knowledge_base_id=? AND config_version_id=?")
                    .params(kb, target)
                    .query(String.class)
                    .single());
            assertThat(await(client, finalParent).path("state").asText()).isEqualTo("succeeded");
            assertThat(bases.get(kb).path("effectiveConfigVersionId").asText()).isEqualTo(target);
            assertThat(bases.get(kb).path("effectiveIndexVersionId").asText()).isEqualTo(finalParent);
            assertThat(bases.files(kb, 100, null).items()).allSatisfy(file -> {
                assertThat(file.path("failure").isNull()).isTrue();
                assertThat(file.path("effectiveConfigVersionId").asText()).isEqualTo(target);
            });
        } finally {
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("查询拒绝不可信分数及超额候选，未知标识和阈值过滤不泄露原文")
    void retrievalBoundariesNeverLeakUntrustedEvidence() throws Exception {
        var mode = new AtomicReference<>("valid");
        var callback = new AtomicReference<Runnable>(() -> {});
        var embedded = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector =
                indexedVector(embedded, () -> {}, () -> callback.get().run(), "4", hits -> {
                    ObjectNode first = (ObjectNode) hits.get(0);
                    switch (mode.get()) {
                        case "missing_score" -> first.remove("score");
                        case "string_score" -> first.put("score", "0.9");
                        case "huge_score" -> first.put("score", 1e100);
                        case "below" -> first.put("score", 0.49);
                        case "equal" -> first.put("score", 0.5);
                        case "unknown" -> first.put("id", UUID.randomUUID().toString());
                        case "unknown_mixed" ->
                            hits.add(
                                    first.deepCopy().put("id", UUID.randomUUID().toString()));
                        case "too_many" -> {
                            while (hits.size() <= 10) hits.add(first.deepCopy());
                        }
                        case "empty" -> hits.removeAll();
                        default -> {}
                    }
                });
        try (var app = start(Files.createTempDirectory(Path.of("target"), "retrieval-boundaries-")
                .toAbsolutePath())) {
            Client client = client(app);
            String kb = createIndexedBase(
                    client,
                    processing(client, vector.getAddress().getPort(), 0, "{}", "[]"),
                    "查询边界库",
                    "不可向未知候选泄露的固定原文。");
            Indexes indexes = app.getBean(Indexes.class);
            KnowledgeBases bases = app.getBean(KnowledgeBases.class);
            for (String scenario : List.of("missing_score", "string_score", "too_many")) {
                mode.set(scenario);
                failure(() -> indexes.retrieve(kb, "固定查询"), "INTEGRITY_FAILED");
            }
            for (String scenario : List.of("below", "unknown", "empty")) {
                mode.set(scenario);
                assertThat(indexes.retrieve(kb, "固定查询").isEmpty()).as(scenario).isTrue();
            }
            for (String scenario : List.of("valid", "equal", "unknown_mixed", "huge_score")) {
                mode.set(scenario);
                JsonNode evidence = indexes.retrieve(kb, "固定查询");
                assertThat(evidence.size()).as(scenario).isEqualTo(1);
                assertThat(evidence.get(0).path("text").asText()).contains("固定原文");
                assertThat(indexes.remainsEligible(evidence.get(0))).isTrue();
            }
            mode.set("valid");
            for (int cancellationStep : List.of(1, 2, 3)) {
                AtomicInteger checks = new AtomicInteger();
                try (var cache = new Indexes.QueryVectors()) {
                    failure(
                            () -> indexes.retrieve(
                                    kb, "取消查询", cache, 10, () -> checks.incrementAndGet() >= cancellationStep),
                            "TASK_CANCELLED");
                }
            }
            try (var cache = new Indexes.QueryVectors()) {
                indexes.captureAll(List.of(kb, kb), cache);
                int before = embedded.size();
                indexes.retrieve(kb, "复用查询", cache, 10, () -> false);
                indexes.retrieve(kb, "复用查询", cache, 10, () -> false);
                assertThat(embedded.size() - before).isEqualTo(1);
                assertThat(indexes.cleanWhenIdle(List.of(), () -> {
                            throw new AssertionError("读者尚未释放，不应清理。");
                        }))
                        .isFalse();
                for (int i = 1; i < 400; i++) indexes.retrieve(kb, "预算查询" + i, cache, 10, () -> false);
                failure(() -> indexes.retrieve(kb, "超过缓存预算", cache, 10, () -> false), "MODEL_CONFIG_INVALID");
            }
            JsonNode saved = indexes.retrieve(kb, "当前范围").get(0);
            String file = saved.path("fileId").asText();
            callback.set(() -> bases.exclude(kb, file));
            failure(() -> indexes.retrieve(kb, "范围变化"), "SCOPE_UNAVAILABLE");
            callback.set(() -> {});
            assertThat(indexes.remainsEligible(saved)).isFalse();
            assertThat(indexes.remainsEligible(JSON.createObjectNode()
                            .put("fileId", UUID.randomUUID().toString())))
                    .isFalse();
            failure(() -> indexes.decorateEvidence(JSON.createArrayNode()), "INTEGRITY_FAILED");
        } finally {
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("索引写入须逐项核验读回标识、载荷和向量，失败不能成为生效索引")
    void failedVectorProofsNeverBecomeEffective() throws Exception {
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>());
        try (var app = start(Files.createTempDirectory(Path.of("target"), "vector-proof-boundaries-")
                .toAbsolutePath())) {
            Client client = client(app);
            String config = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            for (String scenario : List.of(
                    "missing",
                    "duplicate",
                    "id",
                    "payload",
                    "vector_type",
                    "vector_size",
                    "vector_string",
                    "vector_overflow",
                    "vector_negative_overflow",
                    "vector_mismatch")) {
                proofMode.set(scenario);
                String dataset = readCreatedDataset(client, "读回核验" + scenario);
                uploadText(client, dataset, "不能通过错误读回核验的原文。");
                String body = JSON.createObjectNode()
                        .put("name", "核验失败库" + scenario)
                        .set("processing", JSON.readTree(config))
                        .toString();
                ObjectNode input = (ObjectNode) JSON.readTree(body);
                input.set("datasetIds", JSON.createArrayNode().add(dataset));
                JsonNode accepted = JSON.readTree(send(
                                client,
                                "/knowledge-bases",
                                "POST",
                                input.toString(),
                                UUID.randomUUID().toString())
                        .body());
                JsonNode created = await(client, accepted.at("/task/id").asText());
                assertThat(created.path("state").asText()).as(scenario).isEqualTo("succeeded");
                String kb = created.path("resultResourceId").asText();
                JsonNode file = read(client, "/knowledge-bases/" + kb + "/files")
                        .path("items")
                        .get(0);
                JsonNode terminal = await(client, file.at("/task/id").asText());
                assertThat(terminal.path("state").asText()).as(scenario).isEqualTo("failed");
                assertThat(terminal.at("/failure/code").asText()).as(scenario).isEqualTo("INTEGRITY_FAILED");
                failure(() -> app.getBean(Indexes.class).retrieve(kb, "核验未通过"), "NO_EFFECTIVE_INDEX");
            }
        } finally {
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("恢复后只标记显式重建，取消记录不复活且检索范围完整披露缺失库")
    void restoredFilesRequireExplicitRebuildAndPreserveCancellation() throws Exception {
        var embedded = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector = indexedVector(embedded);
        try (var app = start(Files.createTempDirectory(Path.of("target"), "restore-index-boundaries-")
                .toAbsolutePath())) {
            Client client = client(app);
            String config = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String kb = createIndexedBase(client, config, "恢复核验库", "恢复后不自动调用模型。");
            String unpublished = createIndexedBase(client, config, "未发布核验库", "未发布原文。");
            KnowledgeBases bases = app.getBean(KnowledgeBases.class);
            Indexes indexes = app.getBean(Indexes.class);
            SearchRequests searches = app.getBean(SearchRequests.class);
            bases.publish(kb);
            try (var grounding = searches.beginGrounding(
                    List.of(kb, unpublished, UUID.randomUUID().toString()))) {
                JsonNode result = grounding.retrieve(List.of("同一事实", "同一事实"), () -> false, progress -> {});
                assertThat(result.path("evidence").size()).isEqualTo(1);
                assertThat(result.at("/scope/omitted").size()).isEqualTo(2);
                assertThat(result.at("/scope/incomplete").asBoolean()).isTrue();
                assertThat(grounding.remainsEligible(result.path("evidence").get(0)))
                        .isTrue();
                assertThat(grounding
                                .retrieve(List.of("取消事实"), () -> true, progress -> {})
                                .has("evidence"))
                        .isFalse();
                failure(
                        () -> grounding.retrieve(
                                java.util.Collections.nCopies(1001, "预算事实"), () -> false, progress -> {}),
                        "RESOURCE_LIMIT");
            }
            indexes.close();
            int before = embedded.size();
            bases.restoreIndexFailures();
            assertThat(embedded.size()).isEqualTo(before);
            assertThat(bases.get(kb).path("availability").asText()).isEqualTo("unavailable");
            JsonNode file = bases.files(kb, 100, null).items().get(0);
            String fileId = file.path("fileId").asText();
            assertThat(file.at("/task/state").asText()).isEqualTo("failed");
            assertThat(file.at("/task/failure/code").asText()).isEqualTo("INDEX_REBUILD_REQUIRED");
            try (var grounding = searches.beginGrounding(List.of(kb))) {
                JsonNode result = grounding.retrieve(List.of("恢复事实"), () -> false, progress -> {});
                assertThat(result.path("evidence").isEmpty()).isTrue();
                assertThat(result.at("/scope/incomplete").asBoolean()).isTrue();
            }
            JsonNode retried = bases.retryFiles(
                            kb, fileId, "coverage", UUID.randomUUID().toString())
                    .path("items")
                    .get(0);
            assertThat(retried.path("outcome").asText()).isEqualTo("enqueued");
            assertThat(bases.retryFiles(
                                    kb, fileId, "coverage", UUID.randomUUID().toString())
                            .at("/items/0/outcome")
                            .asText())
                    .isEqualTo("already_active");
            String task = retried.path("taskId").asText();
            assertThat(indexes.cancel(task).path("state").asText()).isEqualTo("cancelled");
            bases.restoreIndexFailures();
            assertThat(bases.files(kb, 100, null).items().get(0).at("/task/id").asText())
                    .isEqualTo(task);
            assertThat(bases.retryFiles(
                                    kb, fileId, "coverage", UUID.randomUUID().toString())
                            .at("/items/0/outcome")
                            .asText())
                    .isEqualTo("ineligible");
            assertThat(embedded.size()).isEqualTo(before);
        } finally {
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("会话分页拒绝畸形游标且不更改已保存的问题")
    void malformedConversationCursorsNeverChangeSavedQuestions() throws Exception {
        try (var app = start(Files.createTempDirectory(Path.of("target"), "question-cursor-boundaries-")
                .toAbsolutePath())) {
            Questions questions = app.getBean(Questions.class);
            ObjectNode input = JSON.createObjectNode().put("question", "未配置模型时保留问题");
            input.set("knowledgeBaseIds", JSON.createArrayNode());
            JsonNode accepted = questions.first("coverage", UUID.randomUUID().toString(), input);
            String conversation = accepted.at("/conversation/id").asText();
            String id = accepted.at("/questionTask/task/id").asText();
            Client client = client(app);
            assertThat(await(client, id).path("state").asText()).isEqualTo("failed");
            for (String value : List.of(
                    "null",
                    "{}",
                    "[]",
                    "[1,2]",
                    "[\"2026-11-14T00:00:00Z\",1]",
                    "[\"2026-11-14T00:00:00\",\"bad\"]",
                    "[\"invalidZ\",\"bad\"]",
                    "[\"2026-11-14T00:00:00Z\",\"bad\"]")) {
                String cursor = java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                failure(() -> questions.conversations(10, cursor), "INVALID_REQUEST");
                failure(() -> questions.turns(conversation, 10, cursor), "INVALID_REQUEST");
            }
            assertThat(questions.conversations(10, null).items()).hasSize(1);
            assertThat(questions.turns(conversation, 10, null).items()).hasSize(1);
            assertThat(questions.get(id).path("question").asText()).isEqualTo("未配置模型时保留问题");
        }
    }

    private String readCreatedDataset(Client client, String name) throws Exception {
        return JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"" + name + "\"}", null)
                        .body())
                .path("id")
                .asText();
    }

    private String createIndexedBase(Client client, String processing, String name, String text) throws Exception {
        String dataset = readCreatedDataset(client, name);
        uploadText(client, dataset, text);
        JsonNode accepted = JSON.readTree(send(
                        client,
                        "/knowledge-bases",
                        "POST",
                        "{\"name\":\"" + name + "\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                + "\"]}",
                        UUID.randomUUID().toString())
                .body());
        String kb = await(client, accepted.at("/task/id").asText())
                .path("resultResourceId")
                .asText();
        for (JsonNode file : read(client, "/knowledge-bases/" + kb + "/files").path("items"))
            assertThat(await(client, file.at("/task/id").asText()).path("state").asText())
                    .isEqualTo("succeeded");
        return kb;
    }

    private JsonNode search(Client client, String kb) throws Exception {
        return JSON.readTree(send(
                        client,
                        "/knowledge-bases/" + kb + "/search-test",
                        "POST",
                        "{\"query\":\"固定查询\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                        UUID.randomUUID().toString())
                .body());
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
        return uploadText(client, dataset, text, "source.txt");
    }

    private JsonNode uploadText(Client client, String dataset, String text, String filename) throws Exception {
        String boundary = "knowledge-boundary";
        String body = "--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + (filename.endsWith(".md") ? "text/markdown" : "text/plain") + "\r\n\r\n"
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

    private Client client(ConfigurableApplicationContext app) throws Exception {
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";
        HttpResponse<String> response = send(new Client(base, null, null), "/session", "GET", null, null);
        return new Client(
                base,
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(response.body()).get("csrfToken").asText());
    }

    private final AtomicReference<String> proofMode = new AtomicReference<>("valid");

    private HttpServer indexedVector(java.util.List<String> inputs) throws Exception {
        return indexedVector(inputs, () -> {});
    }

    private HttpServer indexedVector(java.util.List<String> inputs, Runnable beforeEmbedding) throws Exception {
        return indexedVector(inputs, beforeEmbedding, () -> {});
    }

    private HttpServer indexedVector(java.util.List<String> inputs, Runnable beforeEmbedding, Runnable beforeQuery)
            throws Exception {
        return indexedVector(inputs, beforeEmbedding, beforeQuery, "4");
    }

    private HttpServer indexedVector(
            java.util.List<String> inputs, Runnable beforeEmbedding, Runnable beforeQuery, String usage)
            throws Exception {
        return indexedVector(inputs, beforeEmbedding, beforeQuery, usage, hits -> {});
    }

    private HttpServer indexedVector(
            java.util.List<String> inputs,
            Runnable beforeEmbedding,
            Runnable beforeQuery,
            String usage,
            java.util.function.Consumer<tools.jackson.databind.node.ArrayNode> adjustHits)
            throws Exception {
        return indexedVector(inputs, beforeEmbedding, beforeQuery, usage, adjustHits, null);
    }

    private HttpServer indexedVector(
            java.util.List<String> inputs,
            Runnable beforeEmbedding,
            Runnable beforeQuery,
            String usage,
            java.util.function.Consumer<tools.jackson.databind.node.ArrayNode> adjustHits,
            java.util.function.Function<JsonNode, JsonNode> chat)
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
            if (path.equals("/v1/chat/completions") && chat != null) {
                response = chat.apply(body).toString();
            } else if (path.equals("/v1/embeddings")) {
                inputs.add(body.at("/input/0").asText());
                beforeEmbedding.run();
                String usageBody =
                        usage.equals("missing") ? "{}" : "{\"prompt_tokens\":" + usage + ",\"total_tokens\":4}";
                response =
                        "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,0,0]}],\"model\":\"text-embedding-v4\",\"usage\":"
                                + usageBody + "}";
            } else if (path.endsWith("/points") && exchange.getRequestMethod().equals("PUT")) {
                for (JsonNode point : body.path("points"))
                    points.put(point.path("id").asText(), point);
                response = "{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}";
            } else if (path.endsWith("/points") && exchange.getRequestMethod().equals("POST")) {
                var result = JSON.createArrayNode();
                for (JsonNode id : body.path("ids"))
                    if (points.containsKey(id.asText())) {
                        var point = (tools.jackson.databind.node.ObjectNode)
                                points.get(id.asText()).deepCopy();
                        var normalized = JSON.createArrayNode();
                        for (JsonNode component : point.path("vector")) {
                            if (component.doubleValue() == Math.rint(component.doubleValue()))
                                normalized.add(component.intValue());
                            else normalized.add(component.doubleValue());
                        }
                        point.set("vector", normalized);
                        result.add(point);
                    }
                if (!result.isEmpty()) {
                    ObjectNode first = (ObjectNode) result.get(0);
                    switch (proofMode.get()) {
                        case "missing" -> result.removeAll();
                        case "duplicate" -> result.add(first.deepCopy());
                        case "id" -> first.put("id", UUID.randomUUID().toString());
                        case "payload" -> first.set("payload", JSON.createObjectNode());
                        case "vector_type" -> first.put("vector", "错误");
                        case "vector_size" ->
                            first.set("vector", JSON.createArrayNode().add(1));
                        case "vector_string" ->
                            first.set(
                                    "vector",
                                    JSON.createArrayNode().add("1").add(0).add(0));
                        case "vector_overflow" ->
                            first.set(
                                    "vector",
                                    JSON.createArrayNode().add(1e100).add(0).add(0));
                        case "vector_negative_overflow" ->
                            first.set(
                                    "vector",
                                    JSON.createArrayNode().add(-1e100).add(0).add(0));
                        case "vector_mismatch" ->
                            first.set(
                                    "vector",
                                    JSON.createArrayNode().add(0).add(1).add(0));
                        default -> {}
                    }
                }
                response = JSON.writeValueAsString(java.util.Map.of("status", "ok", "result", result));
            } else if (path.endsWith("/points/query")) {
                beforeQuery.run();
                var result = JSON.createArrayNode();
                for (JsonNode point : points.values())
                    for (JsonNode index : body.at("/filter/must/0/match/any"))
                        if (point.at("/payload/index_id").asText().equals(index.asText()))
                            result.add(((tools.jackson.databind.node.ObjectNode) point.deepCopy()).put("score", 0.9));
                adjustHits.accept(result);
                for (JsonNode hit : result) {
                    ObjectNode projected = (ObjectNode) hit;
                    if (!body.path("with_payload").asBoolean()) projected.remove("payload");
                    if (!body.path("with_vector").asBoolean()) projected.remove("vector");
                }
                response = JSON.writeValueAsString(
                        java.util.Map.of("status", "ok", "result", java.util.Map.of("points", result)));
            } else if (path.endsWith("/points/delete")) {
                String index = body.at("/filter/must/0/match/value").asText();
                points.entrySet().removeIf(entry -> entry.getValue()
                        .at("/payload/index_id")
                        .asText()
                        .equals(index));
                response = "{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}";
            } else if (exchange.getRequestMethod().equals("PUT")) {
                collections.add(path);
                response = "{\"status\":\"ok\",\"result\":true}";
            } else if (exchange.getRequestMethod().equals("DELETE")) {
                collections.remove(path);
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

    private HttpResponse<String> send(Client client, String path, String method, String body, String key)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(client.base() + path));
        if (client.cookie() != null) request.header("Cookie", client.cookie());
        if (client.token() != null) request.header("X-CSRF-Token", client.token());
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        var response = http.send(
                request.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        RuntimeTestFixtures.allowSavedModel(
                http, client.base(), path, method, body, client.cookie(), client.token(), response.statusCode());
        return response;
    }

    private void failure(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiFailure.class, value -> assertThat(value.code())
                .isEqualTo(code));
    }

    private record Client(String base, String cookie, String token) {}
}
