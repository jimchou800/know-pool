package cn.knowpool.knowledgebases;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.infrastructure.TaskEvents;
import cn.knowpool.system.ModelSettingsService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 只迁移本地受管数据，固定入口记录切换点，切换后不回退或回删目标。 */
@Service
public final class Maintenance {
    private final ApplicationData data;
    private final KnowledgeBases bases;
    private final ModelSettingsService settings;
    private final VectorCollections vectors;
    private final JsonMapper json;
    private final org.springframework.context.ApplicationEventPublisher publisher;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> results = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> snapshots = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "data-migration");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean stopping;
    private static final Set<String> METADATA = Set.of(
            "application.lock",
            "application.sqlite-wal",
            "application.sqlite-shm",
            "active-directory",
            "active-directory.pending",
            "migration-state.json",
            "migration-state.pending");

    public Maintenance(
            ApplicationData data,
            KnowledgeBases bases,
            ModelSettingsService settings,
            VectorCollections vectors,
            JsonMapper json,
            org.springframework.context.ApplicationEventPublisher publisher)
            throws IOException {
        this.data = data;
        this.bases = bases;
        this.settings = settings;
        this.vectors = vectors;
        this.json = json;
        this.publisher = publisher;
        for (String payload : data.read(jdbc -> jdbc.sql("SELECT payload FROM maintenance_tasks")
                .query(String.class)
                .list())) {
            ObjectNode stored = (ObjectNode) json.readTree(payload);
            results.put(stored.path("result").path("task").path("id").asText(), stored);
            publish(stored);
        }
        Path checkpoint = data.anchor().resolve("migration-state.json");
        if (Files.exists(checkpoint)) {
            safePath(checkpoint);
            ObjectNode stored = (ObjectNode) json.readTree(Files.readString(checkpoint));
            ObjectNode result = (ObjectNode) stored.path("result"), task = (ObjectNode) result.path("task");
            boolean switched = !result.path("operation").asText().equals("backup")
                    && data.directory().equals(Path.of(result.path("targetPath").asText()));
            if (switched)
                result.put("configurationSwitched", true)
                        .put("activeDataDirectory", data.directory().toString());
            if (Set.of("queued", "running").contains(task.path("state").asText())) {
                task.put("state", "interrupted")
                        .put("phase", "completed")
                        .put("finishedAt", Instant.now().toString());
                task.set("failure", failure("TASK_INTERRUPTED", "迁移因应用重启中断，请核查原任务后显式恢复。", !switched));
                result.put("recoverable", !switched)
                        .put(
                                "oldDirectoryResidue",
                                switched && result.path("operation").asText().equals("migration"));
                data.migrationFailed(!switched);
                if (switched) result.put("indexStatus", "unavailable");
                results.put(task.path("id").asText(), stored);
                save(stored, true);
                journal(stored);
            } else results.put(task.path("id").asText(), stored);
            publish(stored);
        }
    }

    public synchronized JsonNode preflight(JsonNode input, String session) {
        strict(input, Set.of("sourcePath", "targetPath"));
        String source = text(input, "sourcePath"), target = text(input, "targetPath");
        for (String field : List.of("sourcePath", "targetPath"))
            if (input.path(field)
                            .asText()
                            .codePointCount(0, input.path(field).asText().length())
                    < 3) throw ApiFailure.invalid(field, "目录长度至少为三个字符。 ");
        Path from = absolute(source, "sourcePath"), to = absolute(target, "targetPath");
        if (!from.equals(data.directory())) throw conflict("源目录与当前数据目录不一致，请刷新后重试。");
        if (data.gate().paused()) throw conflict("已有数据维护正在进行。");
        List<String> reasons = pathReasons(from, to, true);
        long bytes = 0;
        if (reasons.isEmpty())
            try {
                bytes = manifest(from).stream().mapToLong(Entry::size).reduce(0, Math::addExact);
                if (bytes > 9007199254740991L) reasons.add("数据大小超过可安全表示的范围。");
            } catch (IOException | ArithmeticException failure) {
                reasons.add("源目录无法完整读取，或包含链接及不支持的文件。");
            }
        Instant expires = Instant.now().plusSeconds(300);
        String id = UUID.randomUUID().toString();
        tokens.values().removeIf(token -> token.expires().isBefore(Instant.now()));
        if (tokens.size() >= 128) throw new ApiFailure(429, "QUEUE_FULL", "预检数量已达上限，请稍后重试。", null);
        tokens.put(id, new Token(session, from, to, expires, reasons.isEmpty(), "migration"));
        ObjectNode result = json.createObjectNode()
                .put("token", id)
                .put("expiresAt", expires.toString())
                .put("normalizedPath", to.toString())
                .put("allowed", reasons.isEmpty())
                .put("containsPlaintextCredentials", credentials())
                .put("includesVectorSnapshot", false)
                .put("estimatedBytes", Math.max(0, bytes));
        result.set("reasons", json.valueToTree(reasons));
        return result;
    }

    public synchronized JsonNode submit(JsonNode input, String session, String rawKey) {
        strict(input, Set.of("preflightToken", "confirmed"));
        String tokenId = ModelSettingsService.identifier(text(input, "preflightToken"), "preflightToken");
        if (!input.path("confirmed").isBoolean() || !input.path("confirmed").asBoolean())
            throw ApiFailure.invalid("confirmed", "迁移须明确确认。");
        String key = ModelSettingsService.identifier(rawKey, "Idempotency-Key");
        JsonNode previous = replay(session, "submitMigration", key, json.writeValueAsString(input));
        if (previous != null) return accepted(previous);
        Token token = tokens.get(tokenId);
        if (token == null
                || !token.session().equals(session)
                || token.expires().isBefore(Instant.now())
                || !token.allowed()
                || !token.operation().equals("migration")) throw conflict("预检已过期、未通过或不属于当前会话，请重新预检。");
        List<String> reasons = pathReasons(token.source(), token.target(), true);
        if (!token.source().equals(data.directory()) || !reasons.isEmpty()) throw conflict("目录状态已变化，请重新预检。");
        return enqueue(
                token.source(),
                token.target(),
                null,
                false,
                null,
                session,
                "submitMigration",
                key,
                json.writeValueAsString(input),
                "migration",
                null);
    }

    public synchronized JsonNode backupPreflight(JsonNode input, String session) {
        strict(input, Set.of("targetPath"));
        JsonNode result = preflight(
                json.createObjectNode()
                        .put("sourcePath", data.directory().toString())
                        .put("targetPath", pathText(input, "targetPath")),
                session);
        String id = result.path("token").asText();
        Token old = tokens.get(id);
        tokens.put(id, new Token(old.session(), old.source(), old.target(), old.expires(), old.allowed(), "backup"));
        return result;
    }

    public synchronized JsonNode restorePreflight(JsonNode input, String session) {
        strict(input, Set.of("backupPath"));
        Path backup = absolute(pathText(input, "backupPath"), "backupPath");
        if (data.gate().paused()) throw conflict("已有数据维护正在进行。");
        List<String> reasons = new ArrayList<>();
        long bytes = 0;
        boolean credentials = false;
        try {
            safePath(backup);
            if (backup.getParent() == null
                    || dangerous(backup)
                    || backup.startsWith(data.directory())
                    || data.directory().startsWith(backup)) throw new IOException("备份路径不安全。");
            for (Entry entry : archive(backup)) bytes = Math.addExact(bytes, entry.size());
            credentials = json.readTree(Files.readString(backup.resolve("manifest.json")))
                    .path("credentialsIncluded")
                    .asBoolean();
            if (bytes > 9007199254740991L) throw new IOException("备份大小超过限制。");
        } catch (IOException | RuntimeException failure) {
            reasons.add("备份不存在、不完整、版本不受支持或路径不安全。");
        }
        tokens.values().removeIf(token -> token.expires().isBefore(Instant.now()));
        if (tokens.size() >= 128) throw new ApiFailure(429, "QUEUE_FULL", "预检数量已达上限，请稍后重试。", null);
        Instant expires = Instant.now().plusSeconds(300);
        String id = UUID.randomUUID().toString();
        tokens.put(id, new Token(session, data.directory(), backup, expires, reasons.isEmpty(), "restore"));
        ObjectNode result = json.createObjectNode()
                .put("token", id)
                .put("expiresAt", expires.toString())
                .put("normalizedPath", backup.toString())
                .put("allowed", reasons.isEmpty())
                .put("containsPlaintextCredentials", credentials)
                .put("includesVectorSnapshot", false)
                .put("estimatedBytes", Math.max(0, bytes));
        result.set("reasons", json.valueToTree(reasons));
        return result;
    }

    public synchronized JsonNode submitArchive(String kind, JsonNode input, String session, String rawKey) {
        Set<String> fields = kind.equals("restore")
                ? Set.of(
                        "preflightToken", "confirmed", "acknowledgePlaintextCredentials", "acknowledgeIndexRebuildCost")
                : Set.of("preflightToken", "confirmed");
        strict(input, fields);
        for (String field : fields)
            if (!field.equals("preflightToken")
                    && (!input.path(field).isBoolean() || !input.path(field).asBoolean()))
                throw ApiFailure.invalid(field, "备份恢复须明确确认全部风险。");
        String tokenId = ModelSettingsService.identifier(text(input, "preflightToken"), "preflightToken"),
                key = ModelSettingsService.identifier(rawKey, "Idempotency-Key"),
                operation = kind.equals("backup") ? "submitBackup" : "submitRestore";
        JsonNode previous = replay(session, operation, key, json.writeValueAsString(input));
        if (previous != null) return accepted(previous);
        Token token = tokens.get(tokenId);
        if (token == null
                || !token.session().equals(session)
                || token.expires().isBefore(Instant.now())
                || !token.allowed()
                || !token.operation().equals(kind)
                || !token.source().equals(data.directory())) throw conflict("预检已过期、操作不匹配或目录已变化，请重新预检。");
        Path target = token.target(), backup = null;
        if (kind.equals("backup")) {
            if (!pathReasons(token.source(), target, true).isEmpty()) throw conflict("备份目录状态已变化，请重新预检。");
        } else {
            try {
                archive(target);
            } catch (IOException | RuntimeException failure) {
                throw conflict("备份校验失败，请重新预检。");
            }
            backup = target;
            target = data.directory()
                    .resolveSibling("know-pool-restored-" + UUID.randomUUID())
                    .toAbsolutePath();
            if (!pathReasons(token.source(), target, true).isEmpty()) throw conflict("无法创建安全的恢复目录，请先迁移数据目录。");
        }
        return enqueue(
                token.source(),
                target,
                null,
                false,
                null,
                session,
                operation,
                key,
                json.writeValueAsString(input),
                kind,
                backup);
    }

    private static String pathText(JsonNode input, String field) {
        String value = text(input, field);
        if (value.codePointCount(0, value.length()) < 3) throw ApiFailure.invalid(field, "目录长度至少为三个字符。");
        return value;
    }

    public JsonNode get(String raw) {
        String id = ModelSettingsService.identifier(raw, "taskId");
        JsonNode snapshot = snapshots.get(id);
        if (snapshot == null) throw new ApiFailure(404, "NOT_FOUND", "维护任务不存在。", null);
        ObjectNode result = ((ObjectNode) snapshot).deepCopy();
        result.put("activeDataDirectory", data.directory().toString());
        return result;
    }

    public synchronized JsonNode retry(String raw, JsonNode input, String session, String rawKey) {
        String id = ModelSettingsService.identifier(raw, "taskId");
        strict(input, Set.of("action"));
        String action = text(input, "action");
        if (!Set.of("resume", "cleanup_residue").contains(action)) throw ApiFailure.invalid("action", "恢复操作无效。");
        String key = ModelSettingsService.identifier(rawKey, "Idempotency-Key"),
                operation = "retryMaintenanceTask:" + id;
        JsonNode previous = replay(session, operation, key, json.writeValueAsString(input));
        if (previous != null) return accepted(previous);
        ObjectNode old = results.get(id);
        if (old == null) throw new ApiFailure(404, "NOT_FOUND", "维护任务不存在。", null);
        JsonNode result = old.path("result"), task = result.path("task");
        boolean switched = result.path("configurationSwitched").asBoolean();
        if (!Set.of("failed", "interrupted").contains(task.path("state").asText())) throw conflict("仅失败或中断的维护任务可以恢复。");
        if (action.equals("resume") && (switched || !result.path("recoverable").asBoolean()))
            throw conflict("该任务不能恢复复制，请核查故障。");
        if (action.equals("cleanup_residue")
                && (!result.path("operation").asText().equals("migration")
                        || !switched
                        || !result.path("oldDirectoryResidue").asBoolean())) throw conflict("该任务没有可清理的旧目录残留。");
        Path source = Path.of(old.path("sourcePath").asText()),
                target = Path.of(result.path("targetPath").asText());
        if (!(switched ? target : source).equals(data.directory())) throw conflict("活动目录已变化，不能恢复历史迁移。");
        return enqueue(
                source,
                target,
                id,
                action.equals("cleanup_residue"),
                old,
                session,
                operation,
                key,
                json.writeValueAsString(input),
                result.path("operation").asText(),
                old.has("backupPath") ? Path.of(old.path("backupPath").asText()) : null);
    }

    private JsonNode enqueue(
            Path source,
            Path target,
            String retry,
            boolean cleanup,
            ObjectNode previous,
            String session,
            String operation,
            String key,
            String fingerprint,
            String kind,
            Path backup) {
        if (!data.gate().pause()) throw conflict("已有数据维护正在进行。");
        try {
            String id = UUID.randomUUID().toString();
            ObjectNode task = json.createObjectNode()
                    .put("id", id)
                    .put("kind", kind)
                    .put("state", "queued")
                    .put("phase", "queued")
                    .put("createdAt", Instant.now().toString())
                    .put("attempt", 0)
                    .put("progressCompleted", 0);
            for (String field : List.of(
                    "startedAt",
                    "finishedAt",
                    "knowledgeBaseId",
                    "fileId",
                    "targetContentVersionId",
                    "targetConfigVersionId",
                    "progressTotal",
                    "failure",
                    "resultResourceId")) task.putNull(field);
            if (retry == null) task.putNull("retryOfTaskId");
            else task.put("retryOfTaskId", retry);
            task.set("warnings", json.createArrayNode());
            ObjectNode result = json.createObjectNode()
                    .put("operation", kind)
                    .put("activeDataDirectory", data.directory().toString())
                    .put("targetPath", target.toString())
                    .put("configurationSwitched", cleanup)
                    .put("oldDirectoryResidue", cleanup)
                    .put("recoverable", false)
                    .put(
                            "indexStatus",
                            cleanup
                                    ? previous.path("result")
                                            .path("indexStatus")
                                            .asText()
                                    : "not_checked")
                    .put(
                            "credentialsIncluded",
                            kind.equals("restore")
                                    ? json.readTree(readArchiveDescription(backup))
                                            .path("credentialsIncluded")
                                            .asBoolean()
                                    : credentials())
                    .put("vectorSnapshotIncluded", false)
                    .putNull("failureStage");
            result.set("task", task);
            result.set("warnings", json.createArrayNode().add("维护快照可能包含本地明文凭据；向量服务原生数据不在备份和恢复范围内。"));
            ObjectNode stored =
                    json.createObjectNode().put("sourcePath", source.toString()).put("cleanupOnly", cleanup);
            if (backup != null) stored.put("backupPath", backup.toString());
            stored.set("result", result);
            stored.set(
                    "manifest",
                    previous == null
                            ? json.createArrayNode()
                            : previous.path("manifest").deepCopy());
            if (previous != null && previous.path("targetInitialized").asBoolean())
                stored.put("targetInitialized", true);
            if (previous != null && previous.has("copyTemporary"))
                stored.put("copyTemporary", previous.path("copyTemporary").asText());
            data.write(jdbc -> {
                jdbc.sql(
                                "INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,?,'queued',?,?,?,?,?)")
                        .params(id, kind, json.writeValueAsString(task), session, operation, key, fingerprint)
                        .update();
                jdbc.sql("INSERT INTO maintenance_tasks VALUES(?,?)")
                        .params(id, json.writeValueAsString(stored))
                        .update();
                TaskEvents.record(jdbc, json, task);
                return null;
            });
            results.put(id, stored);
            publish(stored);
            try {
                journal(stored);
                worker.execute(() -> run(stored));
            } catch (IOException | RuntimeException failure) {
                finish(stored, "failed", "configuration_save", "维护检查点保存失败，旧目录仍保留。", true);
                throw failure;
            }
            return accepted(task.deepCopy());
        } catch (IOException | RuntimeException failure) {
            data.gate().resume();
            if (failure instanceof ApiFailure api) throw api;
            throw new ApiFailure(500, "INTERNAL_ERROR", "维护任务无法持久保存。", null);
        }
    }

    private void runBackup(ObjectNode stored) {
        try (var exclusive = data.gate().exclusive()) {
            synchronized (data) {
                ObjectNode result = (ObjectNode) stored.path("result"), task = (ObjectNode) result.path("task");
                Path source = Path.of(stored.path("sourcePath").asText()),
                        target = Path.of(result.path("targetPath").asText());
                String stage = "path_check";
                try {
                    phase(stored, "initializing");
                    save(stored, true);
                    if (!pathReasons(source, target, false).isEmpty()) throw new IOException("备份路径不安全。");
                    removePreviousCopies(target, stored);
                    if (Files.exists(target))
                        try (var children = Files.list(target)) {
                            if (children.findAny().isPresent()) throw new IOException("备份目标不为空。");
                        }
                    stored.put("targetInitialized", true);
                    journal(stored);
                    Files.createDirectories(target.resolve("data"));
                    phase(stored, "copying");
                    save(stored, true);
                    stage = "copy";
                    List<Entry> originals = manifest(source).stream()
                            .filter(entry -> !entry.path().equals("application.sqlite"))
                            .toList();
                    List<Entry> copied = new ArrayList<>();
                    stored.set("manifest", json.valueToTree(copied));
                    task.put("progressTotal", originals.size() + 1);
                    String temporaryName = "migration-copy-" + task.path("id").asText() + ".part";
                    stored.put("copyTemporary", temporaryName);
                    journal(stored);
                    Path temporary = inside(target, temporaryName);
                    data.read(jdbc -> {
                        jdbc.sql("VACUUM INTO '" + temporary.toString().replace("'", "''") + "'")
                                .update();
                        return null;
                    });
                    copied.add(new Entry(
                            "data" + java.io.File.separator + "application.sqlite",
                            Files.size(temporary),
                            hash(temporary)));
                    stored.set("manifest", json.valueToTree(copied));
                    journal(stored);
                    Files.move(temporary, target.resolve("data/application.sqlite"), StandardCopyOption.ATOMIC_MOVE);
                    stored.remove("copyTemporary");
                    task.put("progressCompleted", 1);
                    journal(stored);
                    for (Entry entry : originals) {
                        if (stopping) throw new IOException("备份已停止。");
                        Path destination = inside(target.resolve("data"), entry.path());
                        Files.createDirectories(destination.getParent());
                        safePath(destination.getParent());
                        copied.add(
                                new Entry("data" + java.io.File.separator + entry.path(), entry.size(), entry.hash()));
                        stored.set("manifest", json.valueToTree(copied));
                        stored.put("copyTemporary", temporaryName);
                        journal(stored);
                        Files.copy(inside(source, entry.path()), temporary);
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                        stored.remove("copyTemporary");
                        task.put(
                                "progressCompleted",
                                task.path("progressCompleted").asLong() + 1);
                        journal(stored);
                    }
                    stage = "verification";
                    phase(stored, "verifying");
                    List<Entry> contents = manifest(target.resolve("data"));
                    for (Entry entry : copied)
                        if (!hash(inside(target, entry.path())).equals(entry.hash()))
                            throw new IOException("备份文件摘要校验失败。");
                    ObjectNode archive = json.createObjectNode()
                            .put("formatVersion", 1)
                            .put("schemaVersion", 17)
                            .put(
                                    "credentialsIncluded",
                                    result.path("credentialsIncluded").asBoolean())
                            .put("vectorSnapshotIncluded", false);
                    archive.set("files", json.valueToTree(contents));
                    stored.put("copyTemporary", temporaryName);
                    journal(stored);
                    Files.writeString(
                            temporary,
                            json.writeValueAsString(archive),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.SYNC);
                    copied.add(new Entry("manifest.json", Files.size(temporary), hash(temporary)));
                    stored.set("manifest", json.valueToTree(copied));
                    journal(stored);
                    Files.move(temporary, target.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE);
                    stored.remove("copyTemporary");
                    archive(target);
                    finish(stored, "succeeded", null, null, false);
                } catch (IOException | RuntimeException failure) {
                    finish(stored, stopping ? "interrupted" : "failed", stage, "备份未完成，当前资料保持不变；请核查目标后显式恢复。", true);
                }
            }
        } finally {
            data.gate().resume();
        }
    }

    private static String readArchiveDescription(Path backup) throws IOException {
        return Files.readString(inside(backup, "manifest.json"));
    }

    private List<Entry> archive(Path backup) throws IOException {
        safePath(backup);
        JsonNode description = json.readTree(Files.readString(inside(backup, "manifest.json")));
        if (!description.isObject()
                || description.size() != 5
                || description.path("formatVersion").asInt() != 1
                || description.path("schemaVersion").asInt() != 17
                || !description.path("credentialsIncluded").isBoolean()
                || !description.path("vectorSnapshotIncluded").isBoolean()
                || description.path("vectorSnapshotIncluded").asBoolean()
                || !description.path("files").isArray()) throw new IOException("备份格式或版本不受支持。");
        Set<String> names = new HashSet<>();
        List<Entry> entries = new ArrayList<>();
        Path root = backup.resolve("data");
        for (JsonNode item : description.path("files")) {
            if (!item.isObject()
                    || item.size() != 3
                    || !item.path("path").isTextual()
                    || !item.path("size").isIntegralNumber()
                    || item.path("size").asLong() < 0
                    || !item.path("hash").asText().matches("[0-9a-f]{64}")) throw new IOException("备份清单无效。");
            String name = item.path("path").asText();
            Path file = inside(root, name);
            String normalized = root.relativize(file).toString();
            if (!name.equals(normalized)
                    || !names.add(normalized)
                    || metadata(root, name)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) != item.path("size").asLong()
                    || !hash(file).equals(item.path("hash").asText())) throw new IOException("备份副本缺失或摘要不匹配。");
            entries.add(new Entry(
                    name, item.path("size").asLong(), item.path("hash").asText()));
        }
        if (!names.contains("application.sqlite")) throw new IOException("备份缺少数据库。");
        for (Path file : tree(backup))
            if (!Files.isDirectory(file)
                    && !file.equals(backup.resolve("manifest.json"))
                    && (!file.startsWith(root)
                            || !names.contains(root.relativize(file).toString()))) throw new IOException("备份含未登记文件。");
        DriverManagerDataSource database = new DriverManagerDataSource(
                "jdbc:sqlite:file:" + root.resolve("application.sqlite").toUri().getRawPath() + "?mode=ro&immutable=1");
        database.setDriverClassName("org.sqlite.JDBC");
        try (var connection = database.getConnection();
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("PRAGMA user_version")) {
                if (!result.next() || result.getInt(1) != 17) throw new IOException("备份数据库版本不受支持。");
            }
            try (var result = statement.executeQuery("PRAGMA integrity_check")) {
                if (!result.next() || !result.getString(1).equals("ok")) throw new IOException("备份数据库不完整。");
            }
            try (var result = statement.executeQuery(
                    "SELECT relative_path,size_bytes,content_hash FROM file_versions WHERE available=1")) {
                while (result.next()) {
                    Path file = inside(root, result.getString(1));
                    if (!names.contains(root.relativize(file).toString())
                            || Files.size(file) != result.getLong(2)
                            || !hash(file).equals(result.getString(3))) throw new IOException("备份缺少原始版本副本。");
                }
            }
            statement
                    .executeQuery(
                            "SELECT COUNT(*) FROM tasks JOIN maintenance_tasks ON tasks.id=maintenance_tasks.task_id")
                    .close();
        } catch (java.sql.SQLException failure) {
            throw new IOException("备份数据库无法读取。", failure);
        }
        return entries;
    }

    private void prepareRestoredDatabase(Path target) throws IOException {
        List<String[]> currentTasks = data.read(jdbc -> jdbc.sql(
                        "SELECT id,kind,state,payload,result,session_id,operation,idempotency_key,request_fingerprint FROM tasks WHERE kind IN ('migration','backup','restore')")
                .query((row, n) -> {
                    String[] value = new String[9];
                    for (int i = 0; i < 9; i++) value[i] = row.getString(i + 1);
                    return value;
                })
                .list());
        List<String[]> currentPlans = data.read(jdbc -> jdbc.sql("SELECT task_id,payload FROM maintenance_tasks")
                .query((row, n) -> new String[] {row.getString(1), row.getString(2)})
                .list());
        DriverManagerDataSource database =
                new DriverManagerDataSource("jdbc:sqlite:" + target.resolve("application.sqlite"));
        database.setDriverClassName("org.sqlite.JDBC");
        new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(database))
                .execute(status -> {
                    JdbcClient jdbc = JdbcClient.create(database);
                    for (String payload : jdbc.sql("SELECT payload FROM tasks WHERE state IN ('queued','running')")
                            .query(String.class)
                            .list()) {
                        ObjectNode task = (ObjectNode) json.readTree(payload);
                        task.put("state", "interrupted")
                                .put("phase", "completed")
                                .put("finishedAt", Instant.now().toString())
                                .putNull("resultResourceId");
                        task.set("failure", failure("TASK_INTERRUPTED", "备份中的未完成任务因恢复中断，不自动重新调用外部服务。", false));
                        jdbc.sql("UPDATE tasks SET state='interrupted',payload=?,result=NULL WHERE id=?")
                                .params(
                                        json.writeValueAsString(task),
                                        task.path("id").asText())
                                .update();
                        TaskEvents.record(jdbc, json, task);
                    }
                    for (String payload : jdbc.sql("SELECT payload FROM question_tasks")
                            .query(String.class)
                            .list()) {
                        ObjectNode question = (ObjectNode) json.readTree(payload);
                        String id = question.at("/task/id").asText();
                        jdbc.sql("SELECT payload FROM tasks WHERE id=? AND state='interrupted'")
                                .param(id)
                                .query(String.class)
                                .optional()
                                .ifPresent(value -> {
                                    question.set("task", json.readTree(value));
                                    question.putNull("answer");
                                    jdbc.sql("UPDATE question_tasks SET payload=? WHERE id=?")
                                            .params(json.writeValueAsString(question), id)
                                            .update();
                                });
                    }
                    for (String payload : jdbc.sql("SELECT payload FROM conversations")
                            .query(String.class)
                            .list()) {
                        ObjectNode conversation = (ObjectNode) json.readTree(payload);
                        conversation.putNull("activeTask");
                        String id = conversation.at("/lastTask/id").asText();
                        jdbc.sql("SELECT payload FROM tasks WHERE id=?")
                                .param(id)
                                .query(String.class)
                                .optional()
                                .ifPresent(value -> conversation.set("lastTask", json.readTree(value)));
                        jdbc.sql("UPDATE conversations SET payload=? WHERE id=?")
                                .params(
                                        json.writeValueAsString(conversation),
                                        conversation.path("id").asText())
                                .update();
                    }
                    for (String payload : jdbc.sql("SELECT payload FROM search_instances")
                            .query(String.class)
                            .list()) {
                        ObjectNode instance = (ObjectNode) json.readTree(payload);
                        instance.putNull("currentRequestId")
                                .put(
                                        "resultState",
                                        instance.path("lastSubmittedQuery").isNull()
                                                ? "never_submitted"
                                                : "rerun_required");
                        jdbc.sql("UPDATE search_instances SET payload=? WHERE id=?")
                                .params(
                                        json.writeValueAsString(instance),
                                        instance.path("id").asText())
                                .update();
                    }
                    for (String payload : jdbc.sql("SELECT payload FROM maintenance_tasks")
                            .query(String.class)
                            .list()) {
                        ObjectNode plan = (ObjectNode) json.readTree(payload);
                        ObjectNode result = (ObjectNode) plan.path("result");
                        String id = result.path("task").path("id").asText();
                        jdbc.sql("SELECT payload FROM tasks WHERE id=? AND state='interrupted'")
                                .param(id)
                                .query(String.class)
                                .optional()
                                .ifPresent(value -> {
                                    result.set("task", json.readTree(value));
                                    result.put("recoverable", false);
                                    jdbc.sql("UPDATE maintenance_tasks SET payload=? WHERE task_id=?")
                                            .params(json.writeValueAsString(plan), id)
                                            .update();
                                });
                    }
                    for (String[] row : currentTasks) {
                        jdbc.sql(
                                        "INSERT OR REPLACE INTO tasks(id,kind,state,payload,result,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,?,?,?,?,?,?,?,?)")
                                .params((Object[]) row)
                                .update();
                        TaskEvents.record(jdbc, json, json.readTree(row[3]));
                    }
                    for (String[] row : currentPlans)
                        jdbc.sql("INSERT OR REPLACE INTO maintenance_tasks VALUES(?,?)")
                                .params(row[0], row[1])
                                .update();
                    return null;
                });
        try (var connection = database.getConnection();
                var statement = connection.createStatement()) {
            statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").close();
        } catch (java.sql.SQLException failure) {
            throw new IOException("恢复数据库检查点失败。", failure);
        }
    }

    private void run(ObjectNode stored) {
        if (stored.path("result").path("operation").asText().equals("backup")) {
            runBackup(stored);
            return;
        }
        try (var exclusive = data.gate().exclusive()) {
            ObjectNode result = (ObjectNode) stored.path("result"), task = (ObjectNode) result.path("task");
            Path source = Path.of(stored.path("sourcePath").asText()),
                    target = Path.of(result.path("targetPath").asText());
            boolean restore = result.path("operation").asText().equals("restore");
            String stage = "path_check";
            try {
                phase(stored, "initializing");
                save(stored, true);
                if (!stored.path("cleanupOnly").asBoolean()) {
                    synchronized (data) {
                        var reasons = pathReasons(source, target, false);
                        if (!reasons.isEmpty()) throw new IOException("路径安全检查未通过。");
                        removePreviousCopies(target, stored);
                        if (Files.exists(target))
                            try (var items = Files.list(target)) {
                                if (items.findAny().isPresent()) throw new IOException("目标目录含有非本次迁移登记的内容。");
                            }
                        stored.put("targetInitialized", true);
                        journal(stored);
                        Files.createDirectories(target);
                        safePath(target);
                        var targetChannel = java.nio.channels.FileChannel.open(
                                target.resolve("application.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                        java.nio.channels.FileLock targetLock;
                        try {
                            targetLock = targetChannel.tryLock();
                            if (targetLock == null) throw new IOException("目标目录已被占用。");
                        } catch (IOException | RuntimeException failure) {
                            targetChannel.close();
                            throw failure;
                        }
                        try (targetChannel;
                                targetLock) {
                            phase(stored, "copying");
                            save(stored, true);
                            data.checkpoint();
                            Path copySource = restore
                                    ? Path.of(stored.path("backupPath").asText())
                                            .resolve("data")
                                    : source;
                            List<Entry> entries = restore
                                    ? archive(Path.of(stored.path("backupPath").asText()))
                                    : manifest(source);
                            stored.set("manifest", json.valueToTree(entries));
                            task.put("progressTotal", entries.size());
                            journal(stored);
                            stage = "copy";
                            for (Entry entry : entries) {
                                if (stopping) throw new IOException("维护已停止。");
                                Path from = inside(copySource, entry.path()), to = inside(target, entry.path());
                                Files.createDirectories(to.getParent());
                                safePath(to.getParent());
                                if (Files.exists(to)) throw new IOException("目标文件已存在。");
                                String temporaryName =
                                        "migration-copy-" + task.path("id").asText() + ".part";
                                stored.put("copyTemporary", temporaryName);
                                journal(stored);
                                Path temporary = inside(target, temporaryName);
                                if (Files.exists(temporary)) throw new IOException("复制临时文件已存在。");
                                Files.copy(from, temporary);
                                Files.move(temporary, to, StandardCopyOption.ATOMIC_MOVE);
                                stored.remove("copyTemporary");
                                task.put(
                                        "progressCompleted",
                                        task.path("progressCompleted").asLong() + 1);
                                journal(stored);
                            }
                            stage = "verification";
                            phase(stored, "verifying");
                            for (Entry entry : entries)
                                if (!hash(inside(target, entry.path())).equals(entry.hash()))
                                    throw new IOException("目标文件清单校验失败。");
                            verifyDatabase(target);
                            if (restore) {
                                prepareRestoredDatabase(target);
                                stored.set("manifest", json.valueToTree(manifest(target)));
                                journal(stored);
                            }
                        }
                        stage = "configuration_save";
                        phase(stored, "saving_config");
                        if (restore) data.gate().invalidateQueuedActivities();
                        data.switchDirectory(target);
                        if (restore) {
                            publisher.publishEvent(new ApplicationData.Restored());
                            for (String payload : data.read(jdbc -> jdbc.sql("SELECT payload FROM maintenance_tasks")
                                    .query(String.class)
                                    .list())) {
                                ObjectNode plan = (ObjectNode) json.readTree(payload);
                                String id = plan.path("result")
                                        .path("task")
                                        .path("id")
                                        .asText();
                                if (results.putIfAbsent(id, plan) == null) publish(plan);
                            }
                        }
                        result.put("configurationSwitched", true)
                                .put("activeDataDirectory", target.toString())
                                .put("oldDirectoryResidue", !restore);
                        journal(stored);
                        save(stored, true);
                    }
                }
                stage = "index_check";
                if (!stored.path("cleanupOnly").asBoolean()) {
                    String index;
                    try {
                        index = checkIndexes();
                    } catch (IOException | RuntimeException failure) {
                        if (!restore) throw failure;
                        index = "unavailable";
                    }
                    result.put("indexStatus", index);
                    if (restore && !index.equals("valid")) {
                        bases.restoreIndexFailures();
                        ((tools.jackson.databind.node.ArrayNode) result.path("warnings"))
                                .add("索引尚未恢复，已保留需重建失败项；显式重试将调用嵌入服务，可能产生费用和恢复耗时。");
                    }
                    if (!restore && !index.equals("valid")) throw new IOException("向量连接或索引引用核查未通过。");
                }
                if (restore) {
                    ((tools.jackson.databind.node.ArrayNode) result.path("warnings"))
                            .add("恢复前目录保留供人工核查：" + source + "；原备份保持不变，不自动清理。");
                } else {
                    stage = "old_directory_cleanup";
                    phase(stored, "cleaning");
                    cleanupSource(source, stored.path("manifest"));
                    result.put("oldDirectoryResidue", false);
                }
                finish(stored, "succeeded", null, null, false);
            } catch (IOException | RuntimeException failure) {
                boolean switched = result.path("configurationSwitched").asBoolean()
                        || data.directory().equals(target);
                result.put("configurationSwitched", switched)
                        .put("activeDataDirectory", data.directory().toString());
                if (stage.equals("index_check")
                        && result.path("indexStatus").asText().equals("not_checked"))
                    result.put("indexStatus", "unavailable");
                if (switched) result.put("oldDirectoryResidue", !restore);
                finish(
                        stored,
                        stopping ? "interrupted" : "failed",
                        stage,
                        stage.equals("old_directory_cleanup")
                                ? "旧目录残留清理失败，新目录已正常启用；可以显式重试清理。"
                                : stage.equals("index_check")
                                        ? "本地目录已切换，知识服务核查未恢复；请核查向量服务及索引引用。"
                                        : "迁移未完成，旧目录仍保留；请核查目录后显式恢复。",
                        !switched);
            }
        } finally {
            data.gate().resume();
        }
    }

    private void phase(ObjectNode stored, String phase) throws IOException {
        ObjectNode task = (ObjectNode) stored.path("result").path("task");
        task.put("state", "running").put("phase", phase).put("attempt", 1);
        if (task.path("startedAt").isNull()) task.put("startedAt", Instant.now().toString());
        journal(stored);
    }

    private void finish(ObjectNode stored, String state, String stage, String message, boolean recoverable) {
        ObjectNode result = (ObjectNode) stored.path("result"), task = (ObjectNode) result.path("task");
        task.put("state", state)
                .put("phase", "completed")
                .put("finishedAt", Instant.now().toString());
        result.put("recoverable", recoverable);
        data.migrationFailed(recoverable);
        if (stage == null) {
            result.putNull("failureStage");
            task.putNull("failure");
        } else {
            result.put("failureStage", stage);
            task.set(
                    "failure",
                    failure(
                            state.equals("interrupted")
                                    ? "TASK_INTERRUPTED"
                                    : switch (stage) {
                                        case "path_check" -> "PATH_INVALID";
                                        case "copy" -> "COPY_FAILED";
                                        case "verification" -> "VERIFICATION_FAILED";
                                        case "configuration_save" -> "CONFIGURATION_SAVE_FAILED";
                                        case "old_directory_cleanup" -> "OLD_DIRECTORY_CLEANUP_FAILED";
                                        case "index_check" ->
                                            result.path("indexStatus").asText().equals("rebuild_required")
                                                    ? "INDEX_REBUILD_REQUIRED"
                                                    : "VECTOR_UNAVAILABLE";
                                        default -> "INTERNAL_ERROR";
                                    },
                            message,
                            recoverable));
        }
        try {
            save(stored, true);
            journal(stored);
        } catch (IOException | RuntimeException ignored) {
            ((tools.jackson.databind.node.ArrayNode) result.path("warnings")).add("维护结果持久保存失败，请保留目录并在重启后核查检查点。");
            publish(stored);
        }
    }

    private ObjectNode failure(String code, String message, boolean retryable) {
        return json.createObjectNode().put("code", code).put("message", message).put("retryable", retryable);
    }

    private void save(ObjectNode stored, boolean database) throws IOException {
        if (!database) {
            journal(stored);
            return;
        }
        JsonNode task = stored.path("result").path("task");
        String id = task.path("id").asText();
        data.write(jdbc -> {
            jdbc.sql("UPDATE tasks SET state=?,payload=? WHERE id=?")
                    .params(task.path("state").asText(), json.writeValueAsString(task), id)
                    .update();
            jdbc.sql(
                            "INSERT INTO maintenance_tasks VALUES(?,?) ON CONFLICT(task_id) DO UPDATE SET payload=excluded.payload")
                    .params(id, json.writeValueAsString(stored))
                    .update();
            TaskEvents.record(jdbc, json, task);
            return null;
        });
    }

    private void journal(ObjectNode stored) throws IOException {
        Path pending = data.anchor().resolve("migration-state.pending"),
                checkpoint = data.anchor().resolve("migration-state.json");
        safePath(data.anchor());
        if (Files.isSymbolicLink(pending) || Files.isSymbolicLink(checkpoint)) throw new IOException("运行检查点不能是链接。");
        Files.writeString(
                pending,
                json.writeValueAsString(stored),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.SYNC);
        Files.move(pending, checkpoint, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        publish(stored);
    }

    private void publish(ObjectNode stored) {
        snapshots.put(
                stored.path("result").path("task").path("id").asText(),
                stored.path("result").deepCopy());
    }

    private JsonNode replay(String session, String operation, String key, String fingerprint) {
        return data.read(jdbc -> jdbc.sql(
                        "SELECT id,request_fingerprint FROM tasks WHERE session_id=? AND operation=? AND idempotency_key=?")
                .params(session, operation, key)
                .query((row, n) -> new String[] {row.getString(1), row.getString(2)})
                .optional()
                .map(row -> {
                    if (!row[1].equals(fingerprint)) throw conflict("同一幂等键不能用于不同维护请求。");
                    return get(row[0]).path("task");
                })
                .orElse(null));
    }

    private JsonNode accepted(JsonNode task) {
        return json.createObjectNode().set("task", task);
    }

    private boolean credentials() {
        return data.read(jdbc -> jdbc.sql(
                                "SELECT EXISTS(SELECT 1 FROM model_configs WHERE credential IS NOT NULL AND credential<>'' UNION ALL SELECT 1 FROM vector_config WHERE credential IS NOT NULL AND credential<>'')")
                        .query(Integer.class)
                        .single())
                != 0;
    }

    private List<String> pathReasons(Path source, Path target, boolean empty) {
        List<String> reasons = new ArrayList<>();
        try {
            safePath(source);
            safePath(target);
            if (source.equals(target) || source.startsWith(target) || target.startsWith(source))
                reasons.add("源目录与目标目录不能相同或相互包含。");
            if (source.getParent() == null || target.getParent() == null) reasons.add("不能将磁盘根目录用作迁移目录。");
            if (dangerous(source) || dangerous(target)) reasons.add("不能使用源码根目录或源码内容目录。");
            if (Files.exists(target) && !Files.isDirectory(target)) reasons.add("目标必须是目录。");
            else if (empty && Files.exists(target))
                try (var items = Files.list(target)) {
                    if (items.findAny().isPresent()) reasons.add("目标目录必须为空或不存在。");
                }
            if (!reasons.isEmpty()) return reasons;
            Path parent = target;
            while (parent != null && !Files.exists(parent)) parent = parent.getParent();
            if (parent == null || !Files.isWritable(parent)) reasons.add("目标目录不可写。");
            long bytes = manifest(source).stream().mapToLong(Entry::size).sum();
            if (parent != null && Files.getFileStore(parent).getUsableSpace() < bytes) reasons.add("目标磁盘剩余空间不足。");
        } catch (IOException | RuntimeException failure) {
            reasons.add("目录不可访问或包含不允许的链接。");
        }
        return reasons;
    }

    private static boolean dangerous(Path path) {
        for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.isDirectory(ancestor.resolve(".git"))
                    || Files.exists(ancestor.resolve("AGENTS.md")) && Files.exists(ancestor.resolve("pom.xml"))) {
                if (path.equals(ancestor)) return true;
                return !ancestor.relativize(path).getName(0).toString().equals(".runtime");
            }
        }
        return false;
    }

    private static Path absolute(String raw, String field) {
        try {
            Path path = Path.of(raw);
            if (!path.isAbsolute() || raw.startsWith("\\\\") || raw.startsWith("//") || raw.indexOf(':', 3) >= 0)
                throw new IllegalArgumentException();
            return path.normalize();
        } catch (RuntimeException failure) {
            throw ApiFailure.invalid(field, "目录须为本机规范绝对路径。");
        }
    }

    private static void safePath(Path path) throws IOException {
        for (Path item = path; item != null; item = item.getParent())
            if (Files.exists(item, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(item)
                            || !item.toRealPath().equals(item.toAbsolutePath().normalize())))
                throw new IOException("目录不能包含链接或重解析路径。");
    }

    private static Path inside(Path root, String relative) throws IOException {
        Path value = root.resolve(relative).normalize();
        if (!value.startsWith(root) || value.equals(root)) throw new IOException("文件清单越过目录边界。");
        safePath(value);
        return value;
    }

    private List<Entry> manifest(Path root) throws IOException {
        List<Entry> entries = new ArrayList<>();
        {
            for (Path file : tree(root)) {
                safePath(file);
                if (Files.isDirectory(file)) continue;
                String relative = root.relativize(file).toString();
                if (metadata(root, relative)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("数据目录含有非普通文件。");
                entries.add(new Entry(relative, Files.size(file), hash(file)));
            }
        }
        entries.sort(Comparator.comparing(Entry::path));
        return entries;
    }

    private static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("摘要算法不可用。", impossible);
        }
    }

    private boolean metadata(Path root, String relative) {
        return Set.of("application.lock", "application.sqlite-wal", "application.sqlite-shm")
                        .contains(relative)
                || root.equals(data.anchor()) && METADATA.contains(relative);
    }

    private void removePreviousCopies(Path target, JsonNode stored) throws IOException {
        JsonNode manifest = stored.path("manifest");
        if (!Files.exists(target) || !stored.path("targetInitialized").asBoolean()) return;
        safePath(target);
        Path lockFile = target.resolve("application.lock");
        safePath(lockFile);
        try (var channel =
                java.nio.channels.FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            var lease = channel.tryLock();
            if (lease == null) throw new IOException("目标目录已被占用。");
            try (lease) {
                if (stored.has("copyTemporary")) {
                    String name = stored.path("copyTemporary").asText();
                    if (!name.matches("migration-copy-[0-9a-f-]{36}\\.part")) throw new IOException("复制临时清单无效。");
                    Files.deleteIfExists(inside(target, name));
                    ((ObjectNode) stored).remove("copyTemporary");
                }
                for (JsonNode entry : manifest) {
                    Path file = inside(target, entry.path("path").asText());
                    if (Files.exists(file)) {
                        if (!hash(file).equals(entry.path("hash").asText())) throw new IOException("目标登记副本已变化，不能自动覆盖。");
                        Files.delete(file);
                    }
                }
                for (String sidecar : List.of("application.sqlite-wal", "application.sqlite-shm"))
                    Files.deleteIfExists(inside(target, sidecar));
            }
        }
        Files.deleteIfExists(lockFile);
        removeEmptyDirectories(target);
    }

    private void cleanupSource(Path source, JsonNode manifest) throws IOException {
        if (source.equals(data.directory()) || dangerous(source) || source.getParent() == null)
            throw new IOException("拒绝清理活动或危险目录。");
        safePath(source);
        try (var channel = java.nio.channels.FileChannel.open(
                source.resolve("application.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            var lease = channel.tryLock();
            if (lease == null) throw new IOException("旧目录已被其他进程占用。 ");
            try (lease) {
                for (JsonNode entry : manifest) {
                    Path file = inside(source, entry.path("path").asText());
                    if (!Files.exists(file)) continue;
                    if (!hash(file).equals(entry.path("hash").asText())) throw new IOException("旧目录登记文件已变化，保留该文件等待核查。");
                    Files.delete(file);
                }
                for (String sidecar : List.of("application.sqlite-wal", "application.sqlite-shm")) {
                    Path file = source.resolve(sidecar);
                    safePath(file);
                    Files.deleteIfExists(file);
                }
                removeEmptyDirectories(source);
                {
                    for (Path file : tree(source))
                        if (!Files.isDirectory(file)
                                && !metadata(source, source.relativize(file).toString()))
                            throw new IOException("旧目录出现未登记文件，保留残留等待核查。");
                }
            }
        }
    }

    private static List<Path> tree(Path root) throws IOException {
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                safePath(directory);
                paths.add(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes)
                    throws IOException {
                safePath(file);
                paths.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return paths;
    }

    private static void removeEmptyDirectories(Path root) throws IOException {
        if (!Files.exists(root)) return;
        {
            for (Path path :
                    tree(root).stream().sorted(Comparator.reverseOrder()).toList())
                if (!path.equals(root) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    boolean empty;
                    try (var children = Files.list(path)) {
                        empty = children.findAny().isEmpty();
                    }
                    if (empty) Files.delete(path);
                }
        }
    }

    private static void verifyDatabase(Path target) throws IOException {
        DriverManagerDataSource source =
                new DriverManagerDataSource("jdbc:sqlite:" + target.resolve("application.sqlite"));
        source.setDriverClassName("org.sqlite.JDBC");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("PRAGMA integrity_check")) {
                if (!result.next() || !result.getString(1).equals("ok")) throw new IOException("目标数据库完整性校验失败。");
            }
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate(
                        "INSERT INTO application_metadata(name,value) VALUES('migration_probe','已验证') ON CONFLICT(name) DO UPDATE SET value=excluded.value");
                statement
                        .executeQuery("SELECT value FROM application_metadata WHERE name='migration_probe'")
                        .close();
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }
            statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").close();
        } catch (java.sql.SQLException failure) {
            throw new IOException("目标数据库读写校验失败。", failure);
        }
    }

    private String checkIndexes() throws IOException {
        List<String[]> refs = data.read(jdbc -> jdbc.sql(
                        "SELECT knowledge_base_id,file_id,content_version_id,config_version_id,index_id FROM effective_files")
                .query((row, n) -> new String[] {
                    row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5)
                })
                .list());
        var connection = settings.vectorConnection();
        if (connection.endpoint() == null) return refs.isEmpty() ? "valid" : "unavailable";
        JsonNode available = vectors.request(connection.endpoint(), connection.secret(), null, "GET", null);
        if (available == null || !available.at("/result/collections").isArray()) return "unavailable";
        Set<String> checked = new HashSet<>();
        for (String[] ref : refs) {
            String collection = bases.collection(ref[0], ref[3]);
            if (checked.add(collection)) {
                JsonNode config = bases.config(ref[0], ref[3]).path("input").path("embedding"),
                        actual = vectors.request(connection.endpoint(), connection.secret(), collection, "GET", null);
                if (actual == null
                        || actual.at("/result/config/params/vectors/size").asInt()
                                != config.path("dimensions").asInt()
                        || !actual.at("/result/config/params/vectors/distance")
                                .asText()
                                .equals(VectorCollections.distance(
                                        config.path("distance").asText()))) return "rebuild_required";
            }
            List<String> ids = data.read(
                    jdbc -> jdbc.sql("SELECT id FROM index_chunks WHERE index_id=? AND written=1 ORDER BY ordinal")
                            .param(ref[4])
                            .query(String.class)
                            .list());
            for (int offset = 0; offset < ids.size(); offset += 64) {
                List<String> batch = ids.subList(offset, Math.min(offset + 64, ids.size()));
                JsonNode actual = vectors.request(
                        connection.endpoint(),
                        connection.secret(),
                        collection + "/points",
                        "POST",
                        json.writeValueAsString(Map.of("ids", batch, "with_payload", true, "with_vector", false)));
                if (actual == null
                        || !actual.path("result").isArray()
                        || actual.path("result").size() != batch.size()) return "rebuild_required";
                Set<String> found = new HashSet<>();
                for (JsonNode point : actual.path("result")) {
                    JsonNode payload = point.path("payload");
                    if (!batch.contains(point.path("id").asText())
                            || !found.add(point.path("id").asText())
                            || !payload.path("knowledge_base_id").asText().equals(ref[0])
                            || !payload.path("index_id").asText().equals(ref[4])
                            || !payload.path("file_id").asText().equals(ref[1])
                            || !payload.path("config_version_id").asText().equals(ref[3])
                            || !payload.path("content_version_id").asText().equals(ref[2])) return "rebuild_required";
                }
            }
        }
        return "valid";
    }

    private static void strict(JsonNode input, Set<String> fields) {
        if (input == null
                || !input.isObject()
                || input.size() != fields.size()
                || input.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey())))
            throw ApiFailure.invalid(null, "请求字段不完整或含有未知字段。");
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input.path(field);
        if (!value.isTextual()
                || value.asText().length() < 1
                || value.asText().codePointCount(0, value.asText().length()) > 32767)
            throw ApiFailure.invalid(field, "字段须为有效字符串。");
        return value.asText();
    }

    private static ApiFailure conflict(String message) {
        return new ApiFailure(409, "CONFLICT", message, null);
    }

    @PreDestroy
    public void close() {
        stopping = true;
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private record Token(
            String session, Path source, Path target, Instant expires, boolean allowed, String operation) {}

    public record Entry(String path, long size, String hash) {}
}
