package cn.knowpool.datasets.api;

import cn.knowpool.datasets.FileService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public final class FileController {
    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @PostMapping(value = "/api/v1/datasets/{datasetId}/files:upload", consumes = "multipart/form-data")
    public FileService.Upload upload(
            @PathVariable String datasetId,
            @RequestParam("files") List<MultipartFile> input,
            HttpServletResponse response,
            jakarta.servlet.http.HttpServletRequest request)
            throws jakarta.servlet.ServletException, IOException {
        if (request.getQueryString() != null) throw cn.knowpool.infrastructure.ApiFailure.invalid(null, "上传操作不接受查询参数。");
        for (var part : request.getParts()) {
            if (!part.getName().equals("files") || part.getSubmittedFileName() == null)
                throw cn.knowpool.infrastructure.ApiFailure.invalid(part.getName(), "上传请求包含未知字段或非文件项目。");
        }
        return files.upload(datasetId, input, response.getHeader("X-Request-Id"));
    }

    @GetMapping("/api/v1/files/{fileId}")
    public FileService.FileView get(@PathVariable String fileId, jakarta.servlet.http.HttpServletRequest request) {
        query(request, java.util.Set.of());
        return files.get(fileId);
    }

    @PatchMapping(value = "/api/v1/files/{fileId}/name", consumes = "application/json")
    public FileService.FileView rename(
            @PathVariable String fileId,
            @RequestBody tools.jackson.databind.JsonNode input,
            jakarta.servlet.http.HttpServletRequest request) {
        query(request, java.util.Set.of());
        if (!input.isObject()) throw cn.knowpool.infrastructure.ApiFailure.invalid(null, "请求正文须为字段对象。");
        for (String field : input.propertyNames())
            if (!field.equals("name"))
                throw cn.knowpool.infrastructure.ApiFailure.invalid(
                        "/" + field.replace("~", "~0").replace("/", "~1"), "请求包含未声明的字段。");
        if (!input.has("name") || !input.get("name").isString())
            throw cn.knowpool.infrastructure.ApiFailure.invalid("/name", "须提供字符串类型的文件名。");
        return files.rename(fileId, input.get("name").asText());
    }

    @GetMapping("/api/v1/datasets/{datasetId}/files")
    public FileService.Page list(
            @PathVariable String datasetId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            jakarta.servlet.http.HttpServletRequest request) {
        query(request, java.util.Set.of("limit", "cursor"));
        if (limit < 1
                || limit > 100
                || (request.getParameter("limit") != null
                        && !request.getParameter("limit").matches("[0-9]+")))
            throw cn.knowpool.infrastructure.ApiFailure.invalid("limit", "每页数量须为一至一百的整数。");
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 2048))
            throw cn.knowpool.infrastructure.ApiFailure.invalid("cursor", "分页位置长度不正确。");
        return files.list(datasetId, limit, cursor);
    }

    @GetMapping("/api/v1/files/{fileId}/versions")
    public FileService.VersionPage versions(
            @PathVariable String fileId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            jakarta.servlet.http.HttpServletRequest request) {
        query(request, java.util.Set.of("limit", "cursor"));
        if (limit < 1
                || limit > 100
                || (request.getParameter("limit") != null
                        && !request.getParameter("limit").matches("[0-9]+")))
            throw cn.knowpool.infrastructure.ApiFailure.invalid("limit", "每页数量须为一至一百的整数。");
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 2048))
            throw cn.knowpool.infrastructure.ApiFailure.invalid("cursor", "分页位置长度不正确。");
        return files.versions(fileId, limit, cursor);
    }

    @GetMapping("/api/v1/files/{fileId}/latest/download")
    public ResponseEntity<byte[]> download(@PathVariable String fileId, jakarta.servlet.http.HttpServletRequest request)
            throws IOException {
        query(request, java.util.Set.of());
        return content(fileId, false);
    }

    @GetMapping("/api/v1/files/{fileId}/latest/preview")
    public ResponseEntity<byte[]> preview(@PathVariable String fileId, jakarta.servlet.http.HttpServletRequest request)
            throws IOException {
        query(request, java.util.Set.of());
        return content(fileId, true);
    }

    @GetMapping("/api/v1/files/{fileId}/versions/{contentVersionId}/preview")
    public ResponseEntity<byte[]> previewVersion(
            @PathVariable String fileId,
            @PathVariable String contentVersionId,
            jakarta.servlet.http.HttpServletRequest request)
            throws IOException {
        query(request, java.util.Set.of());
        return versionContent(fileId,contentVersionId,true);
    }

    @GetMapping("/api/v1/files/{fileId}/versions/{contentVersionId}/download")
    public ResponseEntity<byte[]> downloadVersion(@PathVariable String fileId, @PathVariable String contentVersionId,
            jakarta.servlet.http.HttpServletRequest request) throws IOException {
        query(request,java.util.Set.of());
        return versionContent(fileId,contentVersionId,false);
    }

    private ResponseEntity<byte[]> versionContent(String fileId, String contentVersionId, boolean preview) throws IOException {
        FileService.VersionContent stored = files.versionContent(fileId, contentVersionId);
        String media = stored.version().mediaType();
        return ResponseEntity.ok()
                .header("Content-Type", media + (media.startsWith("text/") ? ";charset=UTF-8" : ""))
                .header("Content-Disposition", ContentDisposition.builder(preview ? "inline" : "attachment").filename(stored.version().uploadedName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header("Cache-Control", "no-store")
                .body(stored.bytes());
    }

    private static void query(jakarta.servlet.http.HttpServletRequest request, java.util.Set<String> allowed) {
        request.getParameterMap().forEach((name, values) -> {
            if (!allowed.contains(name) || values.length != 1)
                throw cn.knowpool.infrastructure.ApiFailure.invalid(name, "请求包含未声明或重复的查询参数。");
        });
    }

    private ResponseEntity<byte[]> content(String id, boolean preview) throws IOException {
        FileService.Stored stored = files.content(id);
        FileService.FileView file = stored.file();
        String media = file.latestVersion().mediaType();
        return ResponseEntity.ok()
                .header("Content-Type", media + (media.startsWith("text/") ? ";charset=UTF-8" : ""))
                .header(
                        "Content-Disposition",
                        ContentDisposition.builder(preview ? "inline" : "attachment")
                                .filename(file.name(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .body(stored.bytes());
    }
}
