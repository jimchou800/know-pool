package cn.knowpool.infrastructure;

import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 运行参数全量保存；调用方读取快照，变更不回写正在执行的请求。 */
@Component
public final class RuntimeSettings {
    public static final Set<String> FIELDS = Set.of(
            "indexConcurrency",
            "questionConcurrency",
            "searchConcurrency",
            "queueCapacity",
            "maxFileBytes",
            "maxUploadBytes",
            "maxUploadFiles",
            "parseTimeoutSeconds",
            "maxExtractedCodePoints",
            "chunkHardLimitCodePoints",
            "maxRequestCandidates",
            "finalEvidenceLimit",
            "rrfConstant",
            "requestModelCallBudget",
            "requestEmbeddingCallBudget",
            "modelTimeoutSeconds",
            "contextEvidenceTokenBudget",
            "sqliteBusyTimeoutMilliseconds",
            "logMaxFileBytes",
            "logRetainedFileCount",
            "historyContextTokenBudget",
            "modelRateLimits");
    private final ApplicationData data;
    private final JsonMapper json;
    private final org.springframework.core.env.Environment environment;
    private final java.util.concurrent.ScheduledExecutorService retention =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "log-retention");
                t.setDaemon(true);
                return t;
            });
    private volatile ObjectNode snapshot;
    private volatile java.nio.file.Path location;
    private final ThreadLocal<ObjectNode> captured = new ThreadLocal<>();

    public RuntimeSettings(
            ApplicationData data, JsonMapper json, org.springframework.core.env.Environment environment) {
        this.data = data;
        this.json = json;
        this.environment = environment;
        refresh();
        retention.scheduleWithFixedDelay(
                () -> {
                    try {
                        applyLogging(get());
                    } catch (RuntimeException ignored) {
                    }
                },
                1,
                1,
                java.util.concurrent.TimeUnit.SECONDS);
    }

    private void refresh() {
        if (snapshot != null && data.directory().equals(location)) return;
        snapshot = data.write(jdbc -> {
            var stored = jdbc.sql("SELECT value FROM application_metadata WHERE name='runtime_settings'")
                    .query(String.class)
                    .optional();
            if (stored.isPresent()) return (ObjectNode) json.readTree(stored.get());
            ObjectNode initial = defaults();
            jdbc.sql("INSERT INTO application_metadata(name,value) VALUES('runtime_settings',?)")
                    .param(json.writeValueAsString(initial))
                    .update();
            return initial;
        });
        location = data.directory();
        applyLogging(snapshot);
        data.busyTimeout(snapshot.path("sqliteBusyTimeoutMilliseconds").asLong());
    }

    public ObjectNode get() {
        if (!data.directory().equals(location)) refresh();
        return snapshot.deepCopy();
    }

    public long number(String field) {
        ObjectNode fixed = captured.get();
        return (fixed == null ? get() : fixed).path(field).asLong();
    }

    public double decimal(String field) {
        ObjectNode fixed = captured.get();
        return (fixed == null ? get() : fixed).path(field).asDouble();
    }

    public Lease capture() {
        ObjectNode previous = captured.get();
        captured.set(get());
        return () -> {
            if (previous == null) captured.remove();
            else captured.set(previous);
        };
    }

    public JsonNode update(JsonNode input, Runnable inputChanged) {
        validate(input);
        if (!(input instanceof ObjectNode object)) throw ApiFailure.invalid("body", "运行参数须为完整对象。");
        ObjectNode next = object.deepCopy();
        synchronized (data) {
            data.write(jdbc -> {
                if (get().path("chunkHardLimitCodePoints").asLong()
                        != next.path("chunkHardLimitCodePoints").asLong()) inputChanged.run();
                jdbc.sql("UPDATE application_metadata SET value=? WHERE name='runtime_settings'")
                        .param(json.writeValueAsString(next))
                        .update();
                return null;
            });
            snapshot = next;
            data.busyTimeout(next.path("sqliteBusyTimeoutMilliseconds").asLong());
            applyLogging(next);
        }
        return next.deepCopy();
    }

    private void validate(JsonNode input) {
        if (input == null
                || !input.isObject()
                || input.size() != FIELDS.size()
                || input.propertyNames().stream().anyMatch(field -> !FIELDS.contains(field)))
            throw ApiFailure.invalid(null, "须完整提供全部运行参数且不得添加未知字段。");
        for (String field : FIELDS) {
            if (field.equals("modelRateLimits")) continue;
            JsonNode value = input.path(field);
            if (field.equals("rrfConstant")) {
                if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() <= 0)
                    throw ApiFailure.invalid(field, "融合常数须为有限正数。");
                continue;
            }
            long max = field.endsWith("Concurrency")
                    ? 32
                    : field.equals("queueCapacity")
                            ? 100000
                            : field.equals("maxUploadFiles") ? 1000 : 9007199254740991L;
            integer(value, field, max);
        }
        if (input.path("maxUploadBytes").asLong() < input.path("maxFileBytes").asLong())
            throw ApiFailure.invalid("maxUploadBytes", "单次上传总量不能小于单文件上限。");
        if (input.path("finalEvidenceLimit").asLong()
                > input.path("maxRequestCandidates").asLong())
            throw ApiFailure.invalid("finalEvidenceLimit", "最终证据数不能超过请求候选总量。");
        if (input.path("chunkHardLimitCodePoints").asLong()
                > input.path("maxExtractedCodePoints").asLong())
            throw ApiFailure.invalid("chunkHardLimitCodePoints", "切片上限不能超过解析正文上限。");
        JsonNode rates = input.path("modelRateLimits");
        if (!rates.isArray() || rates.size() > 100) throw ApiFailure.invalid("modelRateLimits", "模型限流须为最多一百项的数组。");
        Set<String> identities = new HashSet<>();
        for (JsonNode rate : rates) {
            if (!rate.isObject()
                    || rate.size() != 5
                    || !rate.path("endpoint").isTextual()
                    || !rate.path("model").isTextual()
                    || rate.path("model").asText().isBlank())
                throw ApiFailure.invalid("modelRateLimits", "模型限流字段须完整且模型名不能为空。");
            String endpoint = endpoint(rate.path("endpoint").asText());
            if (!identities.add(endpoint + "\n" + rate.path("model").asText()))
                throw ApiFailure.invalid("modelRateLimits", "同端点及模型不能重复设置限流。");
            for (String field : List.of("maxConcurrent", "requestsPerMinute", "tokensPerMinute"))
                integer(rate.path(field), "modelRateLimits/" + field, 9007199254740991L);
        }
        Long context = data.read(jdbc -> jdbc.sql(
                        "SELECT context_tokens FROM model_configs WHERE id=(SELECT config_id FROM active_configs WHERE kind='generation')")
                .query(Long.class)
                .optional()
                .orElse(null));
        Long output = data.read(jdbc -> jdbc.sql(
                        "SELECT output_tokens FROM model_configs WHERE id=(SELECT config_id FROM active_configs WHERE kind='generation')")
                .query(Long.class)
                .optional()
                .orElse(null));
        if (context != null
                && output != null
                && input.path("contextEvidenceTokenBudget").asLong()
                                + input.path("historyContextTokenBudget").asLong()
                                + output
                                + 4096
                        > context) throw ApiFailure.invalid("contextEvidenceTokenBudget", "证据、历史、模型输出及系统预留超过生成模型上下文。");
    }

    private static void integer(JsonNode value, String field, long max) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1 || value.asLong() > max)
            throw ApiFailure.invalid(field, "参数须为契约范围内的正安全整数。");
    }

    public static String endpoint(String raw) {
        try {
            URI uri = URI.create(raw);
            if (!Set.of("http", "https")
                            .contains(
                                    uri.getScheme() == null
                                            ? ""
                                            : uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
            int port = uri.getPort();
            if (port == 80 && uri.getScheme().equalsIgnoreCase("http")
                    || port == 443 && uri.getScheme().equalsIgnoreCase("https")) port = -1;
            return new URI(
                            uri.getScheme().toLowerCase(Locale.ROOT),
                            null,
                            uri.getHost().toLowerCase(Locale.ROOT),
                            port,
                            uri.normalize().getPath(),
                            null,
                            null)
                    .toASCIIString()
                    .replaceAll("/+$", "");
        } catch (RuntimeException | java.net.URISyntaxException failure) {
            throw ApiFailure.invalid("modelRateLimits/endpoint", "限流端点须为不含凭据及查询的合法网络地址。");
        }
    }

    private ObjectNode defaults() {
        ObjectNode value = json.createObjectNode();
        long[] values = {
            1, 1, 1, 16, 10485760, 104857600, 1000, 10, 10485760, 2000, 1000, 100, 60, 3, 16, 5, 8192, 5000, 10485760,
            5, 8192
        };
        String[] names = {
            "indexConcurrency",
            "questionConcurrency",
            "searchConcurrency",
            "queueCapacity",
            "maxFileBytes",
            "maxUploadBytes",
            "maxUploadFiles",
            "parseTimeoutSeconds",
            "maxExtractedCodePoints",
            "chunkHardLimitCodePoints",
            "maxRequestCandidates",
            "finalEvidenceLimit",
            "rrfConstant",
            "requestModelCallBudget",
            "requestEmbeddingCallBudget",
            "modelTimeoutSeconds",
            "contextEvidenceTokenBudget",
            "sqliteBusyTimeoutMilliseconds",
            "logMaxFileBytes",
            "logRetainedFileCount",
            "historyContextTokenBudget"
        };
        for (int i = 0; i < names.length; i++) value.put(names[i], values[i]);
        Map<String, String> legacy = Map.of(
                "maxFileBytes",
                "know-pool.upload.max-file-bytes",
                "maxUploadBytes",
                "know-pool.upload.max-upload-bytes",
                "maxUploadFiles",
                "know-pool.upload.max-upload-files",
                "parseTimeoutSeconds",
                "know-pool.processing.parse-timeout-seconds",
                "maxExtractedCodePoints",
                "know-pool.processing.max-extracted-code-points");
        legacy.forEach((field, key) -> value.put(
                field,
                environment.getProperty(key, Long.class, value.path(field).asLong())));
        value.set("modelRateLimits", json.createArrayNode());
        return value;
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        retention.shutdownNow();
    }

    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    private static void applyLogging(JsonNode value) {
        if (!(org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext context))
            return;
        var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appenders = root.iteratorForAppenders();
        while (appenders.hasNext())
            if (appenders.next() instanceof ch.qos.logback.core.rolling.RollingFileAppender<?> appender
                    && appender.getRollingPolicy()
                            instanceof ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy<?> policy) {
                policy.setMaxFileSize(new ch.qos.logback.core.util.FileSize(
                        value.path("logMaxFileBytes").asLong()));
                policy.setMaxHistory(0);
                if (policy.getTimeBasedFileNamingAndTriggeringPolicy()
                        instanceof ch.qos.logback.core.rolling.SizeAndTimeBasedFileNamingAndTriggeringPolicy<?> trigger)
                    trigger.setMaxFileSize(new ch.qos.logback.core.util.FileSize(
                            value.path("logMaxFileBytes").asLong()));
                String activeFile = appender.getFile();
                if (activeFile != null) {
                    java.nio.file.Path
                            active = java.nio.file.Path.of(activeFile).toAbsolutePath(),
                            directory = active.getParent();
                    java.nio.file.Path activeName = active.getFileName();
                    if (directory == null || activeName == null) continue;
                    String prefix = activeName.toString();
                    if (java.nio.file.Files.isDirectory(directory))
                        try (var entries = java.nio.file.Files.list(directory)) {
                            var archives = entries.filter(file -> {
                                        java.nio.file.Path name = file.getFileName();
                                        return name != null
                                                && !file.equals(active)
                                                && !java.nio.file.Files.isSymbolicLink(file)
                                                && java.nio.file.Files.isRegularFile(file)
                                                && (name.toString().startsWith(prefix + ".")
                                                        || name.toString().startsWith(prefix + "-"));
                                    })
                                    .sorted(java.util.Comparator.comparingLong((java.nio.file.Path file) ->
                                                    file.toFile().lastModified())
                                            .reversed())
                                    .toList();
                            for (long i = value.path("logRetainedFileCount").asLong(); i < archives.size(); i++)
                                java.nio.file.Files.deleteIfExists(archives.get((int) i));
                        } catch (java.io.IOException ignored) {
                        }
                }
            }
    }
}
