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
            DocumentSources documentSources) {
        this.data = data;
        this.files = files;
        this.bases = bases;
        this.settings = settings;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.json = json;
        this.documentSources = documentSources;
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
            List<String> ids = data.read(jdbc -> jdbc.sql(
                            "SELECT id FROM tasks WHERE kind='index_file' AND state IN ('queued','running') ORDER BY id LIMIT 1")
                    .query(String.class)
                    .list());
            if (!ids.isEmpty()) run(ids.getFirst());
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

    private boolean eligible(JdbcClient jdbc, JsonNode task) {
        if (!List.of("queued", "running").contains(task.path("state").asText())) return false;
        String kb = task.path("knowledgeBaseId").asText(),
                file = task.path("fileId").asText();
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
                    bases.config(task.path("knowledgeBaseId").asText()).path("input");
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
                DocumentSources.Parsed document = documentSources.parse(source);
                phase(id, "chunking", 0, null);
                List<JsonNode> parsed = splitSources(document, config.path("chunking"), index);
                data.write(jdbc -> {
                    if (!eligible(jdbc, task(jdbc, id))) throw new ApiFailure(409, "TASK_CANCELLED", "任务资格已失效。", null);
                    jdbc.sql("UPDATE index_runs SET parser_version=?,source_map=?,config_snapshot=? WHERE id=?")
                            .params(DocumentSources.RULE_VERSION, json.writeValueAsString(documentSources.provenance(source, document)), json.writeValueAsString(config), index).update();
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
                chunks = chunks(index);
            }
            var embedding = config.path("embedding");
            var snapshot = settings.embeddingSnapshot(
                    embedding.path("modelConfigVersionId").asText());
            var connection = settings.vectorConnection();
            String collection = "kp_" + task.path("knowledgeBaseId").asText().replace("-", "");
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

    private List<JsonNode> splitSources(DocumentSources.Parsed document, JsonNode config, String index) {
        var result = new java.util.ArrayList<JsonNode>();
        int unitIndex = 0;
        var sequences = new java.util.HashMap<String, Integer>();
        for (DocumentSources.Source source : document.sources()) {
            int cursor = 0, continuation = 0;
            int sourceOrdinal = unitIndex++;
            for (JsonNode value : split(source.text(), config, index + ":" + sourceOrdinal)) {
                ObjectNode chunk = (ObjectNode) value;
                String text = chunk.path("text").asText();
                int start = source.text().indexOf(text, cursor);
                if (start < 0) throw new ApiFailure(500, "INTEGRITY_FAILED", "切片无法回溯原文。", null);
                int end = start + text.length();
                ObjectNode location = source.location().deepCopy();
                if (source.pageEnds() != null) {
                    int first = 0, last = 0;
                    while (first < source.pageEnds().length - 1 && source.pageEnds()[first] <= start) first++;
                    while (last < source.pageEnds().length - 1 && source.pageEnds()[last] < end) last++;
                    location.put("pageStart", first + 1).put("pageEnd", last + 1);
                } else {
                    int first = source.lineStart() + (int) source.text().substring(0, start).chars().filter(c -> c == '\n').count();
                    int last = first + (int) text.substring(0, Math.max(0, text.length() - 1)).chars().filter(c -> c == '\n').count();
                    location.put("lineStart", first).put("lineEnd", last);
                    if (!location.path("tableRowStart").isNull()) {
                        int firstRow = Math.max(0, first - source.lineStart() - 1);
                        int lastRow = Math.max(0, last - source.lineStart() - 1);
                        location.put("tableRowStart", firstRow).put("tableRowEnd", lastRow);
                    }
                }
                if (!location.path("structureId").isNull()) {
                    String structure = location.path("structureId").asText();
                    int sequence = sequences.getOrDefault(structure, 0);
                    location.put("continuationIndex", sequence);
                    sequences.put(structure, sequence + 1);
                } else if (location.path("format").asText().equals("text")) location.put("continuationIndex", continuation++);
                chunk.set("sourceRange", json.createObjectNode().put("sourceOrdinal", sourceOrdinal).put("utf16Start", start).put("utf16EndExclusive", end).put("ruleVersion", DocumentSources.RULE_VERSION));
                chunk.set("location", location);
                result.add(chunk);
                cursor = start + (text.isEmpty() ? 0 : Character.charCount(text.codePointAt(0)));
            }
        }
        return result;
    }

    private List<JsonNode> split(String text, JsonNode config, String index) {
        var result = new java.util.ArrayList<JsonNode>();
        List<Integer> sentences = sentenceEnds(text);
        int start = 0, ordinal = 0, overlap = config.path("overlapCodePoints").asInt();
        long lineStart = 1;
        while (start < text.length()) {
            int end = start;
            for (int count = 0; count < 2000 && end < text.length(); count++)
                end += Character.charCount(text.codePointAt(end));
            if (end < text.length() && !config.path("strategy").asText().equals("fixed")) {
                int position = java.util.Collections.binarySearch(sentences, end);
                int sentence = sentences.get(position >= 0 ? position : -position - 2);
                if (sentence > start) end = sentence;
                else {
                    int delimiter = start;
                    for (int cursor = start; cursor < end; ) {
                        int cp = text.codePointAt(cursor);
                        cursor += Character.charCount(cp);
                        if ("，、；,;：:".indexOf(cp) >= 0) delimiter = cursor;
                    }
                    if (delimiter > start) end = delimiter;
                }
            }
            ObjectNode location = json.createObjectNode()
                    .put("format", "text")
                    .put("lineStart", lineStart)
                    .put(
                            "lineEnd",
                            lineStart
                                    + text.substring(start, Math.max(start, end - 1))
                                            .chars()
                                            .filter(c -> c == '\n')
                                            .count());
            for (String field : List.of(
                    "pageStart",
                    "pageEnd",
                    "paragraphId",
                    "structureId",
                    "tableRowStart",
                    "tableRowEnd",
                    "tableColumnStart",
                    "tableColumnEnd",
                    "codeLanguage",
                    "codeBlockId")) location.putNull(field);
            location.put("continuationIndex", ordinal);
            location.set("titlePath", json.createArrayNode());
            ObjectNode chunk = json.createObjectNode()
                    .put(
                            "id",
                            UUID.nameUUIDFromBytes((index + ":" + ordinal++).getBytes(StandardCharsets.UTF_8))
                                    .toString())
                    .put("text", text.substring(start, end));
            chunk.set("location", location);
            result.add(chunk);
            if (end == text.length()) break;
            int next = end;
            int sentenceIndex = java.util.Collections.binarySearch(sentences, end);
            if (overlap > 0 && sentenceIndex >= 0) {
                int nextSentenceEnd = sentences.get(sentenceIndex + 1);
                int available = Math.min(overlap, 2000 - Math.min(2000, text.codePointCount(end, nextSentenceEnd)));
                int earliest = text.offsetByCodePoints(end, -Math.min(available, text.codePointCount(start, end)));
                int position = java.util.Collections.binarySearch(sentences, earliest);
                int boundary = sentences.get(position >= 0 ? position : -position - 1);
                if (boundary < end) next = boundary;
            }
            lineStart +=
                    text.substring(start, next).chars().filter(c -> c == '\n').count();
            start = next;
        }
        return result;
    }

    private static List<Integer> sentenceEnds(String text) {
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
            JsonNode config = bases.config(kb).path("input").path("embedding");
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
            return new SearchSnapshot(
                    config, java.util.Map.copyOf(captured), Instant.now().toString());
        });
        JsonNode config = snapshot.config();
        List<String> indexes = List.copyOf(snapshot.files().keySet());
        if (indexes.isEmpty()) throw new ApiFailure(422, "NO_EFFECTIVE_INDEX", "请先完成资料处理，再检索生效索引。", null);
        var connection = settings.vectorConnection();
        try {
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
                    "kp_" + kb.replace("-", "") + "/points/query",
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

    private record SearchSnapshot(JsonNode config, java.util.Map<String, CapturedFile> files, String capturedAt) {}

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
