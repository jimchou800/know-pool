package cn.knowpool.datasets.data;

import cn.knowpool.datasets.Dataset;
import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class DatasetRepository {
    private static final String SELECT =
            "SELECT d.*, (SELECT COUNT(*) FROM files f WHERE f.dataset_id=d.id AND NOT EXISTS(SELECT 1 FROM deleted_resources x WHERE x.kind='file' AND x.id=f.id)) AS file_count, (SELECT COALESCE(SUM(v.size_bytes),0) FROM files f JOIN file_versions v ON f.latest_version=v.id WHERE f.dataset_id=d.id AND NOT EXISTS(SELECT 1 FROM deleted_resources x WHERE x.kind='file' AND x.id=f.id)) AS total_bytes FROM datasets d ";
    private final ApplicationData data;

    public DatasetRepository(ApplicationData data) {
        this.data = data;
    }

    public List<Dataset> list(int count, String beforeTime, String beforeId) {
        return data.read(jdbc -> jdbc.sql(
                        SELECT
                                + "WHERE NOT EXISTS(SELECT 1 FROM deleted_resources x WHERE x.kind='dataset' AND x.id=d.id) AND (? IS NULL OR created_at < ? OR (created_at = ? AND id < ?)) ORDER BY created_at DESC, id DESC LIMIT ?")
                .param(beforeTime)
                .param(beforeTime)
                .param(beforeTime)
                .param(beforeId)
                .param(count)
                .query((row, index) -> new Dataset(
                        row.getString("id"),
                        row.getString("name"),
                        row.getString("description"),
                        row.getString("created_at"),
                        row.getString("updated_at"),
                        row.getLong("file_count"),
                        row.getLong("total_bytes")))
                .list());
    }

    public Dataset get(String id) {
        return data.read(jdbc -> find(jdbc, id));
    }

    public Dataset create(Dataset dataset, String nameKey) {
        return data.write(jdbc -> {
            unique(jdbc, nameKey, dataset.id());
            jdbc.sql("INSERT INTO datasets(id,name,name_key,description,created_at,updated_at) VALUES (?,?,?,?,?,?)")
                    .param(dataset.id())
                    .param(dataset.name())
                    .param(nameKey)
                    .param(dataset.description())
                    .param(dataset.createdAt())
                    .param(dataset.updatedAt())
                    .update();
            return dataset;
        });
    }

    public Dataset update(String id, String name, String nameKey, String description, String timestamp) {
        return data.write(jdbc -> {
            Dataset current = find(jdbc, id);
            String finalName = name == null ? current.name() : name;
            String finalDescription = description == null ? current.description() : description;
            if (nameKey != null) unique(jdbc, nameKey, id);
            jdbc.sql(
                            "UPDATE datasets SET name=?, name_key=COALESCE(?,name_key), description=?, updated_at=? WHERE id=?")
                    .param(finalName)
                    .param(nameKey)
                    .param(finalDescription)
                    .param(timestamp)
                    .param(id)
                    .update();
            return new Dataset(
                    id,
                    finalName,
                    finalDescription,
                    current.createdAt(),
                    timestamp,
                    current.fileCount(),
                    current.totalBytes());
        });
    }

    private static Dataset find(JdbcClient jdbc, String id) {
        return jdbc.sql(SELECT + "WHERE id=? AND NOT EXISTS(SELECT 1 FROM deleted_resources x WHERE x.kind='dataset' AND x.id=d.id)")
                .param(id)
                .query((row, index) -> new Dataset(
                        row.getString("id"),
                        row.getString("name"),
                        row.getString("description"),
                        row.getString("created_at"),
                        row.getString("updated_at"),
                        row.getLong("file_count"),
                        row.getLong("total_bytes")))
                .optional()
                .orElseThrow(() -> new ApiFailure(404, "NOT_FOUND", "数据集不存在。", null));
    }

    private static void unique(JdbcClient jdbc, String key, String id) {
        if (jdbc.sql("SELECT COUNT(*) FROM datasets WHERE name_key=? AND id<>?")
                        .param(key)
                        .param(id)
                        .query(Integer.class)
                        .single()
                > 0) {
            throw new ApiFailure(409, "NAME_EXISTS", "名称已存在，请使用其他名称。", "/name");
        }
    }
}
