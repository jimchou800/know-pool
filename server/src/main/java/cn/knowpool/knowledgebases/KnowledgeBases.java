package cn.knowpool.knowledgebases;

import cn.knowpool.datasets.FileService;
import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.system.ConnectionTests;
import cn.knowpool.system.ModelSettingsService;
import jakarta.annotation.PreDestroy;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class KnowledgeBases {
    private final ApplicationData data;
    private final ModelSettingsService settings;
    private final VectorCollections vectors;
    private final JsonMapper json;
    private final FileService files;
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(17);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "knowledge-base-create");
                thread.setDaemon(true);
                return thread;
            });

    public KnowledgeBases(
            ApplicationData data,
            ModelSettingsService settings,
            VectorCollections vectors,
            JsonMapper json,
            FileService files) {
        this.data = data;
        this.settings = settings;
        this.vectors = vectors;
        this.json = json;
        this.files = files;
        recover();
    }

    public ConnectionTests.Accepted create(String session, String key, JsonNode input) {
        String validKey = ModelSettingsService.identifier(key, "Idempotency-Key");
        String fingerprint = json.writeValueAsString(input);
        AtomicBoolean acquired = new AtomicBoolean();
        boolean committed = false;
        Pending pending;
        try {
            pending = data.write(jdbc -> {
                List<String> old = jdbc.sql(
                                "SELECT payload FROM tasks WHERE session_id=? AND operation='createKnowledgeBase' AND idempotency_key=?")
                        .params(session, validKey)
                        .query(String.class)
                        .list();
                if (!old.isEmpty()) {
                    String previous = jdbc.sql(
                                    "SELECT request_fingerprint FROM tasks WHERE session_id=? AND operation='createKnowledgeBase' AND idempotency_key=?")
                            .params(session, validKey)
                            .query(String.class)
                            .single();
                    if (!json.readTree(previous).equals(input))
                        throw new ApiFailure(409, "CONFLICT", "同一幂等键不能提交不同内容。", null);
                    return new Pending(json.readValue(old.getFirst(), ConnectionTests.Task.class), null, null);
                }
                JsonNode embedding = input.path("processing").path("embedding");
                ModelSettingsService.ModelConfig model = settings.historical(
                        embedding.path("modelConfigVersionId").asText());
                if (model.endpoint() == null
                        || !model.endpoint()
                                .equals(embedding.at("/modelSnapshot/endpoint").asText())
                        || !model.model()
                                .equals(embedding.at("/modelSnapshot/model").asText()))
                    throw new ApiFailure(
                            422, "INVALID_STATE", "嵌入快照与保存的配置版本不匹配。", "/processing/embedding/modelSnapshot");
                ModelSettingsService.VectorConnection vector = settings.vectorConnection();
                if (vector.endpoint() == null) throw new ApiFailure(422, "INVALID_STATE", "请先保存向量服务连接配置。", null);
                for (JsonNode dataset : input.path("datasetIds")) files.sourceFiles(jdbc, "dataset", dataset.asText());
                String id = UUID.randomUUID().toString(),
                        configId = UUID.randomUUID().toString(),
                        now = Instant.now().toString();
                String name = normalize(input.path("name").asText());
                unique(jdbc, name, "");
                if (!capacity.tryAcquire()) throw new ApiFailure(429, "QUEUE_FULL", "知识库创建队列已满，请稍后重试。", null);
                acquired.set(true);
                ObjectNode kb = json.createObjectNode();
                kb.put("id", id)
                        .put("name", name)
                        .put("description", input.path("description").asText(""))
                        .put("createdAt", now)
                        .put("updatedAt", now)
                        .put("publicationState", "unpublished")
                        .putNull("publishedAt")
                        .putNull("offlineAt")
                        .putNull("offlineReason")
                        .put("availability", "available")
                        .putNull("availabilityReason")
                        .put("lastCheckedAt", now)
                        .put("processingState", "idle")
                        .put("targetConfigVersionId", configId)
                        .putNull("effectiveConfigVersionId")
                        .putNull("effectiveIndexVersionId")
                        .put("includedFileCount", 0)
                        .put("searchableFileCount", 0);
                ObjectNode config = json.createObjectNode();
                config.put("id", configId)
                        .put("knowledgeBaseId", id)
                        .put("createdAt", now)
                        .put("parserRuleVersion", DocumentSources.RULE_VERSION)
                        .put("chunkRuleVersion", ChunkPlans.RULE_VERSION)
                        .put("lengthMeterVersion", "unicode-code-points-1")
                        .set("input", input.path("processing"));
                try {
                    jdbc.sql("INSERT INTO knowledge_bases(id,name_key,visible,payload,config) VALUES(?,?,0,?,?)")
                            .params(
                                    id,
                                    name.toLowerCase(Locale.ROOT),
                                    json.writeValueAsString(kb),
                                    json.writeValueAsString(config))
                            .update();
                } catch (DuplicateKeyException duplicate) {
                    throw new ApiFailure(409, "CONFLICT", "知识库名称已存在。", "/name");
                }
                ConnectionTests.Task task = task(UUID.randomUUID().toString(), id, configId, "queued", null, null);
                jdbc.sql(
                                "INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,?,?,?,?,'createKnowledgeBase',?,?)")
                        .params(
                                task.id(),
                                task.kind(),
                                task.state(),
                                json.writeValueAsString(task),
                                session,
                                validKey,
                                fingerprint)
                        .update();
                cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, json.valueToTree(task));
                jdbc.sql(
                                "INSERT INTO knowledge_base_creations(task_id,knowledge_base_id,collection_name,endpoint,ownership,phase,datasets) VALUES(?,?,?,?,'none','reserved',?)")
                        .params(
                                task.id(),
                                id,
                                "kp_" + id.replace("-", ""),
                                vector.endpoint(),
                                json.writeValueAsString(
                                        input.path("datasetIds").isMissingNode()
                                                ? json.createArrayNode()
                                                : input.path("datasetIds")))
                        .update();
                return new Pending(task, vector, embedding);
            });
            committed = true;
        } finally {
            if (!committed && acquired.get()) capacity.release();
        }
        if (pending.vector() != null) {
            executor.execute(() -> {
                try {
                    initialize(pending);
                } finally {
                    capacity.release();
                }
            });
        }
        return new ConnectionTests.Accepted(pending.task());
    }

    private void initialize(Pending pending) {
        ConnectionTests.Task task = pending.task();
        String collection = "kp_" + task.knowledgeBaseId().replace("-", "");
        String endpoint = pending.vector().endpoint(), secret = pending.vector().secret();
        try {
            if (vectors.request(endpoint, secret, collection, "GET", null) != null)
                throw new ApiFailure(409, "INTEGRITY_FAILED", "专属集合名称已存在，未接管已有资源。", null);
            data.write(jdbc -> {
                jdbc.sql("UPDATE knowledge_base_creations SET phase='creating',ownership='unknown' WHERE task_id=?")
                        .param(task.id())
                        .update();
                persist(
                        jdbc,
                        task(task.id(), task.knowledgeBaseId(), task.targetConfigVersionId(), "running", null, null));
                return null;
            });
            String body = json.writeValueAsString(java.util.Map.of(
                    "vectors",
                    java.util.Map.of(
                            "size",
                            pending.embedding().path("dimensions").asInt(),
                            "distance",
                            VectorCollections.distance(
                                    pending.embedding().path("distance").asText()))));
            JsonNode created = vectors.request(endpoint, secret, collection, "PUT", body);
            if (!created.path("result").isBoolean() || !created.path("result").asBoolean())
                throw new java.io.IOException("集合创建结果不确定。");
            data.write(jdbc -> {
                jdbc.sql("UPDATE knowledge_base_creations SET ownership='owned',phase='created' WHERE task_id=?")
                        .param(task.id())
                        .update();
                return null;
            });
            JsonNode actual = vectors.request(endpoint, secret, collection, "GET", null);
            if (actual == null
                    || actual.at("/result/config/params/vectors/size").asInt()
                            != pending.embedding().path("dimensions").asInt()
                    || !actual.at("/result/config/params/vectors/distance")
                            .asText()
                            .equals(VectorCollections.distance(
                                    pending.embedding().path("distance").asText())))
                throw new ApiFailure(500, "INTEGRITY_FAILED", "专属集合的维度或距离配置核验失败。", null);
            if (stopping.get()) return;
            data.write(jdbc -> {
                jdbc.sql("UPDATE knowledge_bases SET visible=1 WHERE id=?")
                        .param(task.knowledgeBaseId())
                        .update();
                JsonNode datasets =
                        json.readTree(jdbc.sql("SELECT datasets FROM knowledge_base_creations WHERE task_id=?")
                                .param(task.id())
                                .query(String.class)
                                .single());
                for (JsonNode dataset : datasets) addSource(jdbc, task.knowledgeBaseId(), "dataset", dataset.asText());
                jdbc.sql("UPDATE knowledge_base_creations SET phase='completed' WHERE task_id=?")
                        .param(task.id())
                        .update();
                persist(
                        jdbc,
                        task(
                                task.id(),
                                task.knowledgeBaseId(),
                                task.targetConfigVersionId(),
                                "succeeded",
                                null,
                                task.knowledgeBaseId()));
                return null;
            });
        } catch (java.io.IOException | RuntimeException failure) {
            if (!stopping.get()) {
                String code = failure instanceof ApiFailure api
                        ? api.code()
                        : failure instanceof org.springframework.dao.DataAccessException
                                ? "INTERNAL_ERROR"
                                : "VECTOR_UNAVAILABLE";
                String message = failure instanceof ApiFailure api
                        ? api.getMessage()
                        : code.equals("INTERNAL_ERROR") ? "知识库初始化的本机持久提交失败。" : "向量服务初始化请求失败或结果不确定，请核查原任务。";
                fail(task, code, message);
                cleanup(task.id(), endpoint, secret, collection);
            }
        }
    }

    private void fail(ConnectionTests.Task task, String code, String message) {
        data.write(jdbc -> {
            persist(
                    jdbc,
                    task(
                            task.id(),
                            task.knowledgeBaseId(),
                            task.targetConfigVersionId(),
                            "failed",
                            new ConnectionTests.Failure(code, message, false),
                            null));
            String ownership = jdbc.sql("SELECT ownership FROM knowledge_base_creations WHERE task_id=?")
                    .param(task.id())
                    .query(String.class)
                    .single();
            if (ownership.equals("unknown")) warning(jdbc, task.id(), "集合创建结果或归属不确定，已保留核查线索，未自动删除远端资源。");
            jdbc.sql("DELETE FROM knowledge_bases WHERE id=? AND visible=0")
                    .param(task.knowledgeBaseId())
                    .update();
            return null;
        });
    }

    private void cleanup(String taskId, String endpoint, String secret, String collection) {
        String owner = data.read(jdbc -> jdbc.sql("SELECT ownership FROM knowledge_base_creations WHERE task_id=?")
                .param(taskId)
                .query(String.class)
                .single());
        if (!owner.equals("owned")) return;
        try {
            if (vectors.request(endpoint, secret, collection, "GET", null) != null) {
                JsonNode result = vectors.request(endpoint, secret, collection, "DELETE", null);
                if (!result.path("result").isBoolean()
                        || !result.path("result").asBoolean()
                        || vectors.request(endpoint, secret, collection, "GET", null) != null)
                    throw new java.io.IOException("集合删除尚未核验。");
            }
            data.write(jdbc -> {
                jdbc.sql("UPDATE knowledge_base_creations SET phase='cleaned',cleanup_error=NULL WHERE task_id=?")
                        .param(taskId)
                        .update();
                return null;
            });
        } catch (java.io.IOException | RuntimeException failure) {
            data.write(jdbc -> {
                jdbc.sql(
                                "UPDATE knowledge_base_creations SET phase='cleanup_pending',cleanup_error=?,cleanup_attempts=cleanup_attempts+1 WHERE task_id=?")
                        .params("自有集合清理失败，待恢复核查。", taskId)
                        .update();
                warning(jdbc, taskId, "初始化失败后的自有集合尚待清理，已保存补偿任务。");
                return null;
            });
        }
    }

    private void recover() {
        List<ConnectionTests.Task> tasks = data.read(jdbc -> jdbc
                .sql("SELECT payload FROM tasks WHERE kind='knowledge_base_create' AND state IN ('queued','running')")
                .query(String.class)
                .list()
                .stream()
                .map(value -> json.readValue(value, ConnectionTests.Task.class))
                .toList());
        for (ConnectionTests.Task task : tasks) fail(task, "TASK_INTERRUPTED", "知识库创建因应用重启中断，请核查原任务。");
        List<String[]> pending = data.read(jdbc -> jdbc.sql(
                        "SELECT task_id,endpoint,collection_name FROM knowledge_base_creations WHERE ownership='owned' AND phase<>'completed' AND phase<>'cleaned' AND cleanup_attempts<3")
                .query((row, index) -> new String[] {row.getString(1), row.getString(2), row.getString(3)})
                .list());
        ModelSettingsService.VectorConnection current = settings.vectorConnection();
        if (!pending.isEmpty()) {
            capacity.acquireUninterruptibly();
            executor.execute(() -> {
                try {
                    for (String[] item : pending) {
                        if (stopping.get()) return;
                        if (item[1].equals(current.endpoint())) cleanup(item[0], item[1], current.secret(), item[2]);
                    }
                } finally {
                    capacity.release();
                }
            });
        }
    }

    public JsonNode get(String id) {
        return data.read(jdbc -> get(jdbc, id));
    }

    private JsonNode get(JdbcClient jdbc, String id) {
        return jdbc.sql("SELECT payload FROM knowledge_bases WHERE id=? AND visible=1")
                .param(ModelSettingsService.identifier(id, "knowledgeBaseId"))
                .query(String.class)
                .optional()
                .map(json::readTree)
                .orElseThrow(() -> new ApiFailure(404, "NOT_FOUND", "知识库不存在。", null));
    }

    public void refreshProcessing(JdbcClient jdbc, String id) {
        ObjectNode value = (ObjectNode) get(jdbc, id);
        String state = jdbc.sql(
                        """
                SELECT CASE
                  WHEN SUM(t.state='running')>0 THEN 'running'
                  WHEN SUM(t.state='queued')>0 THEN 'queued'
                  WHEN SUM(t.state IN ('failed','interrupted'))>0 THEN 'failed'
                  ELSE 'idle' END
                FROM tasks t WHERE t.id IN (
                  SELECT task_id FROM knowledge_base_files WHERE knowledge_base_id=?
                  UNION SELECT r.task_id FROM processing_rebuilds r
                  WHERE r.knowledge_base_id=? AND r.config_version_id=json_extract(?, '$.targetConfigVersionId'))
                """)
                .params(id, id, json.writeValueAsString(value))
                .query(String.class)
                .single();
        value.put("processingState", state);
        jdbc.sql("UPDATE knowledge_bases SET payload=? WHERE id=?")
                .params(json.writeValueAsString(value), id)
                .update();
    }

    public JsonNode config(String id) {
        return data.read(jdbc -> {
            get(jdbc, id);
            return json.readTree(jdbc.sql("SELECT config FROM knowledge_bases WHERE id=?")
                    .param(id)
                    .query(String.class)
                    .single());
        });
    }

    public JsonNode config(String id, String version) {
        return data.read(jdbc -> {
            get(jdbc, id);
            return jdbc.sql("SELECT payload FROM processing_config_history WHERE id=? AND knowledge_base_id=?")
                    .params(version, id).query(String.class).optional().map(json::readTree)
                    .orElseGet(() -> {
                        JsonNode current = config(id);
                        if (!current.path("id").asText().equals(version)) throw new ApiFailure(404, "NOT_FOUND", "处理配置版本不存在。", null);
                        return current;
                    });
        });
    }

    String collection(String id, String version) {
        return data.read(jdbc -> jdbc.sql("SELECT collection_name FROM processing_config_history WHERE id=? AND knowledge_base_id=?")
                .params(version, id).query(String.class).optional().orElse("kp_" + id.replace("-", "")));
    }

    public ConnectionTests.Accepted updateConfig(String id, String session, String key, JsonNode input) {
        String validKey = ModelSettingsService.identifier(key, "Idempotency-Key");
        return data.write(jdbc -> {
            ObjectNode base = (ObjectNode) get(jdbc, id);
            String operation = "updateProcessingConfig:" + id;
            var previous = jdbc.sql("SELECT payload,request_fingerprint FROM tasks WHERE session_id=? AND operation=? AND idempotency_key=?")
                    .params(session, operation, validKey).query((row, n) -> new String[]{row.getString(1), row.getString(2)}).list();
            if (!previous.isEmpty()) {
                if (!json.readTree(previous.getFirst()[1]).equals(input)) throw new ApiFailure(409, "CONFLICT", "同一幂等键不能修改不同配置。", null);
                return new ConnectionTests.Accepted(json.readValue(previous.getFirst()[0], ConnectionTests.Task.class));
            }
            JsonNode embedding = input.path("embedding");
            var model = settings.historical(embedding.path("modelConfigVersionId").asText());
            if (model.endpoint() == null || !model.endpoint().equals(embedding.at("/modelSnapshot/endpoint").asText())
                    || !model.model().equals(embedding.at("/modelSnapshot/model").asText()))
                throw new ApiFailure(422, "MODEL_CONFIG_INVALID", "嵌入快照与已保存版本不匹配。", "/embedding/modelSnapshot");
            if (settings.vectorConnection().endpoint() == null) throw new ApiFailure(422, "MODEL_CONFIG_INVALID", "请先设置向量连接。", null);
            if (jdbc.sql("SELECT COUNT(*) FROM processing_rebuilds r JOIN tasks t ON t.id=r.task_id WHERE t.state IN ('queued','running')")
                    .query(Integer.class).single() >= 17) throw new ApiFailure(429, "QUEUE_FULL", "配置重建队列已满。", null);
            JsonNode oldConfig = config(id);
            jdbc.sql("INSERT OR IGNORE INTO processing_config_history VALUES(?,?,?,?)").params(oldConfig.path("id").asText(), id,
                    json.writeValueAsString(oldConfig), "kp_" + id.replace("-", "")).update();
            String taskId = UUID.randomUUID().toString(), version = UUID.randomUUID().toString(), now = Instant.now().toString();
            ObjectNode config = json.createObjectNode().put("id", version).put("knowledgeBaseId", id).put("createdAt", now)
                    .put("parserRuleVersion", DocumentSources.RULE_VERSION).put("chunkRuleVersion", ChunkPlans.RULE_VERSION)
                    .put("lengthMeterVersion", "unicode-code-points-1");
            config.set("input", input);
            jdbc.sql("INSERT INTO processing_config_history VALUES(?,?,?,?)").params(version, id, json.writeValueAsString(config), "kp_" + id.replace("-", "") + "_" + version.replace("-", "")).update();
            for (String payload : jdbc.sql("SELECT payload FROM tasks WHERE state IN ('queued','running') AND kind IN ('index_file','rebuild_knowledge_base') AND json_extract(payload,'$.knowledgeBaseId')=?").param(id).query(String.class).list()) {
                ObjectNode cancelled = (ObjectNode) json.readTree(payload);
                cancelled.put("state", "cancelled").put("phase", "completed").put("finishedAt", now).putNull("failure").putNull("resultResourceId");
                cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, cancelled);
                jdbc.sql("UPDATE tasks SET state='cancelled',payload=? WHERE id=?").params(json.writeValueAsString(cancelled), cancelled.path("id").asText()).update();
            }
            base.put("targetConfigVersionId", version).put("processingState", "queued").put("updatedAt", now);
            jdbc.sql("UPDATE knowledge_bases SET config=?,payload=? WHERE id=?").params(json.writeValueAsString(config), json.writeValueAsString(base), id).update();
            ConnectionTests.Task parent = new ConnectionTests.Task(taskId, "rebuild_knowledge_base", "queued", "queued", now, null, null,
                    id, null, null, version, null, 0, 0, null, null, List.of(), null);
            jdbc.sql("INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,'rebuild_knowledge_base','queued',?,?,?,?,?)")
                    .params(taskId, json.writeValueAsString(parent), session, operation, validKey, json.writeValueAsString(input)).update();
            jdbc.sql("INSERT INTO processing_rebuilds(task_id,knowledge_base_id,config_version_id) VALUES(?,?,?)").params(taskId, id, version).update();
            cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, json.valueToTree(parent));
            for (String file : jdbc.sql("SELECT file_id FROM knowledge_base_files WHERE knowledge_base_id=?").param(id).query(String.class).list()) {
                var source = files.sourceFiles(jdbc, "file", file).getFirst();
                String childId = UUID.randomUUID().toString();
                ConnectionTests.Task child = new ConnectionTests.Task(childId, "index_file", "queued", "queued", now, null, null,
                        id, file, source.contentVersionId(), version, null, 0, 0, null, null, List.of(), null);
                jdbc.sql("INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,'index_file','queued',?,'internal','index_file',?,'')")
                        .params(childId, json.writeValueAsString(child), childId).update();
                jdbc.sql("UPDATE knowledge_base_files SET task_id=?,content_version_id=?,config_version_id=? WHERE knowledge_base_id=? AND file_id=?")
                        .params(childId, source.contentVersionId(), version, id, file).update();
                cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, json.valueToTree(child));
            }
            return new ConnectionTests.Accepted(parent);
        });
    }

    public Page list(int limit, String cursor) {
        return data.read(jdbc -> {
            String after = cursor == null ? "" : ModelSettingsService.identifier(cursor, "cursor");
            List<JsonNode> rows = jdbc
                    .sql("SELECT payload FROM knowledge_bases WHERE visible=1 AND id>? ORDER BY id LIMIT ?")
                    .params(after, limit + 1)
                    .query(String.class)
                    .list()
                    .stream()
                    .map(json::readTree)
                    .toList();
            return new Page(
                    rows.subList(0, Math.min(limit, rows.size())),
                    rows.size() > limit ? rows.get(limit - 1).path("id").asText() : null);
        });
    }

    public JsonNode update(String id, JsonNode input) {
        return data.write(jdbc -> {
            ObjectNode value = (ObjectNode) get(jdbc, id);
            if (input.has("name"))
                value.put("name", normalize(input.path("name").asText()));
            if (input.has("description"))
                value.put("description", input.path("description").asText());
            unique(jdbc, value.path("name").asText(), id);
            value.put("updatedAt", Instant.now().toString());
            jdbc.sql("UPDATE knowledge_bases SET name_key=?,payload=? WHERE id=?")
                    .params(value.path("name").asText().toLowerCase(Locale.ROOT), json.writeValueAsString(value), id)
                    .update();
            return value;
        });
    }

    public JsonNode addSource(String id, String type, String resource) {
        return data.write(jdbc -> {
            get(jdbc, id);
            return addSource(jdbc, id, type, resource);
        });
    }

    @org.springframework.context.event.EventListener
    public void contentCommitted(FileService.ContentCommitted event) {
        contentCommitted(event,null);
    }

    private void contentCommitted(FileService.ContentCommitted event, String targetBase) {
        JdbcClient jdbc = event.jdbc();
        List<String> ids = jdbc.sql(
                        "SELECT DISTINCT s.knowledge_base_id FROM knowledge_base_sources s JOIN knowledge_bases b ON b.id=s.knowledge_base_id WHERE b.visible=1 AND ((s.type='file' AND s.resource_id=?) OR (s.type='dataset' AND s.resource_id=?))")
                .params(event.fileId(), event.datasetId())
                .query(String.class)
                .list();
        for (String kb : ids) {
            if (targetBase != null && !targetBase.equals(kb)) continue;
            if (excluded(jdbc,kb,event.fileId())) continue;
            for (String payload : jdbc.sql(
                            "SELECT payload FROM tasks WHERE kind='index_file' AND state IN ('queued','running') AND json_extract(payload,'$.knowledgeBaseId')=? AND json_extract(payload,'$.fileId')=?")
                    .params(kb, event.fileId())
                    .query(String.class)
                    .list()) {
                ObjectNode old = (ObjectNode) json.readTree(payload);
                old.put("state", "cancelled")
                        .put("phase", "completed")
                        .put("finishedAt", Instant.now().toString());
                old.putNull("failure").putNull("resultResourceId");
                cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, old);
                jdbc.sql("UPDATE tasks SET state='cancelled',payload=? WHERE id=?")
                        .params(json.writeValueAsString(old), old.path("id").asText())
                        .update();
            }
            String config = get(jdbc, kb).path("targetConfigVersionId").asText(),
                    taskId = UUID.randomUUID().toString();
            ConnectionTests.Task task = new ConnectionTests.Task(
                    taskId,
                    "index_file",
                    "queued",
                    "queued",
                    Instant.now().toString(),
                    null,
                    null,
                    kb,
                    event.fileId(),
                    event.contentVersionId(),
                    config,
                    null,
                    0,
                    0,
                    null,
                    null,
                    List.of(),
                    null);
            jdbc.sql(
                            "INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,'index_file','queued',?,'internal','index_file',?,'')")
                    .params(taskId, json.writeValueAsString(task), taskId)
                    .update();
            cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, json.valueToTree(task));
            jdbc.sql(
                            "INSERT INTO knowledge_base_files VALUES(?,?,?,?,?) ON CONFLICT(knowledge_base_id,file_id) DO UPDATE SET content_version_id=excluded.content_version_id,config_version_id=excluded.config_version_id,task_id=excluded.task_id")
                    .params(kb, event.fileId(), event.contentVersionId(), config, taskId)
                    .update();
            ObjectNode value = (ObjectNode) get(jdbc, kb);
            value.put("processingState", "queued")
                    .put(
                            "includedFileCount",
                            jdbc.sql("SELECT COUNT(*) FROM knowledge_base_files WHERE knowledge_base_id=?")
                                    .param(kb)
                                    .query(Integer.class)
                                    .single());
            jdbc.sql("UPDATE knowledge_bases SET payload=? WHERE id=?")
                    .params(json.writeValueAsString(value), kb)
                    .update();
            refreshProcessing(jdbc, kb);
            event.addTaskId(taskId);
        }
    }

    private JsonNode addSource(JdbcClient jdbc, String id, String type, String resource) {
        if (jdbc.sql(
                                "SELECT COUNT(*) FROM knowledge_base_sources WHERE knowledge_base_id=? AND type=? AND resource_id=?")
                        .params(id, type, resource)
                        .query(Integer.class)
                        .single()
                > 0) throw new ApiFailure(409, "CONFLICT", "该来源已关联。", null);
        List<FileService.SourceFile> included = files.sourceFiles(jdbc, type, resource);
        String sourceId = UUID.randomUUID().toString();
        ObjectNode source = json.createObjectNode();
        source.put("id", sourceId)
                .put("knowledgeBaseId", id)
                .put("type", type)
                .put("resourceId", resource)
                .put("createdAt", Instant.now().toString());
        try {
            jdbc.sql(
                            "INSERT INTO knowledge_base_sources(id,knowledge_base_id,type,resource_id,payload) VALUES(?,?,?,?,?)")
                    .params(sourceId, id, type, resource, json.writeValueAsString(source))
                    .update();
        } catch (DuplicateKeyException duplicate) {
            throw new ApiFailure(409, "CONFLICT", "该来源已关联。", null);
        }
        ObjectNode kb = (ObjectNode) get(jdbc, id);
        String config = kb.path("targetConfigVersionId").asText();
        for (FileService.SourceFile file : included) {
            if (excluded(jdbc,id,file.id())) continue;
            if (jdbc.sql("SELECT COUNT(*) FROM knowledge_base_files WHERE knowledge_base_id=? AND file_id=?")
                            .params(id, file.id())
                            .query(Integer.class)
                            .single()
                    > 0) continue;
            String taskId = UUID.randomUUID().toString();
            ConnectionTests.Task task = new ConnectionTests.Task(
                    taskId,
                    "index_file",
                    "queued",
                    "queued",
                    Instant.now().toString(),
                    null,
                    null,
                    id,
                    file.id(),
                    file.contentVersionId(),
                    config,
                    null,
                    0,
                    0,
                    null,
                    null,
                    List.of(),
                    null);
            jdbc.sql(
                            "INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,'index_file','queued',?,'internal','index_file',?,'')")
                    .params(taskId, json.writeValueAsString(task), taskId)
                    .update();
            cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, json.valueToTree(task));
            jdbc.sql(
                            "INSERT INTO knowledge_base_files(knowledge_base_id,file_id,content_version_id,config_version_id,task_id) VALUES(?,?,?,?,?)")
                    .params(id, file.id(), file.contentVersionId(), config, taskId)
                    .update();
        }
        int count = jdbc.sql("SELECT COUNT(*) FROM knowledge_base_files WHERE knowledge_base_id=?")
                .param(id)
                .query(Integer.class)
                .single();
        kb.put("includedFileCount", count)
                .put("processingState", count == 0 ? "idle" : "queued")
                .put("updatedAt", Instant.now().toString());
        jdbc.sql("UPDATE knowledge_bases SET payload=? WHERE id=?")
                .params(json.writeValueAsString(kb), id)
                .update();
        refreshProcessing(jdbc, id);
        return source;
    }

    public Page sources(String id, int limit, String cursor) {
        return data.read(jdbc -> {
            get(jdbc, id);
            return page(
                    jdbc
                            .sql(
                                    "SELECT payload FROM knowledge_base_sources WHERE knowledge_base_id=? AND id>? ORDER BY id LIMIT ?")
                            .params(id, after(cursor), limit + 1)
                            .query(String.class)
                            .list()
                            .stream()
                            .map(json::readTree)
                            .toList(),
                    limit,
                    "id");
        });
    }

    private boolean excluded(JdbcClient jdbc, String kb, String file) {
        return jdbc.sql("SELECT COUNT(*) FROM knowledge_base_exclusions WHERE knowledge_base_id=? AND file_id=?")
                .params(kb,file).query(Integer.class).single() > 0;
    }

    private boolean hasSource(JdbcClient jdbc, String kb, FileService.SourceFile file) {
        return jdbc.sql("SELECT COUNT(*) FROM knowledge_base_sources WHERE knowledge_base_id=? AND ((type='file' AND resource_id=?) OR (type='dataset' AND resource_id=?))")
                .params(kb,file.id(),file.datasetId()).query(Integer.class).single() > 0;
    }

    public void removeSource(String kb, String sourceId) {
        String valid = ModelSettingsService.identifier(sourceId,"sourceId");
        data.write(jdbc -> {
            get(jdbc,kb);
            JsonNode source = jdbc.sql("SELECT payload FROM knowledge_base_sources WHERE id=? AND knowledge_base_id=?")
                    .params(valid,kb).query(String.class).optional().map(json::readTree)
                    .orElseThrow(() -> new ApiFailure(404,"NOT_FOUND","关联来源不存在。",null));
            List<FileService.SourceFile> affected = files.sourceFiles(jdbc,source.path("type").asText(),source.path("resourceId").asText());
            jdbc.sql("DELETE FROM knowledge_base_sources WHERE id=? AND knowledge_base_id=?").params(valid,kb).update();
            for (var file : affected) if (!hasSource(jdbc,kb,file)) detach(jdbc,kb,file.id());
            updateCounts(jdbc,kb);
            return null;
        });
    }

    public JsonNode exclude(String kb, String fileId) {
        String file = ModelSettingsService.identifier(fileId,"fileId");
        return data.write(jdbc -> {
            get(jdbc,kb); files.sourceFiles(jdbc,"file",file);
            JsonNode exclusion = jdbc.sql("SELECT payload FROM knowledge_base_exclusions WHERE knowledge_base_id=? AND file_id=?")
                    .params(kb,file).query(String.class).optional().map(json::readTree).orElse(null);
            if (exclusion != null) return exclusion;
            ObjectNode value = json.createObjectNode().put("knowledgeBaseId",kb).put("fileId",file).put("createdAt",Instant.now().toString());
            jdbc.sql("INSERT INTO knowledge_base_exclusions VALUES(?,?,?)").params(kb,file,json.writeValueAsString(value)).update();
            detach(jdbc,kb,file); updateCounts(jdbc,kb);
            return value;
        });
    }

    public void include(String kb, String fileId) {
        String file = ModelSettingsService.identifier(fileId,"fileId");
        data.write(jdbc -> {
            get(jdbc,kb);
            var source = files.sourceFiles(jdbc,"file",file).getFirst();
            if (jdbc.sql("DELETE FROM knowledge_base_exclusions WHERE knowledge_base_id=? AND file_id=?").params(kb,file).update() == 0)
                throw new ApiFailure(404,"NOT_FOUND","排除记录不存在。",null);
            if (hasSource(jdbc,kb,source)) {
                var event = new FileService.ContentCommitted(jdbc,file,source.datasetId(),source.contentVersionId());
                contentCommitted(event,kb);
            }
            updateCounts(jdbc,kb);
            return null;
        });
    }

    public Page exclusions(String kb, int limit, String cursor) {
        return data.read(jdbc -> {
            get(jdbc,kb);
            List<JsonNode> rows = jdbc.sql("SELECT payload FROM knowledge_base_exclusions WHERE knowledge_base_id=? AND file_id>? ORDER BY file_id LIMIT ?")
                    .params(kb,after(cursor),limit+1).query(String.class).list().stream().map(json::readTree).toList();
            return page(rows,limit,"fileId");
        });
    }

    private void detach(JdbcClient jdbc, String kb, String file) {
        String now = Instant.now().toString();
        for (String payload : jdbc.sql("SELECT payload FROM tasks WHERE kind='index_file' AND state IN ('queued','running') AND json_extract(payload,'$.knowledgeBaseId')=? AND json_extract(payload,'$.fileId')=?")
                .params(kb,file).query(String.class).list()) {
            ObjectNode task = (ObjectNode) json.readTree(payload);
            task.put("state","cancelled").put("phase","completed").put("finishedAt",now).putNull("failure").putNull("resultResourceId");
            cn.knowpool.infrastructure.TaskEvents.record(jdbc,json,task);
            jdbc.sql("UPDATE tasks SET state='cancelled',payload=? WHERE id=?").params(json.writeValueAsString(task),task.path("id").asText()).update();
        }
        var targets = json.createArrayNode();
        jdbc.sql("SELECT r.id,json_extract(t.payload,'$.targetConfigVersionId') FROM index_runs r JOIN tasks t ON t.id=r.task_id WHERE json_extract(t.payload,'$.knowledgeBaseId')=? AND json_extract(t.payload,'$.fileId')=?")
                .params(kb,file).query((row,n) -> {
                    targets.addObject().put("indexId",row.getString(1)).put("collection",collection(kb,row.getString(2)));
                    return true;
                }).list();
        if (!targets.isEmpty()) {
            String id = UUID.randomUUID().toString();
            ConnectionTests.Task cleanup = new ConnectionTests.Task(id,"cleanup","queued","queued",now,null,null,kb,file,null,null,null,0,0,(long)targets.size(),null,List.of(),null);
            jdbc.sql("INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,'cleanup','queued',?,'internal','cleanup',?,'')")
                    .params(id,json.writeValueAsString(cleanup),id).update();
            jdbc.sql("INSERT INTO index_cleanup VALUES(?,?,?)").params(id,json.writeValueAsString(targets),settings.vectorConnection().endpoint()).update();
            cn.knowpool.infrastructure.TaskEvents.record(jdbc,json,json.valueToTree(cleanup));
        }
        jdbc.sql("DELETE FROM effective_files WHERE knowledge_base_id=? AND file_id=?").params(kb,file).update();
        jdbc.sql("DELETE FROM staged_indexes WHERE knowledge_base_id=? AND file_id=?").params(kb,file).update();
        jdbc.sql("DELETE FROM knowledge_base_files WHERE knowledge_base_id=? AND file_id=?").params(kb,file).update();
    }

    private void updateCounts(JdbcClient jdbc, String kb) {
        ObjectNode value = (ObjectNode) get(jdbc,kb);
        int included = jdbc.sql("SELECT COUNT(*) FROM knowledge_base_files WHERE knowledge_base_id=?").param(kb).query(Integer.class).single();
        int searchable = jdbc.sql("SELECT COUNT(*) FROM effective_files WHERE knowledge_base_id=?").param(kb).query(Integer.class).single();
        value.put("includedFileCount",included).put("searchableFileCount",searchable).put("updatedAt",Instant.now().toString());
        if (searchable == 0 && value.path("publicationState").asText().equals("published"))
            value.put("publicationState","offline").put("offlineAt",Instant.now().toString()).put("offlineReason","no_searchable_files");
        jdbc.sql("UPDATE knowledge_bases SET payload=? WHERE id=?").params(json.writeValueAsString(value),kb).update();
        refreshProcessing(jdbc,kb);
    }

    public Page tasks(String id, int limit, String cursor) {
        return data.read(jdbc -> {
            get(jdbc, id);
            List<JsonNode> rows = jdbc
                    .sql(
                            "SELECT payload FROM tasks WHERE kind IN ('index_file','rebuild_knowledge_base','cleanup') AND json_extract(payload,'$.knowledgeBaseId')=? AND id>? ORDER BY id LIMIT ?")
                    .params(id, after(cursor), limit + 1)
                    .query(String.class)
                    .list()
                    .stream()
                    .map(json::readTree)
                    .toList();
            return page(rows, limit, "id");
        });
    }

    public Page switches(String kb, int limit, String cursor) {
        return data.read(jdbc -> {
            get(jdbc,kb);
            List<JsonNode> rows = jdbc.sql("SELECT s.*,json_extract(t.payload,'$.targetConfigVersionId') AS previous_config FROM index_switches s LEFT JOIN index_runs r ON r.id=s.previous_index_id LEFT JOIN tasks t ON t.id=r.task_id WHERE s.knowledge_base_id=? AND s.id>? ORDER BY s.id LIMIT ?")
                    .params(kb,after(cursor),limit+1).query((row,n) -> {
                        ObjectNode value = json.createObjectNode().put("id",row.getString("id")).put("knowledgeBaseId",kb).put("fileId",row.getString("file_id"))
                                .put("toContentVersionId",row.getString("content_id")).put("toIndexVersionId",row.getString("index_id"))
                                .put("toConfigVersionId",row.getString("config_id")).put("taskId",row.getString("task_id")).put("switchedAt",row.getString("switched_at"));
                        value.put("fromContentVersionId",row.getString("previous_content_id")).put("fromIndexVersionId",row.getString("previous_index_id")).put("fromConfigVersionId",row.getString("previous_config"));
                        return (JsonNode)value;
                    }).list();
            return page(rows,limit,"id");
        });
    }

    public Page files(String id, int limit, String cursor) {
        return data.read(jdbc -> {
            get(jdbc,id);
            var candidates = new java.util.TreeSet<String>();
            for (String payload : jdbc.sql("SELECT payload FROM knowledge_base_sources WHERE knowledge_base_id=?").param(id).query(String.class).list()) {
                JsonNode source = json.readTree(payload);
                for (var file : files.sourceFiles(jdbc,source.path("type").asText(),source.path("resourceId").asText())) candidates.add(file.id());
            }
            candidates.addAll(jdbc.sql("SELECT file_id FROM knowledge_base_exclusions WHERE knowledge_base_id=?").param(id).query(String.class).list());
            String after = after(cursor);
            List<JsonNode> rows = candidates.stream().filter(file -> file.compareTo(after)>0).limit((long)limit+1)
                    .map(file -> fileState(jdbc,id,file)).toList();
            return page(rows,limit,"fileId");
        });
    }

    public Page fileKnowledgeBases(String fileId, int limit, String cursor) {
        String file = ModelSettingsService.identifier(fileId,"fileId");
        return data.read(jdbc -> {
            var source = files.sourceFiles(jdbc,"file",file).getFirst();
            List<String> ids = jdbc.sql("SELECT b.id FROM knowledge_bases b WHERE b.visible=1 AND b.id>? AND (EXISTS(SELECT 1 FROM knowledge_base_sources s WHERE s.knowledge_base_id=b.id AND ((s.type='file' AND s.resource_id=?) OR (s.type='dataset' AND s.resource_id=?))) OR EXISTS(SELECT 1 FROM knowledge_base_exclusions e WHERE e.knowledge_base_id=b.id AND e.file_id=?)) ORDER BY b.id LIMIT ?")
                    .params(after(cursor),file,source.datasetId(),file,limit+1).query(String.class).list();
            return page(ids.stream().map(id -> fileState(jdbc,id,file)).toList(),limit,"knowledgeBaseId");
        });
    }

    private JsonNode fileState(JdbcClient jdbc, String kb, String file) {
        var sourceFile = files.sourceFiles(jdbc,"file",file).getFirst();
        var matching = json.createArrayNode();
        for (String payload : jdbc.sql("SELECT payload FROM knowledge_base_sources WHERE knowledge_base_id=? AND ((type='file' AND resource_id=?) OR (type='dataset' AND resource_id=?)) ORDER BY id")
                .params(kb,file,sourceFile.datasetId()).query(String.class).list()) matching.add(json.readTree(payload));
        boolean excluded = excluded(jdbc,kb,file), included = !excluded && !matching.isEmpty();
        ObjectNode value = json.createObjectNode().put("knowledgeBaseId",kb).put("fileId",file)
                .put("excluded",excluded).put("included",included).put("latestContentVersionId",sourceFile.contentVersionId())
                .put("updateState","not_included").put("usingPreviousVersion",false);
        value.set("sources",matching);
        for (String field : List.of("effectiveContentVersionId","effectiveIndexVersionId","targetContentVersionId","targetConfigVersionId","failure","effectiveConfigVersionId","task")) value.putNull(field);
        if (!included) return value;
        jdbc.sql("SELECT content_version_id,config_version_id,task_id FROM knowledge_base_files WHERE knowledge_base_id=? AND file_id=?")
                .params(kb,file).query((row,n) -> {
                    value.put("targetContentVersionId",row.getString(1)).put("targetConfigVersionId",row.getString(2));
                    JsonNode task = json.readTree(jdbc.sql("SELECT payload FROM tasks WHERE id=?").param(row.getString(3)).query(String.class).single());
                    value.set("task",task); value.set("failure",task.path("failure"));
                    String state = task.path("state").asText();
                    value.put("updateState",state.equals("succeeded") ? "ready" : state.equals("running") ? "processing" : state.equals("queued") ? "queued" : "failed");
                    return true;
                }).list();
        jdbc.sql("SELECT content_version_id,config_version_id,index_id FROM effective_files WHERE knowledge_base_id=? AND file_id=?")
                .params(kb,file).query((row,n) -> {
                    value.put("effectiveContentVersionId",row.getString(1)).put("effectiveConfigVersionId",row.getString(2)).put("effectiveIndexVersionId",row.getString(3))
                            .put("usingPreviousVersion",!row.getString(1).equals(value.path("targetContentVersionId").asText()) || !row.getString(2).equals(value.path("targetConfigVersionId").asText()));
                    return true;
                }).list();
        return value;
    }

    private static String after(String cursor) {
        return cursor == null ? "" : ModelSettingsService.identifier(cursor, "cursor");
    }

    private static Page page(List<JsonNode> rows, int limit, String idField) {
        return new Page(
                rows.subList(0, Math.min(limit, rows.size())),
                rows.size() > limit ? rows.get(limit - 1).path(idField).asText() : null);
    }

    private static String normalize(String value) {
        String name = Normalizer.normalize(
                value.replaceAll("^\\p{IsWhite_Space}+|\\p{IsWhite_Space}+$", ""), Normalizer.Form.NFC);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 120)
            throw ApiFailure.invalid("/name", "规范化后的名称须为一至一百二十个字符。");
        return name;
    }

    private static void unique(JdbcClient jdbc, String name, String id) {
        if (jdbc.sql("SELECT COUNT(*) FROM knowledge_bases WHERE name_key=? AND id<>?")
                        .params(name.toLowerCase(Locale.ROOT), id)
                        .query(Integer.class)
                        .single()
                > 0) throw new ApiFailure(409, "NAME_EXISTS", "知识库名称已存在。", "/name");
    }

    private static ConnectionTests.Task task(
            String id, String kb, String config, String state, ConnectionTests.Failure failure, String result) {
        String now = Instant.now().toString();
        boolean queued = state.equals("queued"), running = state.equals("running");
        return new ConnectionTests.Task(
                id,
                "knowledge_base_create",
                state,
                queued ? "queued" : running ? "initializing" : "completed",
                now,
                queued ? null : now,
                queued || running ? null : now,
                kb,
                null,
                null,
                config,
                null,
                queued ? 0 : 1,
                0,
                null,
                failure,
                List.of(),
                result);
    }

    private void persist(JdbcClient jdbc, ConnectionTests.Task task) {
        ConnectionTests.Task previous = json.readValue(
                jdbc.sql("SELECT payload FROM tasks WHERE id=?")
                        .param(task.id())
                        .query(String.class)
                        .single(),
                ConnectionTests.Task.class);
        ConnectionTests.Task updated = new ConnectionTests.Task(
                task.id(),
                task.kind(),
                task.state(),
                task.phase(),
                previous.createdAt(),
                previous.startedAt() == null ? task.startedAt() : previous.startedAt(),
                task.finishedAt(),
                task.knowledgeBaseId(),
                task.fileId(),
                task.targetContentVersionId(),
                task.targetConfigVersionId(),
                task.retryOfTaskId(),
                task.attempt(),
                task.progressCompleted(),
                task.progressTotal(),
                task.failure(),
                task.warnings(),
                task.resultResourceId());
        cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, json.valueToTree(updated));
        jdbc.sql("UPDATE tasks SET state=?,payload=? WHERE id=?")
                .params(task.state(), json.writeValueAsString(updated), task.id())
                .update();
    }

    private void warning(JdbcClient jdbc, String taskId, String message) {
        ObjectNode task = (ObjectNode) json.readTree(jdbc.sql("SELECT payload FROM tasks WHERE id=?")
                .param(taskId)
                .query(String.class)
                .single());
        task.set("warnings", json.createArrayNode().add(message));
        jdbc.sql("UPDATE tasks SET payload=? WHERE id=?")
                .params(json.writeValueAsString(task), taskId)
                .update();
    }

    @PreDestroy
    public void close() {
        stopping.set(true);
        executor.shutdownNow();
        try {
            executor.awaitTermination(6, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record Pending(
            ConnectionTests.Task task, ModelSettingsService.VectorConnection vector, JsonNode embedding) {}

    public record Page(List<JsonNode> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
