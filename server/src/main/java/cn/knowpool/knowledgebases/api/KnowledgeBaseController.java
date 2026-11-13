package cn.knowpool.knowledgebases.api;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.knowledgebases.KnowledgeBases;
import cn.knowpool.system.ConnectionTests;
import cn.knowpool.system.ModelSettingsService;
import cn.knowpool.system.api.LocalSessionFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public final class KnowledgeBaseController {
    private final KnowledgeBases service;

    public KnowledgeBaseController(KnowledgeBases service) {
        this.service = service;
    }

    @PostMapping(value = "/api/v1/knowledge-bases", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ConnectionTests.Accepted create(@RequestBody JsonNode input, HttpServletRequest request) {
        query(request, Set.of());
        object(input, Set.of("name", "description", "processing", "datasetIds"), Set.of("name", "processing"), "");
        text(input, "name", 1, 120, "");
        if (input.has("description")) text(input, "description", 0, 2000, "");
        processing(input.path("processing"));
        if (input.has("datasetIds")) {
            strings(input.path("datasetIds"), 100, "/datasetIds");
            for (JsonNode id : input.path("datasetIds")) ModelSettingsService.identifier(id.asText(), "/datasetIds");
        }
        List<String> keys = java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw ApiFailure.invalid("Idempotency-Key", "须提供唯一幂等键。");
        LocalSessionFilter.Session session = (LocalSessionFilter.Session) request.getAttribute("localSession");
        return service.create(session.sessionId(), keys.getFirst(), input);
    }

    @GetMapping("/api/v1/knowledge-bases")
    public KnowledgeBases.Page list(HttpServletRequest request) {
        return service.list(limit(request), request.getParameter("cursor"));
    }

    @GetMapping("/api/v1/knowledge-bases/{knowledgeBaseId}")
    public JsonNode get(@PathVariable String knowledgeBaseId, HttpServletRequest request) {
        query(request, Set.of());
        return service.get(identifier(knowledgeBaseId));
    }

    @GetMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/processing-config")
    public JsonNode config(@PathVariable String knowledgeBaseId, HttpServletRequest request) {
        query(request, Set.of());
        return service.config(identifier(knowledgeBaseId));
    }

    @PatchMapping(value = "/api/v1/knowledge-bases/{knowledgeBaseId}", consumes = "application/json")
    public JsonNode update(
            @PathVariable String knowledgeBaseId, @RequestBody JsonNode input, HttpServletRequest request) {
        query(request, Set.of());
        object(input, Set.of("name", "description"), Set.of(), "");
        if (input.isEmpty()) throw ApiFailure.invalid(null, "至少修改名称或说明之一。");
        if (input.has("name")) text(input, "name", 1, 120, "");
        if (input.has("description")) text(input, "description", 0, 2000, "");
        return service.update(identifier(knowledgeBaseId), input);
    }

    @GetMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/sources")
    public KnowledgeBases.Page sources(@PathVariable String knowledgeBaseId, HttpServletRequest request) {
        return service.sources(identifier(knowledgeBaseId), limit(request), request.getParameter("cursor"));
    }

    @GetMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/files")
    public KnowledgeBases.Page files(@PathVariable String knowledgeBaseId, HttpServletRequest request) {
        return service.files(identifier(knowledgeBaseId), limit(request), request.getParameter("cursor"));
    }

    @PostMapping(value = "/api/v1/knowledge-bases/{knowledgeBaseId}/sources", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode source(
            @PathVariable String knowledgeBaseId, @RequestBody JsonNode input, HttpServletRequest request) {
        query(request, Set.of());
        object(input, Set.of("type", "resourceId"), Set.of("type", "resourceId"), "");
        choice(input, "type", Set.of("dataset", "file"), "");
        String id = ModelSettingsService.identifier(text(input, "resourceId", 1, 100, ""), "/resourceId");
        return service.addSource(identifier(knowledgeBaseId), input.path("type").asText(), id);
    }

    private static String identifier(String value) {
        return ModelSettingsService.identifier(value, "knowledgeBaseId");
    }

    private static void processing(JsonNode input) {
        String base = "/processing";
        object(input, Set.of("chunking", "embedding"), Set.of("chunking", "embedding"), base);
        JsonNode chunk = input.path("chunking");
        String path = base + "/chunking";
        object(
                chunk,
                Set.of(
                        "strategy",
                        "overlapCodePoints",
                        "removePageHeadersFooters",
                        "customMetadata",
                        "embeddedMetadataFields"),
                Set.of(
                        "strategy",
                        "overlapCodePoints",
                        "removePageHeadersFooters",
                        "customMetadata",
                        "embeddedMetadataFields"),
                path);
        choice(chunk, "strategy", Set.of("structure", "fixed", "page"), path);
        integer(chunk, "overlapCodePoints", 0, 1999, path);
        if (!chunk.path("removePageHeadersFooters").isBoolean())
            throw ApiFailure.invalid(path + "/removePageHeadersFooters", "字段须为布尔值。");
        JsonNode metadata = chunk.path("customMetadata");
        if (!metadata.isObject() || metadata.size() > 20)
            throw ApiFailure.invalid(path + "/customMetadata", "自定义元数据须为最多二十项的对象。");
        for (String field : metadata.propertyNames()) {
            if (!field.matches("user_[A-Za-z0-9_]{1,60}"))
                throw ApiFailure.invalid(path + "/customMetadata/" + field, "元数据名称须使用用户字段前缀。");
            text(metadata, field, 0, 2000, path + "/customMetadata");
        }
        strings(chunk.path("embeddedMetadataFields"), 20, path + "/embeddedMetadataFields");
        for (JsonNode field : chunk.path("embeddedMetadataFields"))
            if (!metadata.has(field.asText()))
                throw ApiFailure.invalid(path + "/embeddedMetadataFields", "选入嵌入的字段必须存在。");
        JsonNode embedding = input.path("embedding");
        path = base + "/embedding";
        object(
                embedding,
                Set.of(
                        "modelConfigVersionId",
                        "dimensions",
                        "distance",
                        "similarityThreshold",
                        "candidatesPerQuery",
                        "modelSnapshot"),
                Set.of(
                        "modelConfigVersionId",
                        "dimensions",
                        "distance",
                        "similarityThreshold",
                        "candidatesPerQuery",
                        "modelSnapshot"),
                path);
        ModelSettingsService.identifier(
                text(embedding, "modelConfigVersionId", 1, 100, path), path + "/modelConfigVersionId");
        integer(embedding, "dimensions", 1, 65536, path);
        integer(embedding, "candidatesPerQuery", 1, 1000, path);
        choice(embedding, "distance", Set.of("cosine", "dot", "euclidean"), path);
        if (!embedding.path("similarityThreshold").isNumber()
                || !Double.isFinite(embedding.path("similarityThreshold").asDouble()))
            throw ApiFailure.invalid(path + "/similarityThreshold", "阈值须为有限数字。");
        JsonNode snapshot = embedding.path("modelSnapshot");
        object(snapshot, Set.of("endpoint", "model"), Set.of("endpoint", "model"), path + "/modelSnapshot");
        text(snapshot, "model", 1, 200, path + "/modelSnapshot");
        String endpoint = text(snapshot, "endpoint", 1, Integer.MAX_VALUE, path + "/modelSnapshot");
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw ApiFailure.invalid(path + "/modelSnapshot/endpoint", "模型快照地址无效。");
        }
    }

    private static void object(JsonNode value, Set<String> allowed, Set<String> required, String path) {
        if (!value.isObject()) throw ApiFailure.invalid(path, "字段须为对象。");
        for (String field : value.propertyNames())
            if (!allowed.contains(field))
                throw ApiFailure.invalid(path + "/" + field.replace("~", "~0").replace("/", "~1"), "请求包含未声明的字段。");
        for (String field : required) if (!value.has(field)) throw ApiFailure.invalid(path + "/" + field, "缺少必填字段。");
    }

    private static String text(JsonNode input, String field, int min, int max, String path) {
        JsonNode value = input.path(field);
        if (!value.isString()) throw ApiFailure.invalid(path + "/" + field, "字段须为字符串。");
        String result = value.asText();
        int length = result.codePointCount(0, result.length());
        if (length < min || length > max) throw ApiFailure.invalid(path + "/" + field, "字段长度不符合要求。");
        return result;
    }

    private static void integer(JsonNode input, String field, long min, long max, String path) {
        JsonNode value = input.path(field);
        if (!value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < min
                || value.longValue() > max) throw ApiFailure.invalid(path + "/" + field, "整数超出允许范围。");
    }

    private static void choice(JsonNode input, String field, Set<String> allowed, String path) {
        if (!input.path(field).isString() || !allowed.contains(input.path(field).asText()))
            throw ApiFailure.invalid(path + "/" + field, "选项不受支持。");
    }

    private static void strings(JsonNode value, int max, String path) {
        if (!value.isArray() || value.size() > max) throw ApiFailure.invalid(path, "字段须为限定数量的数组。");
        Set<String> found = new HashSet<>();
        for (JsonNode item : value)
            if (!item.isString() || !found.add(item.asText())) throw ApiFailure.invalid(path, "数组须为不重复字符串。");
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap().forEach((field, values) -> {
            if (!allowed.contains(field) || values.length != 1) throw ApiFailure.invalid(field, "查询参数未声明或重复。");
        });
    }

    private static int limit(HttpServletRequest request) {
        query(request, Set.of("limit", "cursor"));
        String value = request.getParameter("limit");
        int limit = 20;
        if (value != null)
            try {
                if (!value.matches("[0-9]+")) throw new NumberFormatException();
                limit = Integer.parseInt(value);
                if (limit < 1 || limit > 100) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                throw ApiFailure.invalid("limit", "每页数量须为一至一百。");
            }
        String cursor = request.getParameter("cursor");
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 2048))
            throw ApiFailure.invalid("cursor", "分页位置长度不正确。");
        return limit;
    }
}
