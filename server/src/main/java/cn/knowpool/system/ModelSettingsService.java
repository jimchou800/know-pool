package cn.knowpool.system;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public final class ModelSettingsService {
    private final ApplicationData data;

    public ModelSettingsService(ApplicationData data) {
        this.data = data;
    }

    public ModelConfig current(String kind) {
        return data.read(jdbc -> current(jdbc, kind)).view();
    }

    public ModelConfig save(
            String kind, String endpoint, String model, Long context, Long output, CredentialChange change) {
        return data.write(jdbc -> {
            Snapshot previous = current(jdbc, kind);
            String secret = changedSecret(change, previous.secret(), previous.endpoint(), endpoint);
            boolean identityChanged = !Objects.equals(previous.endpoint(), endpoint)
                    || !Objects.equals(previous.model(), model)
                    || !Objects.equals(previous.contextWindowTokens(), context)
                    || !Objects.equals(previous.maxOutputTokens(), output);
            if (!identityChanged && Objects.equals(secret, previous.secret())) return previous.view();
            String id = identityChanged ? UUID.randomUUID().toString() : previous.versionId();
            String now = Instant.now().toString();
            jdbc.sql(
                            "INSERT INTO model_configs(id,kind,endpoint,model,context_tokens,output_tokens,credential,updated_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET credential=excluded.credential,updated_at=excluded.updated_at")
                    .params(id, kind, endpoint, model, context, output, secret, now)
                    .update();
            jdbc.sql("UPDATE active_configs SET config_id=? WHERE kind=?")
                    .params(id, kind)
                    .update();
            return new Snapshot(kind, id, endpoint, model, context, output, secret, now).view();
        });
    }

    public ModelConfig historical(String id) {
        return data.read(jdbc -> historical(jdbc, identifier(id, "modelConfigVersionId")))
                .view();
    }

    private static Snapshot historical(JdbcClient jdbc, String id) {
        return jdbc.sql("SELECT * FROM model_configs WHERE id=? AND kind='embedding'")
                .param(id)
                .query(ModelSettingsService::snapshot)
                .optional()
                .orElseThrow(() -> new ApiFailure(404, "NOT_FOUND", "嵌入配置版本不存在。", null));
    }

    public ModelConfig credential(String id, CredentialChange change) {
        String validId = identifier(id, "modelConfigVersionId");
        return data.write(jdbc -> {
            Snapshot previous = historical(jdbc, validId);
            String secret = changedSecret(change, previous.secret(), previous.endpoint(), previous.endpoint());
            if (!Objects.equals(secret, previous.secret())) {
                jdbc.sql("UPDATE model_configs SET credential=?,updated_at=? WHERE id=?")
                        .params(secret, Instant.now().toString(), validId)
                        .update();
            }
            return historical(jdbc, validId).view();
        });
    }

    public ModelPage versions(int limit, String cursor) {
        String after = null;
        if (cursor != null) {
            try {
                after = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
                if (!Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(after.getBytes(StandardCharsets.US_ASCII))
                        .equals(cursor)) throw new IllegalArgumentException();
                identifier(after, "cursor");
            } catch (IllegalArgumentException failure) {
                throw ApiFailure.invalid("cursor", "分页位置无效。");
            }
        }
        String position = after;
        return data.read(jdbc -> {
            List<Snapshot> rows = position == null
                    ? jdbc.sql("SELECT * FROM model_configs WHERE kind='embedding' ORDER BY id LIMIT ?")
                            .param(limit + 1)
                            .query(ModelSettingsService::snapshot)
                            .list()
                    : jdbc.sql("SELECT * FROM model_configs WHERE kind='embedding' AND id>? ORDER BY id LIMIT ?")
                            .params(position, limit + 1)
                            .query(ModelSettingsService::snapshot)
                            .list();
            List<Snapshot> page = rows.subList(0, Math.min(rows.size(), limit));
            String next = rows.size() > limit
                    ? Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(page.getLast().versionId().getBytes(StandardCharsets.US_ASCII))
                    : null;
            return new ModelPage(page.stream().map(Snapshot::view).toList(), next);
        });
    }

    public VectorConfig saveVector(String endpoint, CredentialChange change) {
        return data.write(jdbc -> {
            VectorSnapshot previous = vectorSnapshot(jdbc);
            String secret = changedSecret(change, previous.secret(), previous.endpoint(), endpoint);
            if (!Objects.equals(endpoint, previous.endpoint()) || !Objects.equals(secret, previous.secret())) {
                jdbc.sql("UPDATE vector_config SET endpoint=?,credential=?,updated_at=? WHERE id=1")
                        .params(endpoint, secret, Instant.now().toString())
                        .update();
            }
            return vectorSnapshot(jdbc).view();
        });
    }

    static VectorSnapshot vectorSnapshot(JdbcClient jdbc) {
        return jdbc.sql("SELECT * FROM vector_config WHERE id=1")
                .query((row, index) -> new VectorSnapshot(
                        row.getString("endpoint"), row.getString("credential"), row.getString("updated_at")))
                .single();
    }

    private static String changedSecret(CredentialChange change, String previous, String oldEndpoint, String endpoint) {
        if (change.action().equals("keep")) {
            if (previous != null
                    && !Objects.equals(oldEndpoint, endpoint)
                    && (oldEndpoint == null
                            || endpoint == null
                            || !origin(oldEndpoint).equals(origin(endpoint))))
                throw ApiFailure.invalid("/credential/action", "连接来源已变化，请明确替换或清除密钥后保存。");
            return previous;
        }
        return change.action().equals("clear") ? null : change.value();
    }

    private static String origin(String endpoint) {
        URI uri = URI.create(endpoint);
        int port = uri.getPort() < 0 ? (uri.getScheme().equals("https") ? 443 : 80) : uri.getPort();
        return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    public static String identifier(String value, String field) {
        try {
            String normalized = UUID.fromString(value).toString();
            if (!normalized.equals(value.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException failure) {
            throw ApiFailure.invalid(field, "标识须为合法的唯一标识。");
        }
    }

    static Snapshot current(JdbcClient jdbc, String kind) {
        return jdbc.sql("SELECT m.* FROM model_configs m JOIN active_configs a ON a.config_id=m.id WHERE a.kind=?")
                .param(kind)
                .query(ModelSettingsService::snapshot)
                .single();
    }

    private static Snapshot snapshot(ResultSet row, int index) throws SQLException {
        return new Snapshot(
                row.getString("kind"),
                row.getString("id"),
                row.getString("endpoint"),
                row.getString("model"),
                nullableLong(row, "context_tokens"),
                nullableLong(row, "output_tokens"),
                row.getString("credential"),
                row.getString("updated_at"));
    }

    private static Long nullableLong(ResultSet row, String name) throws SQLException {
        long value = row.getLong(name);
        return row.wasNull() ? null : value;
    }

    public VectorConfig vector() {
        return data.read(ModelSettingsService::vectorSnapshot).view();
    }

    public VectorConnection vectorConnection() {
        return data.read(jdbc -> {
            VectorSnapshot stored = vectorSnapshot(jdbc);
            return new VectorConnection(stored.endpoint(), stored.secret());
        });
    }

    public record VectorConnection(String endpoint, String secret) {
        @Override
        public String toString() {
            return "向量连接快照";
        }
    }

    public record CredentialState(boolean configured, String mask) {
        static CredentialState of(String secret) {
            return new CredentialState(secret != null, secret == null ? null : "【密钥已设置】");
        }
    }

    public record ModelConfig(
            String kind,
            String versionId,
            String endpoint,
            String model,
            CredentialState credential,
            Long contextWindowTokens,
            Long maxOutputTokens,
            boolean complete,
            String updatedAt) {}

    public record VectorConfig(String endpoint, CredentialState credential, boolean configured, String updatedAt) {}

    public record CredentialChange(String action, String value) {}

    public record ModelPage(List<ModelConfig> items, String nextCursor) {
        public ModelPage {
            items = List.copyOf(items);
        }
    }

    record VectorSnapshot(String endpoint, String secret, String updatedAt) {
        VectorConfig view() {
            return new VectorConfig(endpoint, CredentialState.of(secret), endpoint != null, updatedAt);
        }
    }

    record Snapshot(
            String kind,
            String versionId,
            String endpoint,
            String model,
            Long contextWindowTokens,
            Long maxOutputTokens,
            String secret,
            String updatedAt) {
        ModelConfig view() {
            return new ModelConfig(
                    kind,
                    versionId,
                    endpoint,
                    model,
                    CredentialState.of(secret),
                    contextWindowTokens,
                    maxOutputTokens,
                    endpoint != null
                            && model != null
                            && secret != null
                            && (kind.equals("embedding") || contextWindowTokens != null && maxOutputTokens != null),
                    updatedAt);
        }
    }
}
