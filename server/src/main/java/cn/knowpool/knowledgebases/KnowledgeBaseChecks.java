package cn.knowpool.knowledgebases;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.infrastructure.TaskEvents;
import cn.knowpool.system.ConnectionTests;
import cn.knowpool.system.Embeddings;
import cn.knowpool.system.ModelSettingsService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class KnowledgeBaseChecks {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.knowpool.infrastructure.RuntimeSettings runtime;

    private final ApplicationData data;
    private final KnowledgeBases bases;
    private final ModelSettingsService settings;
    private final VectorCollections vectors;
    private final Embeddings embeddings;
    private final ConnectionTests tasks;
    private final JsonMapper json;
    private final cn.knowpool.infrastructure.AdjustableCapacity capacity =
            new cn.knowpool.infrastructure.AdjustableCapacity(
                    () -> runtime == null ? 17 : (int) runtime.number("queueCapacity") + 1);
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                Thread thread = new Thread(r, "knowledge-base-check");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean stopping;

    public KnowledgeBaseChecks(
            ApplicationData data,
            KnowledgeBases bases,
            ModelSettingsService settings,
            VectorCollections vectors,
            Embeddings embeddings,
            ConnectionTests tasks,
            JsonMapper json) {
        this.data = data;
        this.bases = bases;
        this.settings = settings;
        this.vectors = vectors;
        this.embeddings = embeddings;
        this.tasks = tasks;
        this.json = json;
    }

    public ConnectionTests.Accepted submit(String kb, String session, String key) {
        String valid = ModelSettingsService.identifier(key, "Idempotency-Key");
        java.util.concurrent.atomic.AtomicBoolean acquired = new java.util.concurrent.atomic.AtomicBoolean();
        Pending pending;
        boolean stored = false;
        try {
            pending = data.write(jdbc -> {
                JsonNode base = bases.get(kb);
                var existing = jdbc.sql(
                                "SELECT task_id FROM knowledge_base_check_keys WHERE session_id=? AND knowledge_base_id=? AND idempotency_key=?")
                        .params(session, kb, valid)
                        .query(String.class)
                        .optional();
                if (existing.isPresent()) return new Pending(tasks.get(existing.get()), null, null, null);
                var active = jdbc.sql(
                                "SELECT id FROM tasks WHERE operation=? AND state IN ('queued','running') ORDER BY id LIMIT 1")
                        .param("checkKnowledgeBase:" + kb)
                        .query(String.class)
                        .optional();
                if (active.isPresent()) {
                    jdbc.sql("INSERT INTO knowledge_base_check_keys VALUES(?,?,?,?)")
                            .params(session, kb, valid, active.get())
                            .update();
                    return new Pending(tasks.get(active.get()), null, null, null);
                }
                String version = checkedVersion(base);
                JsonNode config = bases.config(kb, version).path("input").path("embedding");
                var model = settings.embeddingSnapshot(
                        config.path("modelConfigVersionId").asText());
                var vector = settings.vectorConnection();
                if (model.endpoint() == null
                        || model.model() == null
                        || model.secret() == null
                        || vector.endpoint() == null)
                    throw new ApiFailure(422, "INVALID_STATE", "请先补全知识库生效嵌入配置与向量连接。", null);
                if (!capacity.tryAcquire()) throw new ApiFailure(429, "QUEUE_FULL", "知识库复查队列已满，请稍后重试。", null);
                acquired.set(true);
                String id = UUID.randomUUID().toString();
                ConnectionTests.Task task = new ConnectionTests.Task(
                        id,
                        "model_test",
                        "queued",
                        "queued",
                        Instant.now().toString(),
                        null,
                        null,
                        kb,
                        null,
                        null,
                        version,
                        null,
                        0,
                        0,
                        null,
                        null,
                        List.of(),
                        null);
                jdbc.sql(
                                "INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,'model_test','queued',?,?,?,?,?)")
                        .params(
                                id,
                                json.writeValueAsString(task),
                                session,
                                "checkKnowledgeBase:" + kb,
                                valid,
                                "{\"allowChargedCall\":true}")
                        .update();
                jdbc.sql("INSERT INTO knowledge_base_check_keys VALUES(?,?,?,?)")
                        .params(session, kb, valid, id)
                        .update();
                TaskEvents.record(jdbc, json, json.valueToTree(task));
                return new Pending(task, model, vector, config);
            });
            stored = true;
        } finally {
            if (!stored && acquired.get()) capacity.release();
        }
        if (pending.model() != null) {
            try {
                worker.execute(data.gate()
                        .activity(
                                () -> {
                                    try {
                                        run(pending);
                                    } finally {
                                        capacity.release();
                                    }
                                },
                                capacity::release));
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                capacity.release();
                ObjectNode rejected = (ObjectNode) json.valueToTree(pending.task());
                rejected.put("state", "interrupted")
                        .put("phase", "completed")
                        .put("finishedAt", Instant.now().toString());
                rejected.set(
                        "failure",
                        json.createObjectNode()
                                .put("code", "TASK_INTERRUPTED")
                                .put("message", "知识库复查执行器已停止，请查看原任务后手动重试。")
                                .put("retryable", false));
                rejected.set("warnings", json.createArrayNode().add("连接检查可能已产生费用，请勿自动重新提交。"));
                save(rejected, null);
                return new ConnectionTests.Accepted(tasks.get(pending.task().id()));
            }
        }
        return new ConnectionTests.Accepted(pending.task());
    }

    private static String checkedVersion(JsonNode base) {
        return base.path("effectiveConfigVersionId").isNull()
                ? base.path("targetConfigVersionId").asText()
                : base.path("effectiveConfigVersionId").asText();
    }

    private void run(Pending pending) {
        ObjectNode task = (ObjectNode) json.valueToTree(pending.task());
        try {
            task.put("state", "running")
                    .put("phase", "validating")
                    .put("startedAt", Instant.now().toString())
                    .put("attempt", 1);
            save(task, null);
            boolean available;
            String reason;
            try {
                embeddings.embed(
                        pending.model(),
                        "知识库连接检查。",
                        pending.config().path("dimensions").asInt());
                JsonNode response = vectors.request(
                        pending.vector().endpoint(),
                        pending.vector().secret(),
                        bases.collection(
                                task.path("knowledgeBaseId").asText(),
                                task.path("targetConfigVersionId").asText()),
                        "GET",
                        null);
                available = response != null
                        && response.at("/result/config/params/vectors/size").asInt()
                                == pending.config().path("dimensions").asInt()
                        && response.at("/result/config/params/vectors/distance")
                                .asText()
                                .equals(VectorCollections.distance(
                                        pending.config().path("distance").asText()));
                reason = available ? "知识库嵌入与向量依赖正常。" : "知识库向量集合缺失或维度、度量不匹配。";
            } catch (ApiFailure | java.io.IOException failure) {
                available = false;
                reason = "知识库依赖不可用，请检查生效嵌入配置及向量连接。";
            }
            if (stopping) return;
            boolean result = available;
            String explanation = reason;
            task.put("state", "succeeded")
                    .put("phase", "completed")
                    .put("finishedAt", Instant.now().toString())
                    .putNull("failure");
            ObjectNode output = json.createObjectNode()
                    .put("available", result)
                    .put("checkedAt", Instant.now().toString())
                    .put("message", explanation)
                    .put("mayHaveIncurredCost", true);
            data.write(jdbc -> {
                JsonNode base = bases.get(task.path("knowledgeBaseId").asText());
                if (checkedVersion(base)
                                .equals(task.path("targetConfigVersionId").asText())
                        && settings.vectorConnection().equals(pending.vector())
                        && settings.embeddingSnapshot(pending.config()
                                        .path("modelConfigVersionId")
                                        .asText())
                                .equals(pending.model())) {
                    ObjectNode updated = ((ObjectNode) base).deepCopy();
                    updated.put("availability", result ? "available" : "unavailable")
                            .put("lastCheckedAt", output.path("checkedAt").asText());
                    if (result) updated.putNull("availabilityReason");
                    else updated.put("availabilityReason", explanation);
                    jdbc.sql("UPDATE knowledge_bases SET payload=? WHERE id=?")
                            .params(
                                    json.writeValueAsString(updated),
                                    task.path("knowledgeBaseId").asText())
                            .update();
                }
                persist(jdbc, task, output);
                return null;
            });
        } catch (RuntimeException failure) {
            if (stopping) return;
            task.put("state", "failed")
                    .put("phase", "completed")
                    .put("finishedAt", Instant.now().toString());
            task.set(
                    "failure",
                    json.createObjectNode()
                            .put("code", "INTERNAL_ERROR")
                            .put("message", "知识库复查本机执行失败，请查看原任务后手动重试。")
                            .put("retryable", false));
            task.set("warnings", json.createArrayNode().add("连接检查可能已产生费用，请勿自动重新提交。"));
            save(task, null);
        }
    }

    private void save(ObjectNode task, JsonNode result) {
        data.write(jdbc -> {
            persist(jdbc, task, result);
            return null;
        });
    }

    private void persist(org.springframework.jdbc.core.simple.JdbcClient jdbc, ObjectNode task, JsonNode result) {
        TaskEvents.record(jdbc, json, task);
        jdbc.sql("UPDATE tasks SET state=?,payload=?,result=? WHERE id=?")
                .params(
                        task.path("state").asText(),
                        json.writeValueAsString(task),
                        result == null ? null : json.writeValueAsString(result),
                        task.path("id").asText())
                .update();
    }

    private record Pending(
            ConnectionTests.Task task,
            ModelSettingsService.EmbeddingSnapshot model,
            ModelSettingsService.VectorConnection vector,
            JsonNode config) {}

    @PreDestroy
    public void close() {
        stopping = true;
        worker.shutdownNow();
        try {
            worker.awaitTermination(6, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
