package cn.knowpool.system.api;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.TaskEvents;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

@RestController
public final class TaskEventsController {
    private static final Set<String> TERMINAL = Set.of("succeeded", "failed", "cancelled", "interrupted");
    private final TaskEvents events;
    private final Semaphore capacity = new Semaphore(64);
    private final Map<SseEmitter, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "task-events");
        thread.setDaemon(true);
        return thread;
    });

    public TaskEventsController(TaskEvents events) {
        this.events = events;
        ticker.scheduleWithFixedDelay(this::refresh, 250, 250, TimeUnit.MILLISECONDS);
    }

    @GetMapping("/api/v1/tasks/{taskId}/events")
    public SseEmitter watch(@PathVariable String taskId, HttpServletRequest request) {
        if (request.getQueryString() != null) throw ApiFailure.invalid("query", "该操作不接受查询参数。");
        var cursors = Collections.list(request.getHeaders("Last-Event-ID"));
        if (cursors.size() > 1) throw ApiFailure.invalid("Last-Event-ID", "恢复位置不得重复。");
        if (!cursors.isEmpty()) {
            String cursor = cursors.getFirst();
            if (!cursor.matches(
                            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}:[1-9][0-9]{0,15}")
                    || !cursor.substring(0, 36).equalsIgnoreCase(taskId)
                    || Long.parseLong(cursor.substring(37)) > 9007199254740991L)
                throw ApiFailure.invalid("Last-Event-ID", "恢复位置须为本任务标识与安全范围内的正整数序号。");
        }
        JsonNode snapshot = events.snapshot(taskId);
        if (!capacity.tryAcquire()) throw new ApiFailure(429, "QUEUE_FULL", "任务订阅数量已达上限，请稍后重试。", null);
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30));
        Subscription subscription = new Subscription(
                snapshot.path("taskId").asText(), snapshot.path("sequence").asLong());
        emitter.onCompletion(() -> release(emitter));
        emitter.onTimeout(() -> {
            release(emitter);
            emitter.complete();
        });
        emitter.onError(failure -> release(emitter));
        synchronized (emitter) {
            subscriptions.put(emitter, subscription);
            try {
                send(emitter, snapshot);
            } catch (IOException | RuntimeException failure) {
                release(emitter);
                emitter.completeWithError(failure);
            }
        }
        return emitter;
    }

    private void send(SseEmitter emitter, JsonNode snapshot) throws IOException {
        boolean terminal = TERMINAL.contains(snapshot.path("state").asText());
        emitter.send(SseEmitter.event()
                .id(snapshot.path("taskId").asText() + ":"
                        + snapshot.path("sequence").asLong())
                .name(terminal ? "terminal" : "phase")
                .data(snapshot, MediaType.APPLICATION_JSON));
        if (terminal) {
            release(emitter);
            emitter.complete();
        }
    }

    private void refresh() {
        subscriptions.forEach((emitter, subscription) -> {
            synchronized (emitter) {
                if (!subscriptions.containsKey(emitter)) return;
                try {
                    JsonNode snapshot = events.snapshot(subscription.taskId);
                    long sequence = snapshot.path("sequence").asLong();
                    if (sequence != subscription.sequence) {
                        send(emitter, snapshot);
                        subscription.sequence = sequence;
                    } else if (System.nanoTime() - subscription.heartbeatAt >= TimeUnit.SECONDS.toNanos(5)) {
                        emitter.send(SseEmitter.event().comment("心跳"));
                        subscription.heartbeatAt = System.nanoTime();
                    }
                } catch (IOException | RuntimeException failure) {
                    release(emitter);
                    emitter.completeWithError(failure);
                }
            }
        });
    }

    private void release(SseEmitter emitter) {
        if (subscriptions.remove(emitter) != null) capacity.release();
    }

    @PreDestroy
    public void close() {
        ticker.shutdownNow();
        subscriptions.forEach((emitter, subscription) -> {
            release(emitter);
            emitter.complete();
        });
    }

    private static final class Subscription {
        private final String taskId;
        private long sequence;
        private long heartbeatAt = System.nanoTime();

        private Subscription(String taskId, long sequence) {
            this.taskId = taskId;
            this.sequence = sequence;
        }
    }
}
