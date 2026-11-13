package cn.knowpool.datasets;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public final class FileService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FileService.class);
    private final ApplicationData data;
    private final DatasetService datasets;
    private final cn.knowpool.infrastructure.UploadConfiguration.Limits limits;
    private final org.springframework.context.ApplicationEventPublisher events;

    public List<SourceFile> sourceFiles(org.springframework.jdbc.core.simple.JdbcClient jdbc, String type, String id) {
        String valid = cn.knowpool.system.ModelSettingsService.identifier(id, "/resourceId");
        if (type.equals("dataset")) {
            datasets.get(valid);
            return jdbc.sql("SELECT id, dataset_id, latest_version FROM files WHERE dataset_id=? ORDER BY id")
                    .param(valid)
                    .query((row, index) -> new SourceFile(row.getString(1), row.getString(2), row.getString(3)))
                    .list();
        }
        return List.of(jdbc.sql("SELECT id,dataset_id,latest_version FROM files WHERE id=?")
                .param(valid)
                .query((row, index) -> new SourceFile(row.getString(1), row.getString(2), row.getString(3)))
                .optional()
                .orElseThrow(() -> new ApiFailure(404, "NOT_FOUND", "资料不存在。", null)));
    }

    public java.util.Map<String, CurrentFile> currentFiles(
            org.springframework.jdbc.core.simple.JdbcClient jdbc, List<String> ids) {
        if (ids.isEmpty()) return java.util.Map.of();
        var result = new java.util.HashMap<String, CurrentFile>();
        jdbc.sql(
                        "SELECT f.id,f.dataset_id,f.latest_version,f.name,v.available FROM files f JOIN file_versions v ON v.id=f.latest_version WHERE f.id IN (:ids)")
                .param("ids", ids)
                .query((row, n) -> {
                    result.put(
                            row.getString(1),
                            new CurrentFile(row.getString(2), row.getString(3), row.getString(4), row.getBoolean(5)));
                    return true;
                })
                .list();
        return java.util.Map.copyOf(result);
    }

    public record CurrentFile(String datasetId, String contentVersionId, String filename, boolean available) {}

    public record SourceFile(String id, String datasetId, String contentVersionId) {}

    public FileService(
            ApplicationData data,
            DatasetService datasets,
            cn.knowpool.infrastructure.UploadConfiguration.Limits limits,
            org.springframework.context.ApplicationEventPublisher events) {
        this.data = data;
        this.datasets = datasets;
        this.limits = limits;
        this.events = events;
    }

    private Saved create(String datasetId, MultipartFile input) throws IOException {
        datasets.get(datasetId);
        String id = UUID.randomUUID().toString();
        String version = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String uploader = uploader();
        String name = input.getOriginalFilename();
        validateName(name, "/files");
        if (input.getSize() > limits.maxFileBytes())
            throw new ApiFailure(413, "RESOURCE_LIMIT", "单份资料超过大小上限。", "/files");
        String relative = "files/versions/" + version;
        Path target = data.directory().resolve(relative);
        Path parent = java.util.Objects.requireNonNull(target.getParent());
        validateStoragePath(parent);
        Files.createDirectories(parent);
        validateStoragePath(parent);
        byte[] bytes = input.getBytes();
        String fileType = validateContent(input, bytes);
        String mediaType = mediaType(fileType);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
        boolean retained = false;
        boolean created = false;
        try {
            try (var output = Files.newOutputStream(
                    target, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                created = true;
                output.write(bytes);
            }
            Saved saved = data.write(jdbc -> {
                var existing = jdbc.sql("SELECT id FROM files WHERE dataset_id=? AND name_key=?")
                        .param(datasetId)
                        .param(nameKey(name))
                        .query(String.class)
                        .optional();
                String fileId = existing.orElse(id);
                if (existing.isPresent()) {
                    FileView current = get(fileId);
                    if (current.latestVersion().sizeBytes() == bytes.length
                            && current.latestVersion().contentHash().equals(hash))
                        return new Saved("unchanged", current, List.of());
                    jdbc.sql("UPDATE files SET latest_version=?, uploaded_at=?, updated_at=? WHERE id=?")
                            .param(version)
                            .param(now)
                            .param(now)
                            .param(fileId)
                            .update();
                } else {
                    jdbc.sql("INSERT INTO files VALUES (?,?,?,?,?,?,?,?,?)")
                            .param(fileId)
                            .param(datasetId)
                            .param(name)
                            .param(nameKey(name))
                            .param(uploader)
                            .param(now)
                            .param(now)
                            .param(now)
                            .param(version)
                            .update();
                }
                jdbc.sql("INSERT INTO file_versions VALUES (?,?,?,?,?,?,?,?,?,?,?)")
                        .param(version)
                        .param(fileId)
                        .param(name)
                        .param(fileType)
                        .param(mediaType)
                        .param(bytes.length)
                        .param(hash)
                        .param(uploader)
                        .param(now)
                        .param(relative)
                        .param(1)
                        .update();
                ContentCommitted event = new ContentCommitted(jdbc, fileId, datasetId, version);
                events.publishEvent(event);
                return new Saved(
                        existing.isPresent() ? "replaced" : "created", get(fileId), List.copyOf(event.taskIds()));
            });
            retained = !saved.outcome().equals("unchanged");
            return saved;
        } finally {
            if (created && !retained) Files.deleteIfExists(target);
        }
    }

    private static String nameKey(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String uploader() {
        try {
            String value = System.getProperty("user.name");
            if (value != null && !value.isBlank()) return value;
        } catch (SecurityException failure) {
            LOG.warn("无法读取系统上传人，已按未知用户继续。");
            return null;
        }
        LOG.warn("系统上传人为空，已按未知用户继续。");
        return null;
    }

    private record Saved(String outcome, FileView file, List<String> taskIds) {}

    public record ContentCommitted(
            org.springframework.jdbc.core.simple.JdbcClient jdbc,
            String fileId,
            String datasetId,
            String contentVersionId,
            List<String> taskIds) {
        public ContentCommitted {
            taskIds = new java.util.ArrayList<>(taskIds);
        }

        @Override
        public List<String> taskIds() {
            return List.copyOf(taskIds);
        }

        public void addTaskId(String id) {
            taskIds.add(id);
        }

        public ContentCommitted(
                org.springframework.jdbc.core.simple.JdbcClient jdbc,
                String fileId,
                String datasetId,
                String contentVersionId) {
            this(jdbc, fileId, datasetId, contentVersionId, new java.util.ArrayList<>());
        }
    }

    public Upload upload(String datasetId, List<MultipartFile> inputs, String requestId) {
        datasetId = datasets.get(datasetId).id();
        if (inputs.isEmpty() || inputs.size() > limits.maxUploadFiles())
            throw ApiFailure.invalid("files", "文件数量超出允许范围。");
        Map<String, Long> groups = inputs.stream()
                .map(input -> input.getOriginalFilename() == null ? "" : nameKey(input.getOriginalFilename()))
                .collect(java.util.stream.Collectors.groupingBy(
                        java.util.function.Function.identity(), java.util.stream.Collectors.counting()));
        var items = new java.util.ArrayList<Item>();
        for (int index = 0; index < inputs.size(); index++) {
            MultipartFile input = inputs.get(index);
            String name = input.getOriginalFilename() == null ? "" : input.getOriginalFilename();
            String submitted = name.codePointCount(0, name.length()) > 4096
                    ? name.substring(0, name.offsetByCodePoints(0, 4096))
                    : name;
            try {
                if (groups.getOrDefault(nameKey(name), 0L) > 1)
                    throw new ApiFailure(409, "CONFLICT", "同批文件名重复，请每组只选择一份资料。", "/files");
                Saved saved = create(datasetId, input);
                items.add(new Item(index, submitted, saved.outcome(), saved.file(), null, saved.taskIds()));
            } catch (ApiFailure failure) {
                items.add(new Item(
                        index,
                        submitted,
                        "failed",
                        null,
                        error(
                                requestId,
                                failure.code(),
                                failure.getMessage(),
                                failure.fieldErrors(),
                                failure.status() >= 500),
                        List.of()));
            } catch (IOException | org.springframework.dao.DataAccessException failure) {
                items.add(new Item(
                        index,
                        submitted,
                        "failed",
                        null,
                        error(requestId, "INTERNAL_ERROR", "资料保存失败，请重试。", List.of(), true),
                        List.of()));
            }
        }
        return new Upload(List.copyOf(items), requestId);
    }

    private static Map<String, Object> error(
            String requestId, String code, String message, List<ApiFailure.FieldError> fields, boolean retryable) {
        return Map.of(
                "requestId",
                requestId,
                "code",
                code,
                "message",
                message,
                "fieldErrors",
                fields,
                "retryable",
                retryable);
    }

    private static void validateName(String name, String field) {
        if (name == null
                || name.isEmpty()
                || name.codePointCount(0, name.length()) > 255
                || !name.matches("^(?!\\.{1,2}$)[^\\\\/\\x00-\\x1f\\x7f]+$"))
            throw ApiFailure.invalid(field, "文件名不合法，请勿包含目录或控制字符。");
    }

    private String validateContent(MultipartFile input, byte[] bytes) throws IOException {
        String name = input.getOriginalFilename().toLowerCase(java.util.Locale.ROOT);
        String media = input.getContentType();
        String type = name.endsWith(".txt")
                ? "text"
                : name.endsWith(".md") || name.endsWith(".markdown") ? "markdown" : name.endsWith(".pdf") ? "pdf" : "";
        if (type.isEmpty() || media == null || !media.split(";", 2)[0].equalsIgnoreCase(mediaType(type)))
            throw unsupported();
        if (type.equals("pdf")) {
            Path directory = data.directory().resolve("inspection");
            validateStoragePath(directory);
            Files.createDirectories(directory);
            validateStoragePath(directory);
            Path temporary = Files.createTempFile(directory, "pdf-", ".bin");
            try {
                Files.write(temporary, bytes);
                var result = cn.knowpool.infrastructure.FileInspectionWorker.inspectPdf(
                        temporary, limits.parseTimeoutSeconds() * 1000, limits.maxExtractedCodePoints());
                if (result == cn.knowpool.infrastructure.FileInspectionWorker.Result.UNSUPPORTED) throw unsupported();
                if (result == cn.knowpool.infrastructure.FileInspectionWorker.Result.RESOURCE_LIMIT)
                    throw new ApiFailure(413, "RESOURCE_LIMIT", "PDF校验资源不足或超过限制，请稍后重试或调整资源参数。", "/files");
                return type;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        try {
            var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            for (int index = 0; index < text.length(); index++)
                if (text.charAt(index) == 0) throw new CharacterCodingException();
            if (Character.codePointCount(text, 0, text.length()) > limits.maxExtractedCodePoints())
                throw new ApiFailure(413, "RESOURCE_LIMIT", "文本超过允许提取的码点上限。", "/files");
        } catch (CharacterCodingException failure) {
            throw new ApiFailure(415, "UNSUPPORTED_FILE", "纯文本须使用有效UTF-8编码且不包含空字符。", "/files");
        }
        return type;
    }

    private static ApiFailure unsupported() {
        return new ApiFailure(415, "UNSUPPORTED_FILE", "文件类型或内容不受支持。", "/files");
    }

    private static String mediaType(String type) {
        return switch (type) {
            case "pdf" -> "application/pdf";
            case "markdown" -> "text/markdown";
            default -> "text/plain";
        };
    }

    public record Item(
            int index,
            String submittedName,
            String outcome,
            FileView file,
            Map<String, Object> error,
            List<String> indexTaskIds) {
        public Item {
            error = error == null ? null : Map.copyOf(error);
            indexTaskIds = List.copyOf(indexTaskIds);
        }
    }

    public record Upload(List<Item> items, String requestId) {
        public Upload {
            items = List.copyOf(items);
        }
    }

    public FileView get(String id) {
        String fileId = identifier(id);
        return data.read(jdbc -> jdbc.sql(
                        "SELECT f.*,v.id AS version_id,v.uploaded_name,v.file_type,v.media_type,v.size_bytes,v.content_hash,v.uploaded_by,v.available FROM files f JOIN file_versions v ON f.latest_version=v.id WHERE f.id=?")
                .param(fileId)
                .query((row, index) -> new FileView(
                        row.getString("id"),
                        row.getString("dataset_id"),
                        row.getString("name"),
                        row.getString("created_by"),
                        row.getString("created_at"),
                        row.getString("uploaded_at"),
                        row.getString("updated_at"),
                        new Version(
                                row.getString("version_id"),
                                row.getString("id"),
                                row.getString("uploaded_name"),
                                row.getString("file_type"),
                                row.getString("media_type"),
                                row.getLong("size_bytes"),
                                "SHA-256",
                                row.getString("content_hash"),
                                row.getString("uploaded_by"),
                                row.getString("uploaded_at"),
                                row.getInt("available") == 1)))
                .optional()
                .orElseThrow(() -> new ApiFailure(404, "NOT_FOUND", "资料不存在。", null)));
    }

    public FileView rename(String id, String name) {
        validateName(name, "/name");
        return data.write(jdbc -> {
            FileView current = get(id);
            if (jdbc.sql("SELECT COUNT(*) FROM files WHERE dataset_id=? AND name_key=? AND id<>?")
                            .param(current.datasetId())
                            .param(nameKey(name))
                            .param(current.id())
                            .query(Integer.class)
                            .single()
                    > 0) throw new ApiFailure(409, "NAME_EXISTS", "名称已存在，请使用其他名称。", "/name");
            jdbc.sql("UPDATE files SET name=?,name_key=?,updated_at=? WHERE id=?")
                    .param(name)
                    .param(nameKey(name))
                    .param(Instant.now().toString())
                    .param(current.id())
                    .update();
            return get(current.id());
        });
    }

    public Page list(String datasetId, int limit, String cursor) {
        String canonicalId = datasets.get(datasetId).id();
        String time = "~", before = "~";
        if (cursor != null) {
            try {
                String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
                String[] parts = decoded.split("/", -1);
                if (parts.length != 3
                        || !parts[0].equals(canonicalId)
                        || !java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(decoded.getBytes(StandardCharsets.US_ASCII))
                                .equals(cursor)) throw new IllegalArgumentException();
                Instant.parse(parts[1]);
                time = parts[1];
                before = identifier(parts[2]);
            } catch (IllegalArgumentException | java.time.DateTimeException failure) {
                throw ApiFailure.invalid("cursor", "分页位置无效，请重新载入列表。");
            }
        }
        String timestamp = time, lastId = before;
        var rows = data.read(jdbc -> jdbc.sql(
                        "SELECT id FROM files WHERE dataset_id=? AND (created_at<? OR (created_at=? AND id<?)) ORDER BY created_at DESC,id DESC LIMIT ?")
                .param(canonicalId)
                .param(timestamp)
                .param(timestamp)
                .param(lastId)
                .param(limit + 1)
                .query(String.class)
                .list());
        boolean more = rows.size() > limit;
        var items = rows.subList(0, Math.min(limit, rows.size())).stream()
                .map(this::get)
                .toList();
        String next = more
                ? java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString((canonicalId + "/" + items.getLast().createdAt() + "/"
                                        + items.getLast().id())
                                .getBytes(StandardCharsets.US_ASCII))
                : null;
        return new Page(items, next);
    }

    public VersionPage versions(String id, int limit, String cursor) {
        String fileId = get(id).id();
        String time = "~", before = "~";
        if (cursor != null) {
            try {
                String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
                String[] parts = decoded.split("/", -1);
                if (parts.length != 3
                        || !parts[0].equals(fileId)
                        || !java.util.Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(decoded.getBytes(StandardCharsets.US_ASCII))
                                .equals(cursor)) throw new IllegalArgumentException();
                Instant.parse(parts[1]);
                time = parts[1];
                before = identifier(parts[2]);
            } catch (IllegalArgumentException | java.time.DateTimeException failure) {
                throw ApiFailure.invalid("cursor", "分页位置无效，请重新载入列表。");
            }
        }
        String timestamp = time, lastId = before;
        var rows = data.read(jdbc -> jdbc.sql(
                        "SELECT * FROM file_versions WHERE file_id=? AND (uploaded_at<? OR (uploaded_at=? AND id<?)) ORDER BY uploaded_at DESC,id DESC LIMIT ?")
                .param(fileId)
                .param(timestamp)
                .param(timestamp)
                .param(lastId)
                .param(limit + 1)
                .query((row, index) -> new Version(
                        row.getString("id"),
                        row.getString("file_id"),
                        row.getString("uploaded_name"),
                        row.getString("file_type"),
                        row.getString("media_type"),
                        row.getLong("size_bytes"),
                        "SHA-256",
                        row.getString("content_hash"),
                        row.getString("uploaded_by"),
                        row.getString("uploaded_at"),
                        row.getInt("available") == 1))
                .list());
        boolean more = rows.size() > limit;
        var items = rows.subList(0, Math.min(limit, rows.size()));
        String next = more
                ? java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString((fileId + "/" + items.getLast().uploadedAt() + "/"
                                        + items.getLast().id())
                                .getBytes(StandardCharsets.US_ASCII))
                : null;
        return new VersionPage(items, next);
    }

    public record VersionPage(List<Version> items, String nextCursor) {
        public VersionPage {
            items = List.copyOf(items);
        }
    }

    private static String identifier(String id) {
        try {
            String normalized = UUID.fromString(id).toString();
            if (!normalized.equals(id.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException failure) {
            throw ApiFailure.invalid("fileId", "资料标识格式不正确。");
        }
    }

    public record Page(java.util.List<FileView> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private void validateStoragePath(Path path) throws IOException {
        Path current = data.directory();
        for (Path component : data.directory().relativize(path)) {
            current = current.resolve(component);
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || !current.toRealPath().equals(current)))
                throw new IOException("副本路径不能包含目录链接。");
        }
    }

    public Stored content(String id) throws IOException {
        synchronized (data) {
            FileView file = get(id);
            VersionContent content = readVersion(file.id(),file.latestVersion().id());
            return new Stored(file,content.bytes());
        }
    }

    public void cleanObsoleteVersions(java.util.Set<String> protectedVersions) {
        data.write(jdbc -> {
            var candidates = jdbc.sql("SELECT v.id,v.relative_path FROM file_versions v JOIN files f ON f.id=v.file_id WHERE v.available=1 AND v.id<>f.latest_version")
                    .query((row,n) -> new String[]{row.getString(1),row.getString(2)}).list();
            for (String[] candidate : candidates) {
                if (protectedVersions.contains(candidate[0])) continue;
                cleanupPath(candidate[0],candidate[1]);
                jdbc.sql("INSERT OR IGNORE INTO file_version_cleanup(version_id,relative_path) VALUES(?,?)").params(candidate[0],candidate[1]).update();
                jdbc.sql("UPDATE file_versions SET available=0 WHERE id=?").param(candidate[0]).update();
            }
            return null;
        });
        var pending = data.read(jdbc -> jdbc.sql("SELECT version_id,relative_path FROM file_version_cleanup WHERE attempts<3 ORDER BY version_id")
                .query((row,n) -> new String[]{row.getString(1),row.getString(2)}).list());
        for (String[] candidate : pending) {
            Path target = cleanupPath(candidate[0],candidate[1]);
            boolean claimed = data.write(jdbc -> jdbc.sql("UPDATE file_version_cleanup SET attempts=attempts+1 WHERE version_id=? AND attempts<3")
                    .param(candidate[0]).update() == 1);
            if (!claimed) continue;
            try {
                validateStoragePath(target);
                Files.deleteIfExists(target);
                data.write(jdbc -> { jdbc.sql("DELETE FROM file_version_cleanup WHERE version_id=?").param(candidate[0]).update(); return null; });
            } catch (IOException failure) {
                LOG.warn("????????????????????????");
            }
        }
    }

    private Path cleanupPath(String version, String relative) {
        Path target = data.directory().resolve(relative).toAbsolutePath().normalize();
        Path root = data.directory().resolve("files/versions").toAbsolutePath().normalize();
        if (!root.equals(target.getParent()) || target.getFileName() == null || !target.getFileName().toString().equals(version))
            throw new IllegalStateException("???????????");
        return target;
    }

    public VersionContent versionContent(String id, String version) throws IOException {
        version = identifier(version);
        synchronized (data) {
            return readVersion(id, version);
        }
    }

    private VersionContent readVersion(String id, String version) throws IOException {
        FileView file = get(id);
        Version targetVersion = data.read(jdbc -> jdbc.sql("SELECT id,file_id,uploaded_name,file_type,media_type,size_bytes,content_hash,uploaded_by,uploaded_at,relative_path,available FROM file_versions WHERE id=? AND file_id=?")
                .params(version, id)
                .query((row, index) -> new Version(row.getString("id"), row.getString("file_id"), row.getString("uploaded_name"), row.getString("file_type"), row.getString("media_type"), row.getLong("size_bytes"), "SHA-256", row.getString("content_hash"), row.getString("uploaded_by"), row.getString("uploaded_at"), row.getInt("available") == 1))
                .optional()
                .orElseThrow(() -> new ApiFailure(404, "NOT_FOUND", "指定内容版本不存在。", null)));
        if (!targetVersion.originalAvailable())
            throw new ApiFailure(410, "CONTENT_UNAVAILABLE", "指定版本副本不可用。", null);
        String relative = data.read(jdbc -> jdbc.sql("SELECT relative_path FROM file_versions WHERE id=? AND file_id=? AND available=1")
                .params(version, id)
                .query(String.class)
                .single());
        Path root = data.directory().resolve("files/versions").toAbsolutePath().normalize();
        Path target = data.directory().resolve(relative).toAbsolutePath().normalize();
        Path filename = target.getFileName();
        if (!root.equals(target.getParent()) || filename == null || !version.equals(filename.toString()))
            throw new IOException("副本路径不合法。");
        validateStoragePath(target);
        if (Files.size(target) != targetVersion.sizeBytes()) throw new IOException("副本字节大小与目标版本不一致。");
        byte[] bytes = Files.readAllBytes(target);
        try {
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals(targetVersion.contentHash())) throw new IOException("副本哈希与目标版本不一致。");
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IOException("无法校验目标版本。", failure); }
        return new VersionContent(file, targetVersion, bytes);
    }

    public record VersionContent(FileView file, Version version, byte[] bytes) {
        public VersionContent { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record Stored(FileView file, byte[] bytes) {
        public Stored {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record FileView(
            String id,
            String datasetId,
            String name,
            String createdBy,
            String createdAt,
            String lastUploadedAt,
            String updatedAt,
            Version latestVersion) {}

    public record Version(
            String id,
            String fileId,
            String uploadedName,
            String fileType,
            String mediaType,
            long sizeBytes,
            String hashAlgorithm,
            String contentHash,
            String uploadedBy,
            String uploadedAt,
            boolean originalAvailable) {}
}
