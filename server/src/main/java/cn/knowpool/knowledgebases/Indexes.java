package cn.knowpool.knowledgebases;

import cn.knowpool.datasets.FileService;
import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.system.Embeddings;
import cn.knowpool.system.ModelSettingsService;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class Indexes {
    private static final java.util.Set<String> ABBREVIATIONS =
            java.util.Set.of("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "e.g", "i.e", "fig", "no");
    private static final java.util.regex.Pattern ACRONYM = java.util.regex.Pattern.compile("(?:[a-z]\\.)+[a-z]");
    private final ApplicationData data;
    private final FileService files;
    private final KnowledgeBases bases;
    private final ModelSettingsService settings;
    private final Embeddings embeddings;
    private final VectorCollections vectors;
    private final JsonMapper json;
    private final DocumentSources documentSources;
    private final ChunkPlans chunkPlans;
    private final java.util.Map<String,Integer> readers = new java.util.HashMap<>();
    private volatile boolean stopping;
    private final java.util.concurrent.ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "file-index");
                thread.setDaemon(true);
                return thread;
            });

    public Indexes(
            ApplicationData data,
            FileService files,
            KnowledgeBases bases,
            ModelSettingsService settings,
            Embeddings embeddings,
            VectorCollections vectors,
            JsonMapper json,
            DocumentSources documentSources, ChunkPlans chunkPlans) {
        this.data = data;
        this.files = files;
        this.bases = bases;
        this.settings = settings;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.json = json;
        this.documentSources = documentSources;
        this.chunkPlans = chunkPlans;
        data.write(jdbc -> {
            for (String payload : jdbc.sql("SELECT payload FROM tasks WHERE kind='index_file' AND state='running'")
                    .query(String.class)
                    .list()) {
                ObjectNode task = (ObjectNode) json.readTree(payload);
                int missing = jdbc.sql(
                                "SELECT COUNT(*) FROM index_chunks WHERE index_id=(SELECT id FROM index_runs WHERE task_id=?) AND vector IS NULL")
                        .param(task.path("id").asText())
                        .query(Integer.class)
                        .single();
                int count = jdbc.sql(
                                "SELECT COUNT(*) FROM index_chunks WHERE index_id=(SELECT id FROM index_runs WHERE task_id=?)")
                        .param(task.path("id").asText())
                        .query(Integer.class)
                        .single();
                long dispatched = jdbc.sql(
                                "SELECT COALESCE(SUM(embed_attempts),0) FROM index_chunks WHERE index_id=(SELECT id FROM index_runs WHERE task_id=?)")
                        .param(task.path("id").asText())
                        .query(Long.class)
                        .single();
                if (dispatched > 0 && (missing > 0 || count == 0))
                    finish(jdbc, task, "interrupted", "TASK_INTERRUPTED", "索引因重启中断，嵌入可能已产生费用，请手动重试。", null);
            }
            return null;
        });
        worker.scheduleWithFixedDelay(this::next, 100, 100, TimeUnit.MILLISECONDS);
    }

    private void next() {
        if (stopping) return;
        try {
            cleanups();
            rebuilds();
            List<String> ids = data.read(jdbc -> jdbc.sql(
                            "SELECT id FROM tasks WHERE kind='index_file' AND state IN ('queued','running') ORDER BY id LIMIT 1")
                    .query(String.class)
                    .list());
            if (!ids.isEmpty()) {
                boolean waiting = data.read(jdbc -> jdbc.sql("SELECT COUNT(*) FROM tasks t JOIN processing_rebuilds r ON r.knowledge_base_id=json_extract(t.payload,'$.knowledgeBaseId') AND r.config_version_id=json_extract(t.payload,'$.targetConfigVersionId') JOIN tasks p ON p.id=r.task_id WHERE t.id=? AND r.ready=0 AND p.state IN ('queued','running')")
                        .param(ids.getFirst()).query(Integer.class).single() > 0);
                if (!waiting) run(ids.getFirst());
            }
        } catch (RuntimeException failure) {
            // 持久任务保留现场，下一轮仅从安全检查点领取。
        }
    }

    private ObjectNode task(JdbcClient jdbc, String id) {
        return (ObjectNode) json.readTree(jdbc.sql("SELECT payload FROM tasks WHERE id=?")
                .param(id)
                .query(String.class)
                .single());
    }

    private void cleanups() {
        var pending = data.read(jdbc -> jdbc.sql("SELECT c.task_id,c.targets,c.endpoint FROM index_cleanup c JOIN tasks t ON t.id=c.task_id WHERE t.state IN ('queued','running') ORDER BY c.task_id LIMIT 1")
                .query((row,n) -> new String[]{row.getString(1),row.getString(2),row.getString(3)}).list());
        if (pending.isEmpty()) return;
        String[] item = pending.getFirst();
        JsonNode targets = json.readTree(item[1]);
        synchronized (data) {
            for (JsonNode target : targets) if (readers.getOrDefault(target.path("indexId").asText(),0)>0) return;
        }
        String id = item[0];
        try {
            var connection = settings.vectorConnection();
            if (item[2] == null || !item[2].equals(connection.endpoint()))
                throw new ApiFailure(503,"VECTOR_UNAVAILABLE","清理目标连接已改变，保留待清理资源。",null);
            data.write(jdbc -> {
                ObjectNode value = task(jdbc,id);
                value.put("state","running").put("phase","cleaning");
                if (value.path("startedAt").isNull()) value.put("startedAt",Instant.now().toString());
                persist(jdbc,value); return null;
            });
            int completed = 0;
            for (JsonNode target : targets) {
                if (target.path("completed").asBoolean()) { completed++; continue; }
                String collection = target.path("collection").asText(), index = target.path("indexId").asText();
                int previous = target.path("attempts").asInt();
                if (previous >= 3) throw new ApiFailure(503,"VECTOR_UNAVAILABLE","清理尝试已达到上限。",null);
                for (int attempt = previous+1; attempt <= 3; attempt++) {
                    int currentAttempt = attempt;
                    ((ObjectNode) target).put("attempts",currentAttempt);
                    data.write(jdbc -> {
                        jdbc.sql("UPDATE index_cleanup SET targets=? WHERE task_id=?").params(json.writeValueAsString(targets),id).update();
                        ObjectNode value = task(jdbc,id); value.put("attempt",currentAttempt); persist(jdbc,value); return null;
                    });
                    try {
                        if (vectors.request(connection.endpoint(),connection.secret(),collection,"GET",null) != null) {
                            String body = json.writeValueAsString(java.util.Map.of("filter", java.util.Map.of("must",List.of(java.util.Map.of("key","index_id","match",java.util.Map.of("value",index))))));
                            vectors.request(connection.endpoint(),connection.secret(),collection+"/points/delete?wait=true","POST",body);
                        }
                        break;
                    } catch (java.io.IOException failure) {
                        if (attempt == 3 || failure instanceof VectorCollections.Failure rejected && !rejected.retryable()) throw failure;
                        retryDelay(attempt);
                    }
                }
                int progress = ++completed;
                data.write(jdbc -> {
                    jdbc.sql("DELETE FROM index_chunks WHERE index_id=?").param(index).update();
                    ((ObjectNode) target).put("completed",true);
                    jdbc.sql("UPDATE index_cleanup SET targets=? WHERE task_id=?").params(json.writeValueAsString(targets),id).update();
                    ObjectNode value = task(jdbc,id); value.put("progressCompleted",progress); persist(jdbc,value); return null;
                });
            }
            data.write(jdbc -> { finish(jdbc,task(jdbc,id),"succeeded",null,null,null); return null; });
        } catch (java.io.IOException | RuntimeException failure) {
            data.write(jdbc -> { finish(jdbc,task(jdbc,id),"failed","CLEANUP_FAILED","专属索引清理失败，资料仍保持下线；可稍后重试清理。",null); return null; });
        }
    }

    private void rebuilds() {
        var pending = data.read(jdbc -> jdbc.sql("SELECT r.task_id,r.knowledge_base_id,r.config_version_id,r.ready FROM processing_rebuilds r JOIN tasks t ON t.id=r.task_id WHERE t.state IN ('queued','running') ORDER BY r.task_id")
                .query((row, n) -> new String[]{row.getString(1), row.getString(2), row.getString(3), row.getString(4)}).list());
        for (String[] rebuild : pending) {
            String id = rebuild[0], kb = rebuild[1], configId = rebuild[2];
            try {
                if (!bases.get(kb).path("targetConfigVersionId").asText().equals(configId)) {
                    data.write(jdbc -> { finish(jdbc, task(jdbc,id), "cancelled", null, null, null); return null; });
                    continue;
                }
                if (rebuild[3].equals("0")) {
                    data.write(jdbc -> {
                        ObjectNode value = task(jdbc,id);
                        value.put("state", "running").put("phase", "initializing");
                        if (value.path("startedAt").isNull()) value.put("startedAt", Instant.now().toString());
                        persist(jdbc,value); return null;
                    });
                    JsonNode embedding = bases.config(kb, configId).at("/input/embedding");
                    var connection = settings.vectorConnection();
                    String collection = bases.collection(kb, configId);
                    JsonNode actual = null;
                    for (int attempt = 1; attempt <= 3; attempt++) {
                        int currentAttempt = attempt;
                        data.write(jdbc -> { ObjectNode value = task(jdbc,id); value.put("attempt",currentAttempt); persist(jdbc,value); return null; });
                        try {
                            JsonNode existing = vectors.request(connection.endpoint(), connection.secret(), collection,"GET",null);
                            if (existing == null) vectors.request(connection.endpoint(), connection.secret(), collection,"PUT",
                                    json.writeValueAsString(java.util.Map.of("vectors",java.util.Map.of("size",embedding.path("dimensions").asInt(),"distance",VectorCollections.distance(embedding.path("distance").asText())))));
                            actual = vectors.request(connection.endpoint(), connection.secret(), collection,"GET",null);
                            break;
                        } catch (java.io.IOException failure) {
                            if (attempt == 3 || failure instanceof VectorCollections.Failure rejected && !rejected.retryable()) throw failure;
                            retryDelay(attempt);
                        } catch (ApiFailure failure) {
                            if (failure.status() != 503 || attempt == 3) throw failure;
                            retryDelay(attempt);
                        }
                    }
                    if (actual == null || actual.at("/result/config/params/vectors/size").asInt() != embedding.path("dimensions").asInt()
                            || !actual.at("/result/config/params/vectors/distance").asText().equals(VectorCollections.distance(embedding.path("distance").asText())))
                        throw new ApiFailure(500,"INTEGRITY_FAILED","目标向量集合配置校验失败。",null);
                    data.write(jdbc -> { jdbc.sql("UPDATE processing_rebuilds SET ready=1 WHERE task_id=?").param(id).update(); return null; });
                }
                data.write(jdbc -> {
                    ObjectNode parent = task(jdbc,id);
                    if (!List.of("queued","running").contains(parent.path("state").asText())) return null;
                    List<JsonNode> children = jdbc.sql("SELECT t.payload FROM knowledge_base_files f JOIN tasks t ON t.id=f.task_id WHERE f.knowledge_base_id=? AND f.config_version_id=?")
                            .params(kb, configId).query(String.class).list().stream().map(json::readTree).toList();
                    parent.put("progressTotal", children.size()).put("progressCompleted", children.stream().filter(t -> t.path("state").asText().equals("succeeded")).count()).put("phase","initializing");
                    for (JsonNode child : children) {
                        String state = child.path("state").asText();
                        if (List.of("failed","interrupted","cancelled").contains(state)) {
                            finish(jdbc,parent,"failed","INTEGRITY_FAILED","整库重建中有文件未完成，旧生效索引继续服务。",null);
                            return null;
                        }
                        if (!state.equals("succeeded")) { persist(jdbc,parent); return null; }
                    }
                    if (!bases.get(kb).path("targetConfigVersionId").asText().equals(configId)) {
                        finish(jdbc,parent,"cancelled",null,null,null); return null;
                    }
                    var staged = jdbc.sql("SELECT s.task_id,s.file_id,s.content_version_id,s.index_id FROM staged_indexes s JOIN knowledge_base_files f ON f.task_id=s.task_id AND f.knowledge_base_id=s.knowledge_base_id AND f.file_id=s.file_id AND f.content_version_id=s.content_version_id AND f.config_version_id=s.config_version_id WHERE s.knowledge_base_id=? AND s.config_version_id=?")
                            .params(kb,configId).query((row,n)->new String[]{row.getString(1),row.getString(2),row.getString(3),row.getString(4)}).list();
                    if (staged.size() != children.size()) throw new ApiFailure(500,"INTEGRITY_FAILED","整库待切换结果不完整。",null);
                    for (String[] item : staged) {
                        var old = jdbc.sql("SELECT content_version_id,index_id FROM effective_files WHERE knowledge_base_id=? AND file_id=?")
                                .params(kb,item[1]).query((row,n)->new String[]{row.getString(1),row.getString(2)}).list();
                        jdbc.sql("INSERT OR IGNORE INTO index_switches VALUES(?,?,?,?,?,?,?,?,?,?)").params(UUID.randomUUID().toString(),item[0],kb,item[1],old.isEmpty()?null:old.getFirst()[0],item[2],old.isEmpty()?null:old.getFirst()[1],item[3],configId,Instant.now().toString()).update();
                    }
                    jdbc.sql("DELETE FROM effective_files WHERE knowledge_base_id=?").param(kb).update();
                    for (String[] item : staged) jdbc.sql("INSERT INTO effective_files VALUES(?,?,?,?,?)").params(kb,item[1],item[2],configId,item[3]).update();
                    ObjectNode base = (ObjectNode) bases.get(kb);
                    base.put("effectiveConfigVersionId",configId).put("effectiveIndexVersionId",id).put("searchableFileCount",staged.size());
                    jdbc.sql("UPDATE knowledge_bases SET payload=? WHERE id=?").params(json.writeValueAsString(base),kb).update();
                    finish(jdbc,parent,"succeeded",null,null,kb);
                    return null;
                });
            } catch (java.io.IOException | RuntimeException failure) {
                data.write(jdbc -> {
                    ObjectNode parent = task(jdbc,id);
                    if (List.of("queued","running").contains(parent.path("state").asText()))
                        finish(jdbc,parent,"failed",failure instanceof ApiFailure api ? api.code() : "VECTOR_UNAVAILABLE","整库重建失败，旧生效索引继续服务。",null);
                    return null;
                });
            }
        }
    }

    private boolean eligible(JdbcClient jdbc, JsonNode task) {
        if (!List.of("queued", "running").contains(task.path("state").asText())) return false;
        String kb = task.path("knowledgeBaseId").asText(),
                file = task.path("fileId").asText();
        if (jdbc.sql("SELECT COUNT(*) FROM knowledge_base_exclusions WHERE knowledge_base_id=? AND file_id=?")
                .params(kb,file).query(Integer.class).single() > 0) return false;
        List<String> configs = jdbc.sql(
                        "SELECT config_version_id FROM knowledge_base_files WHERE knowledge_base_id=? AND file_id=? AND task_id=? AND content_version_id=?")
                .params(
                        kb,
                        file,
                        task.path("id").asText(),
                        task.path("targetContentVersionId").asText())
                .query(String.class)
                .list();
        if (configs.isEmpty()
                || !configs.getFirst().equals(task.path("targetConfigVersionId").asText())) return false;
        if (jdbc.sql("SELECT COUNT(*) FROM processing_rebuilds r JOIN tasks p ON p.id=r.task_id WHERE r.knowledge_base_id=? AND r.config_version_id=? AND p.state IN ('failed','cancelled','interrupted')")
                .params(kb, task.path("targetConfigVersionId").asText()).query(Integer.class).single() > 0) return false;
        List<FileService.SourceFile> source;
        try {
            source = files.sourceFiles(jdbc, "file", file);
        } catch (ApiFailure missing) {
            return false;
        }
        if (!source.getFirst()
                .contentVersionId()
                .equals(task.path("targetContentVersionId").asText())) return false;
        return jdbc.sql(
                                        "SELECT COUNT(*) FROM knowledge_bases WHERE id=? AND visible=1 AND json_extract(payload,'$.targetConfigVersionId')=?")
                                .params(kb, task.path("targetConfigVersionId").asText())
                                .query(Integer.class)
                                .single()
                        > 0
                && jdbc.sql(
                                        "SELECT COUNT(*) FROM knowledge_base_sources WHERE knowledge_base_id=? AND ((type='file' AND resource_id=?) OR (type='dataset' AND resource_id=?))")
                                .params(kb, file, source.getFirst().datasetId())
                                .query(Integer.class)
                                .single()
                        > 0;
    }

    private ObjectNode phase(String id, String phase, long completed, Long total) {
        return data.write(jdbc -> {
            ObjectNode task = task(jdbc, id);
            if (!eligible(jdbc, task)) {
                if (List.of("queued", "running").contains(task.path("state").asText()))
                    finish(jdbc, task, "cancelled", null, null, null);
                throw new ApiFailure(409, "TASK_CANCELLED", "任务资格已失效。", null);
            }
            task.put("state", "running").put("phase", phase).put("progressCompleted", completed);
            if (task.path("startedAt").isNull())
                task.put("startedAt", Instant.now().toString());
            if (total == null) task.putNull("progressTotal");
            else task.put("progressTotal", total);
            persist(jdbc, task);
            return task;
        });
    }

    private void persist(JdbcClient jdbc, ObjectNode task) {
        cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, task);
        jdbc.sql("UPDATE tasks SET state=?,payload=? WHERE id=?")
                .params(
                        task.path("state").asText(),
                        json.writeValueAsString(task),
                        task.path("id").asText())
                .update();
        bases.refreshProcessing(jdbc, task.path("knowledgeBaseId").asText());
    }

    private void finish(JdbcClient jdbc, ObjectNode task, String state, String code, String message, String result) {
        task.put("state", state)
                .put("phase", "completed")
                .put("finishedAt", Instant.now().toString());
        if (code == null) task.putNull("failure");
        else
            task.set(
                    "failure",
                    json.createObjectNode()
                            .put("code", code)
                            .put("message", message)
                            .put("retryable", false));
        if (result == null) task.putNull("resultResourceId");
        else task.put("resultResourceId", result);
        persist(jdbc, task);
    }

    private void run(String id) {
        try {
            ObjectNode task = phase(id, "parsing", 0, null);
            JsonNode config =
                    bases.config(task.path("knowledgeBaseId").asText(), task.path("targetConfigVersionId").asText()).path("input");
            String index = data.write(jdbc -> {
                jdbc.sql("INSERT OR IGNORE INTO index_runs(task_id,id,phase,parser_version,config_snapshot) VALUES(?,?,'parsing',?,?)")
                        .params(id, UUID.randomUUID().toString(), DocumentSources.RULE_VERSION, json.writeValueAsString(config))
                        .update();
                return jdbc.sql("SELECT id FROM index_runs WHERE task_id=?")
                        .param(id)
                        .query(String.class)
                        .single();
            });
            List<JsonNode> chunks = chunks(index);
            if (chunks.isEmpty()) {
                FileService.VersionContent source = files.versionContent(
                        task.path("fileId").asText(),
                        task.path("targetContentVersionId").asText());
                if (config.at("/chunking/strategy").asText().equals("page")
                        && !source.version().fileType().equals("pdf"))
                    throw new ApiFailure(422, "MODEL_CONFIG_INVALID", "按页切片仅适用于PDF。", null);
                DocumentSources.Parsed document = documentSources.parse(source, config.path("chunking"));
                phase(id, "chunking", 0, null);
                List<JsonNode> parsed = chunkPlans.build(document, config.path("chunking"), index);
                ObjectNode preflight = chunkPlans.report(task, parsed, document.warnings(), completeSources(document, parsed));
                data.write(jdbc -> {
                    if (!eligible(jdbc, task(jdbc, id))) throw new ApiFailure(409, "TASK_CANCELLED", "任务资格已失效。", null);
                    jdbc.sql("UPDATE index_runs SET parser_version=?,source_map=?,config_snapshot=? WHERE id=?")
                            .params(DocumentSources.RULE_VERSION, json.writeValueAsString(documentSources.provenance(source, document)), json.writeValueAsString(config), index).update();
                    jdbc.sql("UPDATE index_runs SET preflight=? WHERE id=?").params(json.writeValueAsString(preflight), index).update();
                    ObjectNode current = task(jdbc, id);
                    current.set("warnings", json.valueToTree(document.warnings()));
                    persist(jdbc, current);
                    int ordinal = 0;
                    for (JsonNode chunk : parsed)
                        jdbc.sql("INSERT INTO index_chunks(id,index_id,ordinal,text,location,source_range) VALUES(?,?,?,?,?,?)")
                                .params(
                                        chunk.path("id").asText(),
                                        index,
                                        ordinal++,
                                        chunk.path("text").asText(),
                                        json.writeValueAsString(chunk.path("location")), json.writeValueAsString(chunk.path("sourceRange")))
                                .update();
                    return null;
                });
                if (parsed.isEmpty()) throw new ApiFailure(422, "PARSING_FAILED", "资料正文为空，预检未生成切片。", null);
                if (!preflight.path("integrityPassed").asBoolean()) throw new ApiFailure(422, "INTEGRITY_FAILED", "切片完整性检查失败，已阻止发布。", null);
                chunks = chunks(index);
            }
            JsonNode storedPreflight = preflight(id);
            if (!storedPreflight.path("integrityPassed").asBoolean() || storedPreflight.path("emptyText").asBoolean())
                throw new ApiFailure(422, "INTEGRITY_FAILED", "持久预检未通过，已阻止恢复任务发布。", null);
            var embedding = config.path("embedding");
            var snapshot = settings.embeddingSnapshot(
                    embedding.path("modelConfigVersionId").asText());
            var connection = settings.vectorConnection();
            String collection = bases.collection(task.path("knowledgeBaseId").asText(), task.path("targetConfigVersionId").asText());
            for (int i = 0; i < chunks.size(); i++) {
                JsonNode chunk = chunks.get(i);
                String chunkId = chunk.path("id").asText();
                if (data.read(jdbc -> jdbc.sql("SELECT written FROM index_chunks WHERE id=?")
                                .param(chunkId)
                                .query(Integer.class)
                                .single())
                        == 1) continue;
                String vector = data.read(jdbc -> jdbc.sql("SELECT vector FROM index_chunks WHERE id=?")
                        .param(chunkId)
                        .query((r, n) -> r.getString(1))
                        .list()
                        .getFirst());
                if (vector == null) {
                    phase(id, "embedding", i, (long) chunks.size());
                    String input = chunk.path("text").asText();
                    for (JsonNode field : config.at("/chunking/embeddedMetadataFields"))
                        input += "\n" + field.asText() + ": "
                                + config.at("/chunking/customMetadata")
                                        .path(field.asText())
                                        .asText();
                    String finalInput = input;
                    Embeddings.Result measured = embed(
                            id,
                            chunkId,
                            snapshot,
                            finalInput,
                            embedding.path("dimensions").asInt());
                    vector = json.writeValueAsString(measured.vector());
                    String saved = vector;
                    data.write(jdbc -> {
                        if (!eligible(jdbc, task(jdbc, id)))
                            throw new ApiFailure(409, "TASK_CANCELLED", "任务资格已失效。", null);
                        jdbc.sql(
                                        "UPDATE index_chunks SET vector=?,input_upper_bound=?,prompt_tokens=?,meter_version=? WHERE id=?")
                                .params(
                                        saved,
                                        measured.inputUpperBound(),
                                        measured.promptTokens(),
                                        measured.meterVersion(),
                                        chunkId)
                                .update();
                        return null;
                    });
                }
                phase(id, "vector_writing", i, (long) chunks.size());
                ObjectNode payload = json.createObjectNode()
                        .put("knowledge_base_id", task.path("knowledgeBaseId").asText())
                        .put("file_id", task.path("fileId").asText())
                        .put(
                                "content_version_id",
                                task.path("targetContentVersionId").asText())
                        .put(
                                "config_version_id",
                                task.path("targetConfigVersionId").asText())
                        .put("index_id", index);
                ObjectNode point = json.createObjectNode().put("id", chunkId);
                point.set("vector", json.readTree(vector));
                point.set("payload", payload);
                boolean writeCompleted =
                        data.read(jdbc -> jdbc.sql("SELECT write_completed FROM index_chunks WHERE id=?")
                                        .param(chunkId)
                                        .query(Integer.class)
                                        .single())
                                == 1;
                if (!writeCompleted) {
                    vectorRequest(
                            id,
                            chunkId,
                            false,
                            connection,
                            collection + "/points?wait=true",
                            "PUT",
                            json.writeValueAsString(java.util.Map.of("points", List.of(point))));
                    data.write(jdbc -> {
                        jdbc.sql("UPDATE index_chunks SET write_completed=1 WHERE id=?")
                                .param(chunkId)
                                .update();
                        return null;
                    });
                }
                phase(id, "validating", i, (long) chunks.size());
                JsonNode actual = vectorRequest(
                        id,
                        chunkId,
                        true,
                        connection,
                        collection + "/points",
                        "POST",
                        json.writeValueAsString(
                                java.util.Map.of("ids", List.of(chunkId), "with_vector", true, "with_payload", true)));
                if (actual.path("result").size() != 1
                        || !actual.at("/result/0/id").asText().equals(chunkId)
                        || !actual.at("/result/0/payload").equals(payload)
                        || !sameVector(actual.at("/result/0/vector"), json.readTree(vector)))
                    throw new ApiFailure(500, "INTEGRITY_FAILED", "向量写入读回核验失败。", null);
                data.write(jdbc -> {
                    jdbc.sql("UPDATE index_chunks SET written=1 WHERE id=?")
                            .param(chunkId)
                            .update();
                    return null;
                });
            }
            phase(id, "switching", chunks.size(), (long) chunks.size());
            publish(id, index);
        } catch (java.io.IOException | RuntimeException failure) {
            if (!stopping)
                data.write(jdbc -> {
                    ObjectNode task = task(jdbc, id);
                    if (List.of("queued", "running").contains(task.path("state").asText())) {
                        if (!eligible(jdbc, task)) finish(jdbc, task, "cancelled", null, null, null);
                        else
                            finish(
                                    jdbc,
                                    task,
                                    "failed",
                                    failure instanceof ApiFailure api
                                            ? api.code()
                                            : failure instanceof java.io.IOException
                                                    ? "PARSING_FAILED"
                                                    : "INTERNAL_ERROR",
                                    failure instanceof ApiFailure api ? api.getMessage() : "索引执行失败，请检查资料与依赖。",
                                    null);
                    }
                    return null;
                });
        }
    }

    private static boolean sameVector(JsonNode actual, JsonNode expected) {
        if (!actual.isArray() || actual.size() != expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            JsonNode value = actual.get(i);
            if (!value.isNumber()
                    || !Float.isFinite(value.floatValue())
                    || Float.compare(value.floatValue() + 0.0f, expected.get(i).floatValue() + 0.0f) != 0) return false;
        }
        return true;
    }

    private JsonNode vectorRequest(
            String taskId,
            String chunkId,
            boolean validation,
            ModelSettingsService.VectorConnection connection,
            String path,
            String method,
            String body) {
        String column = validation ? "verify_attempts" : "write_attempts";
        while (true) {
            int attempt = data.write(jdbc -> {
                ObjectNode task = task(jdbc, taskId);
                if (!eligible(jdbc, task)) throw new ApiFailure(409, "TASK_CANCELLED", "任务资格已失效。", null);
                int previous = jdbc.sql("SELECT " + column + " FROM index_chunks WHERE id=?")
                        .param(chunkId)
                        .query(Integer.class)
                        .single();
                if (previous >= 3) throw new ApiFailure(503, "VECTOR_UNAVAILABLE", "向量操作尝试已达到上限，请手动重试。", null);
                jdbc.sql("UPDATE index_chunks SET " + column + "=" + column + "+1 WHERE id=?")
                        .param(chunkId)
                        .update();
                task.put("attempt", previous + 1);
                persist(jdbc, task);
                return previous + 1;
            });
            try {
                return vectors.request(connection.endpoint(), connection.secret(), path, method, body);
            } catch (java.io.IOException failure) {
                boolean retryable = !(failure instanceof VectorCollections.Failure rejected) || rejected.retryable();
                if (!retryable || attempt == 3)
                    throw new ApiFailure(503, "VECTOR_UNAVAILABLE", "向量服务操作失败，请检查连接、配置及任务。", null);
                retryDelay(attempt);
            }
        }
    }

    private static void retryDelay(int attempt) {
        try {
            TimeUnit.MILLISECONDS.sleep(100L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiFailure(503, "TASK_INTERRUPTED", "索引执行已中断，请查询原任务。", null);
        }
    }

    private Embeddings.Result embed(
            String taskId,
            String chunkId,
            ModelSettingsService.EmbeddingSnapshot snapshot,
            String input,
            int dimensions) {
        while (true) {
            int attempt = data.write(jdbc -> {
                ObjectNode task = task(jdbc, taskId);
                if (!eligible(jdbc, task)) throw new ApiFailure(409, "TASK_CANCELLED", "任务资格已失效。", null);
                int previous = jdbc.sql("SELECT embed_attempts FROM index_chunks WHERE id=?")
                        .param(chunkId)
                        .query(Integer.class)
                        .single();
                if (previous >= 3) throw new ApiFailure(503, "MODEL_UNAVAILABLE", "嵌入尝试已达到上限，请手动重试。", null);
                jdbc.sql("UPDATE index_chunks SET embed_attempts=embed_attempts+1 WHERE id=?")
                        .param(chunkId)
                        .update();
                task.put("attempt", previous + 1);
                persist(jdbc, task);
                return previous + 1;
            });
            try {
                return embeddings.embed(snapshot, input, dimensions);
            } catch (ApiFailure failure) {
                if (failure.status() != 503 || attempt == 3) throw failure;
                retryDelay(attempt);
            }
        }
    }

    private List<JsonNode> chunks(String index) {
        return data.read(jdbc -> jdbc.sql("SELECT id,text,location FROM index_chunks WHERE index_id=? ORDER BY ordinal")
                .param(index)
                .query((row, n) -> {
                    ObjectNode value =
                            json.createObjectNode().put("id", row.getString(1)).put("text", row.getString(2));
                    value.set("location", json.readTree(row.getString(3)));
                    return (JsonNode) value;
                })
                .list());
    }

    public JsonNode preflight(String id) {
        String valid = ModelSettingsService.identifier(id, "taskId");
        return data.read(jdbc -> jdbc.sql("SELECT r.preflight FROM index_runs r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND t.kind='index_file' AND r.preflight IS NOT NULL")
                .param(valid).query(String.class).optional().map(json::readTree)
                .orElseThrow(() -> new ApiFailure(404, "NOT_FOUND", "目标预检尚未生成或任务不存在。", null)));
    }

    private boolean completeSources(DocumentSources.Parsed document, List<JsonNode> chunks) {
        for (int unit = 0; unit < document.sources().size(); unit++) {
            int covered = 0;
            for (JsonNode chunk : chunks) {
                if (chunk.at("/sourceRange/sourceOrdinal").asInt() != unit) continue;
                int start = chunk.at("/sourceRange/utf16Start").asInt(), end = chunk.at("/sourceRange/utf16EndExclusive").asInt();
                String source = document.sources().get(unit).text();
                if (start < 0 || start > covered || end <= start || end > source.length()
                        || !chunk.path("text").asText().equals(chunk.at("/sourceRange/injectedPrefix").asText("") + source.substring(start,end))
                        || chunk.path("text").asText().codePointCount(0, chunk.path("text").asText().length()) > 2000) return false;
                covered = Math.max(covered, end);
            }
            if (covered != document.sources().get(unit).text().length()) return false;
        }
        return true;
    }

    static List<Integer> sentenceEnds(String text) {
        var ends = new java.util.ArrayList<Integer>();
        ends.add(0);
        for (int cursor = 0; cursor < text.length(); ) {
            int cp = text.codePointAt(cursor);
            int next = cursor + Character.charCount(cp);
            if ("。！？!?".indexOf(cp) >= 0 || cp == '.' && sentencePeriod(text, cursor)) {
                while (next < text.length() && "。！？!?\"'”’）)】]》」』".indexOf(text.codePointAt(next)) >= 0)
                    next += Character.charCount(text.codePointAt(next));
                ends.add(next);
            }
            cursor = next;
        }
        if (ends.getLast() != text.length()) ends.add(text.length());
        return ends;
    }

    private static boolean sentencePeriod(String text, int cursor) {
        int previous = cursor == 0 ? -1 : text.codePointBefore(cursor);
        int next = cursor + 1 == text.length() ? -1 : text.codePointAt(cursor + 1);
        if (Character.isDigit(previous) && Character.isDigit(next)) return false;
        if (Character.isLetter(previous) && Character.isLetter(next)) return false;
        int start = cursor;
        while (start > 0) {
            int cp = text.codePointBefore(start);
            if (!Character.isLetter(cp) && cp != '.') break;
            start -= Character.charCount(cp);
        }
        String token = text.substring(start, cursor).toLowerCase(java.util.Locale.ROOT);
        if (ABBREVIATIONS.contains(token) || ACRONYM.matcher(token).matches()) return false;
        int following = cursor + 1;
        while (following < text.length() && Character.isWhitespace(text.codePointAt(following)))
            following += Character.charCount(text.codePointAt(following));
        if (token.length() == 1 && following < text.length() && Character.isUpperCase(text.codePointAt(following)))
            return false;
        if (Character.isDigit(previous)) {
            int line = text.lastIndexOf('\n', cursor) + 1;
            if (text.substring(line, cursor).strip().matches("[0-9]+")) return false;
        }
        return true;
    }

    private void publish(String id, String index) {
        data.write(jdbc -> {
            ObjectNode task = task(jdbc, id);
            if (!eligible(jdbc, task)) {
                finish(jdbc, task, "cancelled", null, null, null);
                return null;
            }
            String kb = task.path("knowledgeBaseId").asText(),
                    file = task.path("fileId").asText(),
                    content = task.path("targetContentVersionId").asText(),
                    config = task.path("targetConfigVersionId").asText();
            if (jdbc.sql("SELECT COUNT(*) FROM processing_rebuilds r JOIN tasks t ON t.id=r.task_id WHERE r.knowledge_base_id=? AND r.config_version_id=? AND t.state IN ('queued','running')")
                    .params(kb, config).query(Integer.class).single() > 0) {
                jdbc.sql("INSERT OR REPLACE INTO staged_indexes VALUES(?,?,?,?,?,?)").params(id, kb, file, content, config, index).update();
                finish(jdbc, task, "succeeded", null, null, index);
                return null;
            }
            List<String[]> previous = jdbc.sql(
                            "SELECT content_version_id,index_id FROM effective_files WHERE knowledge_base_id=? AND file_id=?")
                    .params(kb, file)
                    .query((row, n) -> new String[] {row.getString(1), row.getString(2)})
                    .list();
            jdbc.sql("INSERT INTO index_switches VALUES(?,?,?,?,?,?,?,?,?,?)")
                    .params(
                            UUID.randomUUID().toString(),
                            id,
                            kb,
                            file,
                            previous.isEmpty() ? null : previous.getFirst()[0],
                            content,
                            previous.isEmpty() ? null : previous.getFirst()[1],
                            index,
                            config,
                            Instant.now().toString())
                    .update();
            jdbc.sql(
                            "INSERT INTO effective_files VALUES(?,?,?,?,?) ON CONFLICT(knowledge_base_id,file_id) DO UPDATE SET content_version_id=excluded.content_version_id,config_version_id=excluded.config_version_id,index_id=excluded.index_id")
                    .params(kb, file, content, config, index)
                    .update();
            ObjectNode base = (ObjectNode) bases.get(kb);
            base.put("effectiveConfigVersionId", config)
                    .put("effectiveIndexVersionId", index)
                    .put(
                            "searchableFileCount",
                            jdbc.sql("SELECT COUNT(*) FROM effective_files WHERE knowledge_base_id=?")
                                    .param(kb)
                                    .query(Integer.class)
                                    .single());
            jdbc.sql("UPDATE knowledge_bases SET payload=? WHERE id=?")
                    .params(json.writeValueAsString(base), kb)
                    .update();
            finish(jdbc, task, "succeeded", null, null, index);
            return null;
        });
    }

    public JsonNode retrieve(String kb, String query) {
        SearchSnapshot snapshot = data.write(jdbc -> {
            JsonNode base = bases.get(kb);
            String effective = base.path("effectiveConfigVersionId").asText();
            JsonNode config = (effective.isEmpty() ? bases.config(kb) : bases.config(kb, effective)).path("input").path("embedding");
            var captured = new java.util.LinkedHashMap<String, CapturedFile>();
            jdbc.sql(
                            "SELECT file_id,content_version_id,config_version_id,index_id FROM effective_files WHERE knowledge_base_id=?")
                    .param(kb)
                    .query((row, n) -> {
                        String file = row.getString(1), index = row.getString(4);
                        captured.put(
                                index,
                                new CapturedFile(
                                        file,
                                        row.getString(2),
                                        row.getString(3),
                                        files.get(file).name()));
                        return true;
                    })
                    .list();
            SearchSnapshot value = new SearchSnapshot(
                    config, java.util.Map.copyOf(captured), Instant.now().toString(), bases.collection(kb, effective));
            for (String index : captured.keySet()) readers.merge(index,1,Integer::sum);
            return value;
        });
        JsonNode config = snapshot.config();
        List<String> indexes = List.copyOf(snapshot.files().keySet());
        if (indexes.isEmpty()) throw new ApiFailure(422, "NO_EFFECTIVE_INDEX", "请先完成资料处理，再检索生效索引。", null);
        try {
            var connection = settings.vectorConnection();
            float[] vector = embeddings
                    .embed(
                            settings.embeddingSnapshot(
                                    config.path("modelConfigVersionId").asText()),
                            query,
                            config.path("dimensions").asInt())
                    .vector();
            ObjectNode body = json.createObjectNode();
            body.set("query", json.valueToTree(vector));
            body.put("limit", config.path("candidatesPerQuery").asInt()).put("with_payload", true);
            body.set(
                    "filter",
                    json.valueToTree(java.util.Map.of(
                            "must",
                            List.of(java.util.Map.of("key", "index_id", "match", java.util.Map.of("any", indexes))))));
            JsonNode response = vectors.request(
                    connection.endpoint(),
                    connection.secret(),
                    snapshot.collection() + "/points/query",
                    "POST",
                    json.writeValueAsString(body));
            if (!response.at("/result/points").isArray())
                throw new ApiFailure(500, "INTEGRITY_FAILED", "向量检索响应不完整。", null);
            JsonNode points = response.at("/result/points");
            if (points.size() > config.path("candidatesPerQuery").asInt())
                throw new ApiFailure(500, "INTEGRITY_FAILED", "向量检索候选数量超过请求上限。", null);
            var ids = new java.util.LinkedHashSet<String>();
            for (JsonNode hit : points) {
                if (!hit.path("score").isNumber()
                        || !Double.isFinite(hit.path("score").asDouble()))
                    throw new ApiFailure(500, "INTEGRITY_FAILED", "向量检索分数无效。", null);
                if (hit.path("score").asDouble()
                        >= config.path("similarityThreshold").asDouble())
                    ids.add(hit.path("id").asText());
            }
            JsonNode result = data.write(jdbc -> evidence(jdbc, kb, snapshot, List.copyOf(ids)));
            availability(kb, null);
            return result;
        } catch (java.io.IOException failure) {
            availability(kb, "向量服务检索失败。");
            throw new ApiFailure(503, "VECTOR_UNAVAILABLE", "向量服务检索失败，请检查连接。", null);
        } catch (ApiFailure failure) {
            if (List.of("MODEL_UNAVAILABLE", "VECTOR_UNAVAILABLE").contains(failure.code()))
                availability(kb, failure.getMessage());
            throw failure;
        } finally {
            synchronized (data) {
                for (String index : snapshot.files().keySet()) readers.computeIfPresent(index,(key,count) -> count <= 1 ? null : count-1);
            }
        }
    }

    private JsonNode evidence(JdbcClient jdbc, String kb, SearchSnapshot snapshot, List<String> ids) {
        var result = json.createArrayNode();
        if (ids.isEmpty()) return result;
        var chunks = new java.util.HashMap<String, JsonNode>();
        var fileIds = new java.util.LinkedHashSet<String>();
        jdbc.sql("SELECT id,text,location,index_id FROM index_chunks WHERE id IN (:ids)")
                .param("ids", ids)
                .query((row, n) -> {
                    CapturedFile captured = snapshot.files().get(row.getString(4));
                    if (captured != null) {
                        ObjectNode chunk = json.createObjectNode()
                                .put("id", row.getString(1))
                                .put("text", row.getString(2))
                                .put("index", row.getString(4));
                        chunk.set("location", json.readTree(row.getString(3)));
                        chunks.put(row.getString(1), chunk);
                        fileIds.add(captured.fileId());
                    }
                    return true;
                })
                .list();
        if (fileIds.isEmpty()) return result;
        var current = files.currentFiles(jdbc, List.copyOf(fileIds));
        var resources = new java.util.HashSet<String>(fileIds);
        for (var file : current.values()) resources.add(file.datasetId());
        var fileSources = new java.util.HashSet<String>();
        var datasetSources = new java.util.HashSet<String>();
        jdbc.sql(
                        "SELECT type,resource_id FROM knowledge_base_sources WHERE knowledge_base_id=:kb AND resource_id IN (:resources)")
                .param("kb", kb)
                .param("resources", resources)
                .query((row, n) -> {
                    if (row.getString(1).equals("file")) fileSources.add(row.getString(2));
                    else if (row.getString(1).equals("dataset")) datasetSources.add(row.getString(2));
                    return true;
                })
                .list();
        var states = new java.util.HashMap<String, String>();
        jdbc.sql(
                        "SELECT f.file_id,t.state FROM knowledge_base_files f JOIN tasks t ON t.id=f.task_id WHERE f.knowledge_base_id=:kb AND f.file_id IN (:files)")
                .param("kb", kb)
                .param("files", fileIds)
                .query((row, n) -> {
                    states.put(row.getString(1), row.getString(2));
                    return true;
                })
                .list();
        JsonNode base = bases.get(kb);
        for (String id : ids) {
            JsonNode chunk = chunks.get(id);
            if (chunk == null) continue;
            CapturedFile captured = snapshot.files().get(chunk.path("index").asText());
            var file = current.get(captured.fileId());
            if (file == null
                    || !file.available()
                    || !states.containsKey(captured.fileId())
                    || jdbc.sql("SELECT COUNT(*) FROM knowledge_base_exclusions WHERE knowledge_base_id=? AND file_id=?").params(kb,captured.fileId()).query(Integer.class).single() > 0
                    || !fileSources.contains(captured.fileId()) && !datasetSources.contains(file.datasetId()))
                throw new ApiFailure(409, "SCOPE_UNAVAILABLE", "资料已不在当前知识库的有效范围中，请重新检索。", null);
            String state = states.get(captured.fileId());
            ObjectNode value = json.createObjectNode()
                    .put("id", UUID.randomUUID().toString())
                    .put("chunkId", id)
                    .put("text", chunk.path("text").asText())
                    .put("knowledgeBaseId", kb)
                    .put("fileId", captured.fileId())
                    .put("contentVersionId", captured.contentVersionId())
                    .put("configVersionId", captured.configVersionId())
                    .put("indexVersionId", chunk.path("index").asText())
                    .put("filenameAtCapture", captured.filename())
                    .put("currentFilename", file.filename())
                    .put("originalAvailable", true)
                    .put(
                            "usingPreviousVersion",
                            !file.contentVersionId().equals(captured.contentVersionId())
                                    || !base.path("targetConfigVersionId")
                                            .asText()
                                            .equals(captured.configVersionId()))
                    .put(
                            "updateState",
                            state.equals("succeeded")
                                    ? "ready"
                                    : List.of("running", "queued").contains(state) ? "updating" : "failed")
                    .put("capturedAt", snapshot.capturedAt());
            value.set("location", chunk.path("location"));
            result.add(value);
        }
        return result;
    }

    private record CapturedFile(String fileId, String contentVersionId, String configVersionId, String filename) {}

    private record SearchSnapshot(JsonNode config, java.util.Map<String, CapturedFile> files, String capturedAt, String collection) {}

    private void availability(String kb, String reason) {
        data.write(jdbc -> {
            ObjectNode value = (ObjectNode) bases.get(kb);
            value.put("availability", reason == null ? "available" : "unavailable")
                    .put("lastCheckedAt", Instant.now().toString());
            if (reason == null) value.putNull("availabilityReason");
            else value.put("availabilityReason", reason);
            jdbc.sql("UPDATE knowledge_bases SET payload=? WHERE id=?")
                    .params(json.writeValueAsString(value), kb)
                    .update();
            return null;
        });
    }

    @PreDestroy
    public void close() {
        stopping = true;
        worker.shutdownNow();
        try {
            worker.awaitTermination(6, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
