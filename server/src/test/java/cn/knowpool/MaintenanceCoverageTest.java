package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.knowledgebases.KnowledgeBases;
import cn.knowpool.knowledgebases.Maintenance;
import cn.knowpool.knowledgebases.VectorCollections;
import cn.knowpool.system.ModelSettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class MaintenanceCoverageTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SESSION = "maintenance-coverage";

    @TempDir
    Path temporary;

    @BeforeEach
    void canonicalizeTemporaryDirectory() throws Exception {
        // 持续集成的临时目录可能使用短文件名，夹具须与应用保存的真实路径一致。
        temporary = temporary.toRealPath();
    }

    @Test
    @DisplayName("维护请求拒绝错误字段、危险路径和跨会话凭证且不改变活动目录")
    void rejectsInvalidRequestsWithoutChangingData() throws Exception {
        Path source = temporary.resolve("source");
        try (var app = start(source)) {
            Maintenance maintenance = app.getBean(Maintenance.class);
            ObjectNode valid = paths(source, temporary.resolve("target"));
            List<JsonNode> malformed = new ArrayList<>();
            malformed.add(null);
            malformed.add(JSON.createArrayNode());
            malformed.add(JSON.createObjectNode());
            malformed.add(valid.deepCopy().put("unexpected", true));
            ObjectNode renamed = valid.deepCopy();
            renamed.remove("sourcePath");
            renamed.put("unknownPath", source.toString());
            malformed.add(renamed);
            for (JsonNode input : malformed) rejected(() -> maintenance.preflight(input, SESSION), 400);
            for (String field : List.of("sourcePath", "targetPath")) {
                for (JsonNode value : List.<JsonNode>of(
                        JSON.valueToTree(7),
                        JSON.valueToTree(""),
                        JSON.valueToTree("x".repeat(32768)),
                        JSON.valueToTree("ab"),
                        JSON.valueToTree("relative"),
                        JSON.valueToTree("//host/share"),
                        JSON.valueToTree(source + ":stream"))) {
                    ObjectNode input = valid.deepCopy();
                    input.set(field, value);
                    rejected(() -> maintenance.preflight(input, SESSION), 400);
                }
            }
            rejected(
                    () -> maintenance.preflight(
                            paths(temporary.resolve("other"), temporary.resolve("target")), SESSION),
                    409);
            Path file = temporary.resolve("ordinary-file");
            Files.writeString(file, "原文件不得覆盖。");
            Path occupied = Files.createDirectories(temporary.resolve("occupied"));
            Files.writeString(occupied.resolve("keep.txt"), "已有内容不得删除。");
            Path repository = Files.createDirectories(temporary.resolve("repository"));
            Files.writeString(repository.resolve("AGENTS.md"), "固定源码标记。");
            Files.writeString(repository.resolve("pom.xml"), "固定源码标记。");
            for (Path target : List.of(
                    source,
                    source.resolve("nested"),
                    temporary,
                    source.getRoot(),
                    file,
                    occupied,
                    repository,
                    repository.resolve("server"))) {
                JsonNode result = maintenance.preflight(paths(source, target), SESSION);
                assertThat(result.path("allowed").asBoolean())
                        .as(target.toString())
                        .isFalse();
                assertThat(result.path("reasons").isEmpty()).isFalse();
                rejected(() -> maintenance.submit(confirmation(result), SESSION, key()), 409);
            }
            JsonNode token = maintenance.preflight(valid, SESSION);
            rejected(() -> maintenance.submit(confirmation(token), "other-session", key()), 409);
            rejected(() -> maintenance.submit(confirmation(token).put("confirmed", false), SESSION, key()), 400);
            rejected(() -> maintenance.submit(confirmation(token).put("confirmed", "true"), SESSION, key()), 400);
            rejected(
                    () -> maintenance.submit(confirmation(token).put("preflightToken", "invalid"), SESSION, key()),
                    400);
            rejected(() -> maintenance.submit(confirmation(token), SESSION, "invalid"), 400);
            rejected(
                    () -> maintenance.submit(
                            JSON.createObjectNode().put("preflightToken", key()).put("confirmed", true),
                            SESSION,
                            key()),
                    409);
            rejected(() -> maintenance.submitArchive("backup", confirmation(token), SESSION, key()), 409);
            JsonNode backup = maintenance.backupPreflight(
                    JSON.createObjectNode()
                            .put("targetPath", temporary.resolve("backup").toString()),
                    SESSION);
            rejected(() -> maintenance.submit(confirmation(backup), SESSION, key()), 409);
            rejected(() -> maintenance.submitArchive("backup", confirmation(backup), "other-session", key()), 409);
            Files.createDirectories(temporary.resolve("target"));
            Files.writeString(temporary.resolve("target/foreign.txt"), "提交前新增内容。");
            rejected(() -> maintenance.submit(confirmation(token), SESSION, key()), 409);
            Files.createDirectories(temporary.resolve("backup"));
            Files.writeString(temporary.resolve("backup/foreign.txt"), "备份前新增内容。");
            rejected(() -> maintenance.submitArchive("backup", confirmation(backup), SESSION, key()), 409);
            rejected(() -> maintenance.get("invalid"), 400);
            rejected(() -> maintenance.get(key()), 404);
            rejected(
                    () -> maintenance.retry(key(), JSON.createObjectNode().put("action", "invalid"), SESSION, key()),
                    400);
            rejected(
                    () -> maintenance.retry(key(), JSON.createObjectNode().put("action", "resume"), SESSION, key()),
                    404);
            rejected(() -> maintenance.backupPreflight(JSON.createObjectNode().put("targetPath", "ab"), SESSION), 400);
            rejected(() -> maintenance.restorePreflight(JSON.createObjectNode().put("backupPath", "ab"), SESSION), 400);
            assertThat(app.getBean(ApplicationData.class).directory()).isEqualTo(source);
            assertThat(Files.readString(file)).isEqualTo("原文件不得覆盖。");
            assertThat(Files.readString(occupied.resolve("keep.txt"))).isEqualTo("已有内容不得删除。");
        }
    }

    @Test
    @DisplayName("真实备份拒绝损坏清单、路径越界、缺失副本及提交前篡改")
    void validatesRealBackupBeforeRestore() throws Exception {
        Path source = temporary.resolve("source"), backup = temporary.resolve("backup");
        try (var app = start(source)) {
            Maintenance maintenance = app.getBean(Maintenance.class);
            Files.createDirectories(source.resolve("files"));
            Files.writeString(source.resolve("files/fixture.txt"), "固定备份内容。");
            JsonNode preflight =
                    maintenance.backupPreflight(JSON.createObjectNode().put("targetPath", backup.toString()), SESSION);
            ObjectNode request = confirmation(preflight);
            String idempotency = key();
            String id = maintenance
                    .submitArchive("backup", request, SESSION, idempotency)
                    .at("/task/id")
                    .asText();
            assertThat(await(maintenance, id).at("/task/state").asText()).isEqualTo("succeeded");
            assertThat(maintenance
                            .submitArchive("backup", request, SESSION, idempotency)
                            .at("/task/id")
                            .asText())
                    .isEqualTo(id);
            rejected(
                    () -> maintenance.submitArchive(
                            "backup", confirmation(JSON.createObjectNode().put("token", key())), SESSION, idempotency),
                    409);
            rejected(() -> maintenance.retry(id, JSON.createObjectNode().put("action", "resume"), SESSION, key()), 409);
            ObjectNode original = (ObjectNode) JSON.readTree(Files.readString(backup.resolve("manifest.json")));
            List<Consumer<ObjectNode>> corruptions = List.of(
                    node -> node.put("extra", true),
                    node -> node.put("formatVersion", 2),
                    node -> node.put("schemaVersion", 16),
                    node -> node.put("credentialsIncluded", "true"),
                    node -> node.put("vectorSnapshotIncluded", "false"),
                    node -> node.put("vectorSnapshotIncluded", true),
                    node -> node.put("files", "invalid"),
                    node -> node.set("files", JSON.createArrayNode().add("invalid")),
                    node -> first(node).put("extra", true),
                    node -> first(node).put("path", 7),
                    node -> first(node).put("size", "1"),
                    node -> first(node).put("size", -1),
                    node -> first(node).put("hash", "invalid"),
                    node -> first(node).put("path", "../outside.txt"),
                    node -> first(node).put("path", "."),
                    node -> first(node).put("path", "files/../application.sqlite"),
                    node -> first(node).put("path", "missing.txt"),
                    node -> first(node).put("path", "application.lock"),
                    node -> first(node).put("size", first(node).path("size").asLong() + 1),
                    node -> first(node).put("hash", "0".repeat(64)),
                    node -> ((ArrayNode) node.path("files")).add(first(node).deepCopy()),
                    node -> node.set("files", JSON.createArrayNode()));
            for (Consumer<ObjectNode> corruption : corruptions) {
                ObjectNode changed = original.deepCopy();
                corruption.accept(changed);
                Files.writeString(backup.resolve("manifest.json"), JSON.writeValueAsString(changed));
                assertThat(maintenance
                                .restorePreflight(JSON.createObjectNode().put("backupPath", backup.toString()), SESSION)
                                .path("allowed")
                                .asBoolean())
                        .isFalse();
            }
            Files.writeString(backup.resolve("manifest.json"), "[]");
            assertThat(restore(maintenance, backup).path("allowed").asBoolean()).isFalse();
            Files.writeString(backup.resolve("manifest.json"), JSON.writeValueAsString(original));
            Files.writeString(backup.resolve("foreign.txt"), "未登记内容。");
            assertThat(restore(maintenance, backup).path("allowed").asBoolean()).isFalse();
            Files.delete(backup.resolve("foreign.txt"));
            Files.writeString(backup.resolve("data/foreign.txt"), "未登记副本。");
            assertThat(restore(maintenance, backup).path("allowed").asBoolean()).isFalse();
            Files.delete(backup.resolve("data/foreign.txt"));
            for (Path unsafe : List.of(source, temporary, source.getRoot(), temporary.resolve("missing")))
                assertThat(restore(maintenance, unsafe).path("allowed").asBoolean())
                        .isFalse();
            JsonNode ready = restore(maintenance, backup);
            assertThat(ready.path("allowed").asBoolean()).isTrue();
            ObjectNode restoreRequest = confirmation(ready)
                    .put("acknowledgePlaintextCredentials", true)
                    .put("acknowledgeIndexRebuildCost", true);
            for (String field :
                    List.of("confirmed", "acknowledgePlaintextCredentials", "acknowledgeIndexRebuildCost")) {
                ObjectNode unconfirmed = restoreRequest.deepCopy().put(field, false);
                rejected(() -> maintenance.submitArchive("restore", unconfirmed, SESSION, key()), 400);
            }
            rejected(() -> maintenance.submitArchive("restore", restoreRequest, "other-session", key()), 409);
            Files.writeString(backup.resolve("data/fixture-corruption.txt"), "预检后篡改。");
            rejected(() -> maintenance.submitArchive("restore", restoreRequest, SESSION, key()), 409);
            assertThat(app.getBean(ApplicationData.class).directory()).isEqualTo(source);
            assertThat(Files.readString(source.resolve("files/fixture.txt"))).isEqualTo("固定备份内容。");
        }
    }

    @Test
    @DisplayName("预检数量有界且维护暂停期间拒绝新的预检")
    void boundsPreflightQueueAndHonorsPause() {
        Path source = temporary.resolve("source");
        try (var app = start(source)) {
            Maintenance maintenance = app.getBean(Maintenance.class);
            ApplicationData data = app.getBean(ApplicationData.class);
            assertThat(data.gate().pause()).isTrue();
            try {
                rejected(() -> maintenance.preflight(paths(source, temporary.resolve("target")), SESSION), 409);
                rejected(() -> restore(maintenance, temporary.resolve("backup")), 409);
            } finally {
                data.gate().resume();
            }
            for (int index = 0; index < 128; index++) maintenance.preflight(paths(source, source), SESSION);
            rejected(() -> maintenance.preflight(paths(source, source), SESSION), 429);
            rejected(() -> restore(maintenance, temporary.resolve("backup")), 429);
            assertThat(data.directory()).isEqualTo(source);
        }
    }

    @Test
    @DisplayName("恢复真实维护检查点时保留中断状态和切换后的目录保护")
    void recoversInterruptedCheckpointsWithoutImplicitRetry() throws Exception {
        Path source = temporary.resolve("source"), backup = temporary.resolve("backup");
        try (var app = start(source)) {
            Maintenance maintenance = app.getBean(Maintenance.class);
            ApplicationData data = app.getBean(ApplicationData.class);
            JsonNode token =
                    maintenance.backupPreflight(JSON.createObjectNode().put("targetPath", backup.toString()), SESSION);
            String id = maintenance
                    .submitArchive("backup", confirmation(token), SESSION, key())
                    .at("/task/id")
                    .asText();
            assertThat(await(maintenance, id).at("/task/state").asText()).isEqualTo("succeeded");
            Path checkpoint = data.anchor().resolve("migration-state.json");
            ObjectNode original = (ObjectNode) JSON.readTree(Files.readString(checkpoint));
            for (String operation : List.of("backup", "migration", "restore")) {
                ObjectNode interrupted = original.deepCopy();
                ObjectNode result = (ObjectNode) interrupted.path("result");
                ObjectNode task = (ObjectNode) result.path("task");
                result.put("operation", operation);
                task.put("kind", operation).put("state", operation.equals("migration") ? "queued" : "running");
                if (!operation.equals("backup")) result.put("targetPath", source.toString());
                Files.writeString(checkpoint, JSON.writeValueAsString(interrupted));
                Maintenance recovered = new Maintenance(
                        data,
                        app.getBean(KnowledgeBases.class),
                        app.getBean(ModelSettingsService.class),
                        app.getBean(VectorCollections.class),
                        JSON,
                        app);
                try {
                    JsonNode snapshot = recovered.get(id);
                    boolean switched = !operation.equals("backup");
                    assertThat(snapshot.at("/task/state").asText()).isEqualTo("interrupted");
                    assertThat(snapshot.at("/task/failure/code").asText()).isEqualTo("TASK_INTERRUPTED");
                    assertThat(snapshot.path("configurationSwitched").asBoolean())
                            .isEqualTo(switched);
                    assertThat(snapshot.path("recoverable").asBoolean()).isEqualTo(!switched);
                    assertThat(snapshot.path("oldDirectoryResidue").asBoolean())
                            .isEqualTo(operation.equals("migration"));
                    if (switched) {
                        assertThat(snapshot.path("indexStatus").asText()).isEqualTo("unavailable");
                        rejected(
                                () -> recovered.retry(
                                        id, JSON.createObjectNode().put("action", "resume"), SESSION, key()),
                                409);
                    }
                    if (!operation.equals("migration"))
                        rejected(
                                () -> recovered.retry(
                                        id, JSON.createObjectNode().put("action", "cleanup_residue"), SESSION, key()),
                                409);
                    ((ObjectNode) snapshot.path("task")).put("state", "succeeded");
                    assertThat(recovered.get(id).at("/task/state").asText()).isEqualTo("interrupted");
                } finally {
                    recovered.close();
                }
            }
            ObjectNode failed = original.deepCopy();
            ObjectNode result = (ObjectNode) failed.path("result");
            ((ObjectNode) result.path("task")).put("state", "failed");
            result.put("recoverable", false);
            Files.writeString(checkpoint, JSON.writeValueAsString(failed));
            Maintenance failedRecovery = new Maintenance(
                    data,
                    app.getBean(KnowledgeBases.class),
                    app.getBean(ModelSettingsService.class),
                    app.getBean(VectorCollections.class),
                    JSON,
                    app);
            try {
                rejected(
                        () -> failedRecovery.retry(id, JSON.createObjectNode().put("action", "resume"), SESSION, key()),
                        409);
            } finally {
                failedRecovery.close();
            }
            result.put("recoverable", true);
            failed.put("sourcePath", temporary.resolve("historical-source").toString());
            Files.writeString(checkpoint, JSON.writeValueAsString(failed));
            Maintenance historicalRecovery = new Maintenance(
                    data,
                    app.getBean(KnowledgeBases.class),
                    app.getBean(ModelSettingsService.class),
                    app.getBean(VectorCollections.class),
                    JSON,
                    app);
            try {
                rejected(
                        () -> historicalRecovery.retry(
                                id, JSON.createObjectNode().put("action", "resume"), SESSION, key()),
                        409);
            } finally {
                historicalRecovery.close();
            }
            Files.writeString(checkpoint, JSON.writeValueAsString(original));
            assertThat(data.directory()).isEqualTo(source);
        }
    }

    @Test
    @DisplayName("清单遍历仅容忍根目录数据库临时元数据消失，普通资料和嵌套同名文件缺失仍拒绝")
    void disappearingDatabaseMetadataDoesNotInvalidatePreflight() throws Exception {
        Path source = temporary.resolve("source").toAbsolutePath();
        try (var app = start(source)) {
            Maintenance maintenance = app.getBean(Maintenance.class);
            Files.writeString(source.resolve("fixture.txt"), "普通资料必须完整保留。");
            for (String relative : List.of(
                    "application.sqlite-wal",
                    "application.sqlite-shm",
                    "ordinary.txt",
                    "nested/application.sqlite-wal")) {
                Path vanished = source.resolve(relative);
                Files.createDirectories(vanished.getParent());
                var missing = new java.nio.file.NoSuchFileException(vanished.toString());
                // 使用确定的缺失异常回调，避免触碰应用正在使用的数据库临时文件。
                try (var filesystem =
                        org.mockito.Mockito.mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
                    filesystem
                            .when(() -> Files.walkFileTree(
                                    org.mockito.ArgumentMatchers.eq(source),
                                    org.mockito.ArgumentMatchers.<java.nio.file.FileVisitor<Path>>any()))
                            .thenAnswer(invocation -> {
                                java.nio.file.FileVisitor<Path> visitor = invocation.getArgument(1);
                                visitor.visitFileFailed(vanished, missing);
                                return invocation.callRealMethod();
                            });
                    JsonNode preflight = maintenance.preflight(paths(source, temporary.resolve("target")), SESSION);
                    boolean excluded =
                            relative.equals("application.sqlite-wal") || relative.equals("application.sqlite-shm");
                    assertThat(preflight.path("allowed").asBoolean())
                            .as(relative)
                            .isEqualTo(excluded);
                    if (!excluded)
                        assertThat(preflight.path("reasons").isEmpty())
                                .as(relative)
                                .isFalse();
                }
                assertThat(Files.readString(source.resolve("fixture.txt"))).isEqualTo("普通资料必须完整保留。");
            }
        }
    }

    private ConfigurableApplicationContext start(Path source) {
        return new SpringApplication(KnowledgePoolApplication.class)
                .run("--server.port=0", "--know-pool.data-directory=" + source);
    }

    private static ObjectNode paths(Path source, Path target) {
        return JSON.createObjectNode().put("sourcePath", source.toString()).put("targetPath", target.toString());
    }

    private static ObjectNode confirmation(JsonNode preflight) {
        return JSON.createObjectNode()
                .put("preflightToken", preflight.path("token").asText())
                .put("confirmed", true);
    }

    private static ObjectNode first(ObjectNode manifest) {
        return (ObjectNode) manifest.path("files").get(0);
    }

    private static JsonNode restore(Maintenance maintenance, Path backup) {
        return maintenance.restorePreflight(JSON.createObjectNode().put("backupPath", backup.toString()), SESSION);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static void rejected(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiFailure.class, failure -> assertThat(failure.status())
                .isEqualTo(status));
    }

    private static JsonNode await(Maintenance maintenance, String id) throws InterruptedException {
        for (int attempt = 0; attempt < 400; attempt++) {
            JsonNode result = maintenance.get(id);
            if (List.of("succeeded", "failed", "interrupted")
                    .contains(result.at("/task/state").asText())) return result;
            Thread.sleep(25);
        }
        throw new AssertionError("维护任务未在限时内结束。");
    }
}
