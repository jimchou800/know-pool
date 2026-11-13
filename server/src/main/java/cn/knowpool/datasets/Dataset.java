package cn.knowpool.datasets;

public record Dataset(
        String id,
        String name,
        String description,
        String createdAt,
        String updatedAt,
        long fileCount,
        long totalBytes) {
    public Dataset(String id, String name, String description, String createdAt, String updatedAt) {
        this(id, name, description, createdAt, updatedAt, 0, 0);
    }
}
