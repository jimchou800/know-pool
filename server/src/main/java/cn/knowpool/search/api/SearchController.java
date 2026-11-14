package cn.knowpool.search.api;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.search.SearchRequests;
import cn.knowpool.system.ModelSettingsService;
import cn.knowpool.system.api.LocalSessionFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public final class SearchController {
    private final SearchRequests searches;

    public SearchController(SearchRequests searches) {
        this.searches = searches;
    }

    @PostMapping("/api/v1/search-instances")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode create(@RequestBody(required = false) String body, HttpServletRequest request) {
        noQuery(request);
        if (body != null && !body.isEmpty()) throw ApiFailure.invalid(null, "创建检索实例不接受正文。");
        return searches.createInstance();
    }

    @GetMapping("/api/v1/search-instances")
    public cn.knowpool.knowledgebases.KnowledgeBases.Page instances(HttpServletRequest request) {
        request.getParameterMap().forEach((name, values) -> {
            if (!java.util.Set.of("limit", "cursor").contains(name) || values.length != 1)
                throw ApiFailure.invalid(name, "查询参数未声明或重复。");
        });
        int limit = 20;
        String text = request.getParameter("limit");
        if (text != null)
            try {
                if (!text.matches("[0-9]+")) throw new NumberFormatException();
                limit = Integer.parseInt(text);
                if (limit < 1 || limit > 100) throw new NumberFormatException();
            } catch (NumberFormatException failure) {
                throw ApiFailure.invalid("limit", "每页数量须为一至一百。");
            }
        String cursor = request.getParameter("cursor");
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 2048))
            throw ApiFailure.invalid("cursor", "分页位置长度不正确。");
        return searches.listInstances(limit, cursor);
    }

    @GetMapping("/api/v1/search-instances/{instanceId}")
    public JsonNode instance(@PathVariable String instanceId, HttpServletRequest request) {
        noQuery(request);
        return searches.instance(instanceId);
    }

    @PatchMapping(value = "/api/v1/search-instances/{instanceId}/name", consumes = "application/json")
    public JsonNode rename(@PathVariable String instanceId, @RequestBody JsonNode input, HttpServletRequest request) {
        noQuery(request);
        if (!input.isObject() || input.size() != 1 || !input.path("name").isTextual())
            throw ApiFailure.invalid("/name", "请求须仅含实例名称。");
        String name = input.path("name").asText();
        if (name.isBlank() || name.codePointCount(0, name.length()) > 120)
            throw ApiFailure.invalid("/name", "实例名称须为一至一百二十个字符且不能仅空白。");
        return searches.renameInstance(instanceId, name);
    }

    @PutMapping(value = "/api/v1/search-instances/{instanceId}/scope", consumes = "application/json")
    public JsonNode scope(@PathVariable String instanceId, @RequestBody JsonNode input, HttpServletRequest request) {
        noQuery(request);
        if (!input.isObject()
                || input.size() != 1
                || !input.path("knowledgeBaseIds").isArray())
            throw ApiFailure.invalid("/knowledgeBaseIds", "请求须仅含知识库范围。");
        JsonNode ids = input.path("knowledgeBaseIds");
        if (ids.size() > 100) throw ApiFailure.invalid("/knowledgeBaseIds", "最多保存一百个知识库。");
        var unique = new java.util.HashSet<String>();
        for (JsonNode id : ids)
            if (!id.isTextual() || !unique.add(ModelSettingsService.identifier(id.asText(), "/knowledgeBaseIds")))
                throw ApiFailure.invalid("/knowledgeBaseIds", "知识库标识须合法且不能重复。");
        return searches.updateScope(instanceId, ids);
    }

    @DeleteMapping(value = "/api/v1/search-instances/{instanceId}", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode delete(@PathVariable String instanceId, @RequestBody JsonNode input, HttpServletRequest request) {
        noQuery(request);
        if (!input.isObject()
                || input.size() != 1
                || !input.path("confirmed").isBoolean()
                || !input.path("confirmed").asBoolean()) throw ApiFailure.invalid("/confirmed", "须明确确认删除检索实例。");
        List<String> keys = java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw ApiFailure.invalid("Idempotency-Key", "须提供唯一幂等键。");
        var session = (LocalSessionFilter.Session) request.getAttribute("localSession");
        return searches.deleteInstance(instanceId, session.sessionId(), keys.getFirst());
    }

    @PostMapping("/api/v1/search-requests/{requestId}/cancel")
    public JsonNode cancel(
            @PathVariable String requestId, @RequestBody(required = false) String body, HttpServletRequest request) {
        noQuery(request);
        if (body != null && !body.isEmpty()) throw ApiFailure.invalid(null, "取消检索不接受正文。");
        return searches.cancelRequest(requestId);
    }

    @PostMapping(value = "/api/v1/search-instances/{instanceId}/requests", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode ordinary(@PathVariable String instanceId, @RequestBody JsonNode input, HttpServletRequest request) {
        noQuery(request);
        if (!input.isObject() || input.size() != 2 || !input.has("query") || !input.has("knowledgeBaseIds"))
            throw ApiFailure.invalid(null, "请求只允许查询和知识库范围。");
        JsonNode query = input.path("query"), ids = input.path("knowledgeBaseIds");
        if (!query.isTextual()
                || query.asText().isBlank()
                || query.asText().codePointCount(0, query.asText().length()) > 20000)
            throw ApiFailure.invalid("/query", "查询须为一至两万字符的非空文本。");
        if (!ids.isArray() || ids.isEmpty() || ids.size() > 100)
            throw ApiFailure.invalid("/knowledgeBaseIds", "须选择一至一百个知识库。");
        var unique = new java.util.HashSet<String>();
        for (JsonNode id : ids) {
            if (!id.isTextual() || !unique.add(ModelSettingsService.identifier(id.asText(), "/knowledgeBaseIds")))
                throw ApiFailure.invalid("/knowledgeBaseIds", "知识库标识须合法且不能重复。");
        }
        List<String> keys = java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw ApiFailure.invalid("Idempotency-Key", "须提供唯一幂等键。");
        var session = (LocalSessionFilter.Session) request.getAttribute("localSession");
        return searches.submitInstance(session.sessionId(), instanceId, keys.getFirst(), input);
    }

    @PostMapping(value = "/api/v1/knowledge-bases/{knowledgeBaseId}/search-test", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode submit(
            @PathVariable String knowledgeBaseId, @RequestBody JsonNode input, HttpServletRequest request) {
        noQuery(request);
        String kb = ModelSettingsService.identifier(knowledgeBaseId, "knowledgeBaseId");
        if (!input.isObject() || input.size() != 2 || !input.has("query") || !input.has("knowledgeBaseIds"))
            throw ApiFailure.invalid(null, "请求只允许查询和知识库范围。");
        JsonNode query = input.path("query");
        if (!query.isTextual()
                || query.asText().isBlank()
                || query.asText().codePointCount(0, query.asText().length()) > 20000)
            throw ApiFailure.invalid("/query", "查询须为一至两万字符的非空文本。");
        JsonNode ids = input.path("knowledgeBaseIds");
        if (!ids.isArray()
                || ids.size() != 1
                || !ids.get(0).isTextual()
                || !ModelSettingsService.identifier(ids.get(0).asText(), "/knowledgeBaseIds")
                        .equals(kb)) throw ApiFailure.invalid("/knowledgeBaseIds", "管理测试范围须恰好是当前单库。");
        List<String> keys = java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw ApiFailure.invalid("Idempotency-Key", "须提供唯一幂等键。");
        LocalSessionFilter.Session session = (LocalSessionFilter.Session) request.getAttribute("localSession");
        return searches.submit(session.sessionId(), kb, keys.getFirst(), input);
    }

    @GetMapping("/api/v1/search-requests/{requestId}")
    public JsonNode get(@PathVariable String requestId, HttpServletRequest request) {
        noQuery(request);
        return searches.get(requestId);
    }

    @GetMapping("/api/v1/search-requests/{requestId}/evidence/{evidenceId}")
    public JsonNode evidence(
            @PathVariable String requestId, @PathVariable String evidenceId, HttpServletRequest request) {
        noQuery(request);
        return searches.evidence(requestId, evidenceId);
    }

    private static void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw ApiFailure.invalid(null, "此操作不接受查询参数。");
    }
}
