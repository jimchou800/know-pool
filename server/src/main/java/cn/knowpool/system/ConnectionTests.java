package cn.knowpool.system;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class ConnectionTests {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.knowpool.infrastructure.RuntimeSettings runtime;

    private static final String COST_WARNING = "连接测试可能已产生费用，请勿自动重新提交。";
    private final ApplicationData data;
    private final JsonMapper json;
    private final ConnectionProbe probe;
    private final cn.knowpool.infrastructure.AdjustableCapacity capacity =
            new cn.knowpool.infrastructure.AdjustableCapacity(
                    () -> runtime == null ? 17 : (int) runtime.number("queueCapacity") + 1);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "connection-test");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean stopping = new AtomicBoolean();

    public ConnectionTests(ApplicationData data, JsonMapper json, ConnectionProbe probe) {
        this.data = data;
        this.json = json;
        this.probe = probe;
        data.write(jdbc -> {
            for (String payload : jdbc.sql(
                            "SELECT payload FROM tasks WHERE state IN ('queued','running') AND kind IN ('model_test','vector_connection_test')")
                    .query(String.class)
                    .list()) {
                Task task = json.readValue(payload, Task.class);
                persist(
                        jdbc,
                        task.finish("interrupted", null, new Failure("TASK_INTERRUPTED", "连接测试已因应用重启中断。", false)),
                        null);
            }
            return null;
        });
    }

    public Task accept(String kind, String session, String key, String fingerprint) {
        String validKey = ModelSettingsService.identifier(key, "Idempotency-Key");
        Pending pending = data.write(jdbc -> {
            List<String> previous = jdbc.sql(
                            "SELECT payload FROM tasks WHERE session_id=? AND operation=? AND idempotency_key=?")
                    .params(session, kind, validKey)
                    .query(String.class)
                    .list();
            if (!previous.isEmpty()) {
                String stored = jdbc.sql(
                                "SELECT request_fingerprint FROM tasks WHERE session_id=? AND operation=? AND idempotency_key=?")
                        .params(session, kind, validKey)
                        .query(String.class)
                        .single();
                if (!stored.equals(fingerprint)) throw new ApiFailure(409, "CONFLICT", "同一幂等键不能用于不同请求。", null);
                return new Pending(json.readValue(previous.getFirst(), Task.class), null);
            }
            ProbeSnapshot snapshot;
            String version = null;
            if (kind.equals("vector")) {
                ModelSettingsService.VectorSnapshot vector = ModelSettingsService.vectorSnapshot(jdbc);
                if (vector.endpoint() == null) throw incomplete();
                snapshot = new ProbeSnapshot(kind, vector.endpoint(), null, null, vector.secret());
            } else {
                ModelSettingsService.Snapshot model = ModelSettingsService.current(jdbc, kind);
                if (!model.view().complete()) throw incomplete();
                snapshot = new ProbeSnapshot(
                        kind, model.endpoint(), model.model(), model.maxOutputTokens(), model.secret());
                version = model.versionId();
            }
            if (!capacity.tryAcquire()) throw new ApiFailure(429, "QUEUE_FULL", "连接测试队列已满，请稍后重试。", null);
            boolean accepted = false;
            try {
                Task task = new Task(
                        UUID.randomUUID().toString(),
                        kind.equals("vector") ? "vector_connection_test" : "model_test",
                        "queued",
                        "queued",
                        Instant.now().toString(),
                        null,
                        null,
                        null,
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
                                "INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,?,?,?,?,?,?,?)")
                        .params(
                                task.id(),
                                task.kind(),
                                task.state(),
                                json.writeValueAsString(task),
                                session,
                                kind,
                                validKey,
                                fingerprint)
                        .update();
                cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, json.valueToTree(task));
                accepted = true;
                return new Pending(task, snapshot);
            } finally {
                if (!accepted) capacity.release();
            }
        });
        if (pending.snapshot() != null) executor.execute(data.gate().activity(() -> run(pending), capacity::release));
        return pending.task();
    }

    private static ApiFailure incomplete() {
        return new ApiFailure(422, "INVALID_STATE", "请先保存完整连接配置。", null);
    }

    public Task get(String id) {
        String valid = ModelSettingsService.identifier(id, "taskId");
        return data.read(jdbc -> get(jdbc, valid));
    }

    private Task get(JdbcClient jdbc, String id) {
        return jdbc.sql("SELECT payload FROM tasks WHERE id=?")
                .param(id)
                .query(String.class)
                .optional()
                .map(value -> json.readValue(value, Task.class))
                .orElseThrow(() -> new ApiFailure(404, "NOT_FOUND", "任务不存在。", null));
    }

    public Result result(String id) {
        String valid = ModelSettingsService.identifier(id, "taskId");
        return data.read(jdbc -> {
            Task task = get(jdbc, valid);
            if (!List.of("model_test", "vector_connection_test").contains(task.kind()))
                throw new ApiFailure(404, "NOT_FOUND", "任务不是连接测试。", null);
            if (!task.state().equals("succeeded"))
                throw new ApiFailure(409, "INVALID_STATE", "连接测试没有可读取的成功终态结果，请查看原任务。", null);
            String result = jdbc.sql("SELECT result FROM tasks WHERE id=?")
                    .param(valid)
                    .query((row, index) -> row.getString(1))
                    .single();
            if (result == null) throw new ApiFailure(500, "INTERNAL_ERROR", "连接测试结果完整性校验失败。", null);
            return json.readValue(result, Result.class);
        });
    }

    private void run(Pending pending) {
        Task running = pending.task().running("initializing");
        try {
            data.write(jdbc -> {
                persist(jdbc, running, null);
                return null;
            });
            Task validating = running.running("validating");
            data.write(jdbc -> {
                persist(jdbc, validating, null);
                return null;
            });
            boolean available = probe.test(pending.snapshot());
            Result result = new Result(
                    available,
                    Instant.now().toString(),
                    available ? "连接正常。" : "连接不可用，请检查已保存配置。",
                    !pending.snapshot().kind().equals("vector"));
            if (!stopping.get())
                data.write(jdbc -> {
                    persist(jdbc, validating.finish("succeeded", result, null), result);
                    return null;
                });
        } catch (RuntimeException failure) {
            if (!stopping.get())
                data.write(jdbc -> {
                    persist(
                            jdbc,
                            running.finish(
                                    "failed", null, new Failure("INTERNAL_ERROR", "连接测试执行失败，请查看配置后手动重试。", false)),
                            null);
                    return null;
                });
        } finally {
            capacity.release();
        }
    }

    private void persist(JdbcClient jdbc, Task task, Result result) {
        cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, json.valueToTree(task));
        jdbc.sql("UPDATE tasks SET state=?,payload=?,result=? WHERE id=?")
                .params(
                        task.state(),
                        json.writeValueAsString(task),
                        result == null ? null : json.writeValueAsString(result),
                        task.id())
                .update();
    }

    @PreDestroy
    public void close() {
        stopping.set(true);
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private record Pending(Task task, ProbeSnapshot snapshot) {}

    record ProbeSnapshot(String kind, String endpoint, String model, Long maxOutputTokens, String secret) {}

    public record Result(boolean available, String checkedAt, String message, boolean mayHaveIncurredCost) {}

    public record Failure(String code, String message, boolean retryable) {}

    public record Accepted(Task task) {}

    public record Task(
            String id,
            String kind,
            String state,
            String phase,
            String createdAt,
            String startedAt,
            String finishedAt,
            String knowledgeBaseId,
            String fileId,
            String targetContentVersionId,
            String targetConfigVersionId,
            String retryOfTaskId,
            int attempt,
            long progressCompleted,
            Long progressTotal,
            Failure failure,
            List<String> warnings,
            String resultResourceId) {
        public Task {
            warnings = List.copyOf(warnings);
        }

        Task running(String nextPhase) {
            return new Task(
                    id,
                    kind,
                    "running",
                    nextPhase,
                    createdAt,
                    startedAt == null ? Instant.now().toString() : startedAt,
                    null,
                    knowledgeBaseId,
                    fileId,
                    targetContentVersionId,
                    targetConfigVersionId,
                    retryOfTaskId,
                    1,
                    0,
                    null,
                    null,
                    warnings,
                    resultResourceId);
        }

        Task finish(String nextState, Result result, Failure nextFailure) {
            return new Task(
                    id,
                    kind,
                    nextState,
                    "completed",
                    createdAt,
                    startedAt,
                    Instant.now().toString(),
                    knowledgeBaseId,
                    fileId,
                    targetContentVersionId,
                    targetConfigVersionId,
                    retryOfTaskId,
                    attempt,
                    0,
                    null,
                    nextFailure,
                    result == null && kind.equals("model_test") ? List.of(COST_WARNING) : List.of(),
                    resultResourceId);
        }
    }
}
