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
