package cn.knowpool.datasets;

import cn.knowpool.datasets.data.DatasetRepository;
import cn.knowpool.infrastructure.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class DatasetService {
    private static final DateTimeFormatter TIMESTAMP =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private final DatasetRepository repository;

    public DatasetService(DatasetRepository repository) {
        this.repository = repository;
    }

    public Page list(int limit, String cursor) {
        String time = null;
        String id = null;
        if (cursor != null) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
                String[] parts = decoded.split("/", -1);
                if (parts.length != 2
                        || !Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(decoded.getBytes(StandardCharsets.US_ASCII))
                                .equals(cursor)) throw new IllegalArgumentException();
                time = parts[0];
                if (!TIMESTAMP.format(Instant.parse(time)).equals(time)) throw new IllegalArgumentException();
                id = identifier(parts[1]);
            } catch (IllegalArgumentException | java.time.DateTimeException failure) {
                throw ApiFailure.invalid("cursor", "分页位置无效，请重新载入列表。");
            }
        }
        List<Dataset> rows = repository.list(limit + 1, time, id);
        boolean more = rows.size() > limit;
        List<Dataset> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String next = null;
        if (more) {
            Dataset last = items.getLast();
            next = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString((last.createdAt() + "/" + last.id()).getBytes(StandardCharsets.US_ASCII));
        }
        return new Page(items, next);
    }

    public Dataset get(String id) {
        return repository.get(identifier(id));
    }

    public Dataset create(String name, String description) {
        String normalized = normalize(name);
        String now = TIMESTAMP.format(Instant.now());
        return repository.create(
                new Dataset(UUID.randomUUID().toString(), normalized, description, now, now),
                normalized.toLowerCase(Locale.ROOT));
    }

    public Dataset update(String id, String name, String description) {
        String normalized = name == null ? null : normalize(name);
        return repository.update(
                identifier(id),
                normalized,
                normalized == null ? null : normalized.toLowerCase(Locale.ROOT),
                description,
                TIMESTAMP.format(Instant.now()));
    }

    private static String identifier(String id) {
        try {
            String normalized = UUID.fromString(id).toString();
            if (!normalized.equals(id.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException failure) {
            throw ApiFailure.invalid("datasetId", "数据集标识格式不正确。");
        }
    }

    private static String normalize(String name) {
        String trimmed = name.replaceAll("^\\p{IsWhite_Space}+|\\p{IsWhite_Space}+$", "");
        String normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFC);
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 120) {
            throw ApiFailure.invalid("/name", "规范化后的名称须为一至一百二十个字符。");
        }
        return normalized;
    }

    public record Page(List<Dataset> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
