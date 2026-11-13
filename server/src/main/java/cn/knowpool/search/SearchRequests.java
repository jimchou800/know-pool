package cn.knowpool.search;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.knowledgebases.Indexes;
import cn.knowpool.knowledgebases.KnowledgeBases;
import cn.knowpool.system.ModelSettingsService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class SearchRequests {
    private final ApplicationData data;
    private final KnowledgeBases bases;
    private final Indexes indexes;
    private final JsonMapper json;
    private final java.util.Map<String, ObjectNode> requests = new java.util.HashMap<>();
    private final java.util.Map<String, String> keys = new java.util.HashMap<>();
    private final java.util.Map<String, String> inputs = new java.util.HashMap<>();
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(17);
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
                Thread t = new Thread(r, "management-search");
                t.setDaemon(true);
                return t;
            });
    private boolean stopping;

    public SearchRequests(ApplicationData data, KnowledgeBases bases, Indexes indexes, JsonMapper json) {
        this.data = data;
        this.bases = bases;
        this.indexes = indexes;
        this.json = json;
        data.write(jdbc -> {
            for (String payload : jdbc.sql(
                            "SELECT payload FROM tasks WHERE kind='search' AND state IN ('queued','running')")
                    .query(String.class)
                    .list()) {
                ObjectNode task = (ObjectNode) json.readTree(payload);
                task.put("state", "interrupted")
                        .put("phase", "completed")
                        .put("finishedAt", Instant.now().toString())
                        .putNull("resultResourceId");
                task.set(
                        "failure",
                        json.createObjectNode()
                                .put("code", "TASK_INTERRUPTED")
                                .put("message", "检索因重启中断，可能已产生费用，请手动重新检索。")
                                .put("retryable", false));
                cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, task);
                jdbc.sql("UPDATE tasks SET state='interrupted',payload=? WHERE id=?")
                        .params(json.writeValueAsString(task), task.path("id").asText())
                        .update();
            }
            return null;
        });
    }

    public synchronized JsonNode submit(String session, String kb, String key, JsonNode input) {
        bases.get(kb);
        ModelSettingsService.identifier(key, "Idempotency-Key");
        String operation = session + ":" + kb + ":" + key;
        if (keys.containsKey(operation)) {
            String id = keys.get(operation);
            if (!inputs.get(id).equals(json.writeValueAsString(input)))
                throw new ApiFailure(409, "CONFLICT", "同一幂等键不能提交不同内容。", null);
            return requests.get(id).deepCopy();
        }
        if (!capacity.tryAcquire()) throw new ApiFailure(429, "QUEUE_FULL", "检索队列已满，请稍后重试。", null);
        String id = UUID.randomUUID().toString(), taskId = UUID.randomUUID().toString();
        ObjectNode task = json.createObjectNode()
                .put("id", taskId)
                .put("kind", "search")
                .put("state", "queued")
                .put("phase", "queued")
                .put("createdAt", Instant.now().toString())
                .put("knowledgeBaseId", kb)
                .put("attempt", 0)
                .put("progressCompleted", 0);
        for (String field : List.of(
                "startedAt",
                "finishedAt",
                "fileId",
                "targetContentVersionId",
                "targetConfigVersionId",
                "retryOfTaskId",
                "progressTotal",
                "failure",
                "resultResourceId")) task.putNull(field);
        task.set("warnings", json.createArrayNode());
        ObjectNode request = json.createObjectNode()
                .put("id", id)
                .putNull("instanceId")
                .putNull("result")
                .putNull("scope");
        request.set("task", task);
        boolean submitted = false;
        try {
            data.write(jdbc -> {
                jdbc.sql("INSERT INTO search_identities VALUES(?)").param(id).update();
                cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, task);
                jdbc.sql(
                                "INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,'search','queued',?,'runtime','managementSearch',?,'')")
                        .params(taskId, json.writeValueAsString(task), taskId)
                        .update();
                return null;
            });
            requests.put(id, request);
            keys.put(operation, id);
            inputs.put(id, json.writeValueAsString(input));
            worker.execute(() -> {
                try {
                    run(id, kb, input.path("query").asText());
                } finally {
                    capacity.release();
                }
            });
            submitted = true;
            return request.deepCopy();
        } finally {
            if (!submitted) capacity.release();
        }
    }

    private void run(String id, String kb, String query) {
        synchronized (this) {
            ObjectNode task = (ObjectNode) requests.get(id).path("task");
            if (!List.of("queued","running").contains(task.path("state").asText())) return;
            task.put("state", "running")
                    .put("phase", "retrieving")
                    .put("startedAt", Instant.now().toString())
                    .put("attempt", 1);
            save(task);
        }
        try {
            JsonNode evidence = indexes.retrieve(kb, query);
            synchronized (this) {
                if (stopping || requests.get(id).at("/task/state").asText().equals("cancelled")) return;
                ObjectNode request = requests.get(id), task = (ObjectNode) request.path("task");
                ObjectNode scope = scope(kb, null);
                request.set("scope", scope);
                ObjectNode result = json.createObjectNode()
                        .put("requestId", id)
                        .putNull("instanceId")
                        .put("taskId", task.path("id").asText())
                        .put("query", query)
                        .put("outcome", evidence.isEmpty() ? "no_matches" : "matches")
                        .put("preprocessingState", "not_used");
                result.set("scope", scope);
                result.set("evidence", evidence);
                result.set("additionalQueries", json.createArrayNode());
                result.set("warnings", json.createArrayNode());
                data.write(jdbc -> {
                    for (JsonNode item : evidence)
                        jdbc.sql("INSERT INTO search_evidence_identities VALUES(?,?)")
                                .params(item.path("id").asText(), id)
                                .update();
                    return null;
                });
                request.set("result", result);
                task.put("state", "succeeded")
                        .put("phase", "completed")
                        .put("finishedAt", Instant.now().toString())
                        .put("resultResourceId", id);
                save(task);
            }
        } catch (RuntimeException failure) {
            synchronized (this) {
                if (stopping || requests.get(id).at("/task/state").asText().equals("cancelled")) return;
                ObjectNode request = requests.get(id), task = (ObjectNode) request.path("task");
                String code = failure instanceof ApiFailure api ? api.code() : "INTERNAL_ERROR";
                request.set(
                        "scope", scope(kb, code.equals("NO_EFFECTIVE_INDEX") ? "no_effective_index" : "unavailable"));
                task.put("state", "failed")
                        .put("phase", "completed")
                        .put("finishedAt", Instant.now().toString());
                task.set(
                        "failure",
                        json.createObjectNode()
                                .put("code", code)
                                .put("message", failure instanceof ApiFailure api ? api.getMessage() : "检索执行失败。")
                                .put("retryable", false));
                save(task);
            }
        }
    }

    private ObjectNode scope(String kb, String reason) {
        ObjectNode scope = json.createObjectNode().put("incomplete", reason != null);
        scope.set("selectedKnowledgeBaseIds", json.createArrayNode().add(kb));
        scope.set(
                "usedKnowledgeBaseIds", reason == null ? json.createArrayNode().add(kb) : json.createArrayNode());
        var omitted = json.createArrayNode();
        if (reason != null)
            omitted.add(json.createObjectNode()
                    .put("knowledgeBaseId", kb)
                    .put("reason", reason)
                    .put("message", reason.equals("no_effective_index") ? "知识库没有生效索引。" : "知识库依赖不可用。"));
        scope.set("omitted", omitted);
        return scope;
    }

    private void save(ObjectNode task) {
        data.write(jdbc -> {
            cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, task);
            jdbc.sql("UPDATE tasks SET state=?,payload=? WHERE id=?")
                    .params(
                            task.path("state").asText(),
                            json.writeValueAsString(task),
                            task.path("id").asText())
                    .update();
            return null;
        });
    }

    public synchronized JsonNode get(String id) {
        String valid = ModelSettingsService.identifier(id, "requestId");
        if (requests.containsKey(valid)) return requests.get(valid).deepCopy();
        boolean known = data.read(jdbc -> jdbc.sql("SELECT COUNT(*) FROM search_identities WHERE id=?")
                        .param(valid)
                        .query(Integer.class)
                        .single()
                > 0);
        throw new ApiFailure(
                known ? 410 : 404,
                known ? "RESULT_EXPIRED" : "NOT_FOUND",
                known ? "检索结果已失效，请手动重新检索。" : "检索请求不存在。",
                null);
    }

    public synchronized JsonNode cancel(String taskId) {
        String id = ModelSettingsService.identifier(taskId,"taskId");
        ObjectNode task = data.read(jdbc -> jdbc.sql("SELECT payload FROM tasks WHERE id=? AND kind='search'").param(id).query(String.class).optional()
                .map(value -> (ObjectNode)json.readTree(value)).orElseThrow(() -> new ApiFailure(404,"NOT_FOUND","检索任务不存在。",null)));
        if (!List.of("queued","running").contains(task.path("state").asText())) return task;
        task.put("state","cancelled").put("phase","completed").put("finishedAt",Instant.now().toString()).putNull("failure").putNull("resultResourceId");
        for (ObjectNode request : requests.values()) if (request.at("/task/id").asText().equals(id)) {
            request.set("task",task); request.putNull("result");
        }
        save(task);
        return task;
    }

    public synchronized JsonNode evidence(String id, String evidenceId) {
        ModelSettingsService.identifier(evidenceId, "evidenceId");
        JsonNode request = get(id);
        for (JsonNode item : request.at("/result/evidence"))
            if (item.path("id").asText().equals(evidenceId)) return item;
        throw new ApiFailure(404, "NOT_FOUND", "本次检索证据不存在。", null);
    }

    @PreDestroy
    public void close() {
        synchronized (this) {
            stopping = true;
        }
        worker.shutdownNow();
        try {
            worker.awaitTermination(6, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
