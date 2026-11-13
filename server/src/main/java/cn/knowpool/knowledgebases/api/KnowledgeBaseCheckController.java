package cn.knowpool.knowledgebases.api;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.knowledgebases.KnowledgeBaseChecks;
import cn.knowpool.system.ConnectionTests;
import cn.knowpool.system.ModelSettingsService;
import cn.knowpool.system.api.LocalSessionFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public final class KnowledgeBaseCheckController {
    private final KnowledgeBaseChecks checks;
    public KnowledgeBaseCheckController(KnowledgeBaseChecks checks) { this.checks=checks; }
    @PostMapping(value="/api/v1/knowledge-bases/{knowledgeBaseId}/check",consumes="application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ConnectionTests.Accepted check(@PathVariable String knowledgeBaseId,@RequestBody JsonNode input,HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw ApiFailure.invalid(null,"复查不接受查询参数。");
        if (!input.isObject() || input.size()!=1 || !input.path("allowChargedCall").isBoolean() || !input.path("allowChargedCall").asBoolean())
            throw ApiFailure.invalid("/allowChargedCall","须明确同意复查可能产生费用。");
        List<String> keys=java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size()!=1) throw ApiFailure.invalid("Idempotency-Key","须提供唯一幂等键。");
        var session=(LocalSessionFilter.Session)request.getAttribute("localSession");
        return checks.submit(ModelSettingsService.identifier(knowledgeBaseId,"knowledgeBaseId"),session.sessionId(),keys.getFirst());
    }
}
