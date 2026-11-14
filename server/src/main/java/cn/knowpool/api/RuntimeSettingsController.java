package cn.knowpool.api;

import cn.knowpool.infrastructure.*;
import cn.knowpool.knowledgebases.KnowledgeBases;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public final class RuntimeSettingsController {
    private final RuntimeSettings settings;
    private final KnowledgeBases bases;
    public RuntimeSettingsController(RuntimeSettings settings,KnowledgeBases bases) { this.settings=settings;this.bases=bases; }
    @GetMapping("/api/v1/settings/runtime")
    public JsonNode get(HttpServletRequest request) { noQuery(request);return settings.get(); }
    @PutMapping("/api/v1/settings/runtime")
    public JsonNode update(@RequestBody JsonNode body,HttpServletRequest request) { noQuery(request);return settings.update(body,() -> bases.runtimeInputChanged(body.path("chunkHardLimitCodePoints").asLong())); }
    private static void noQuery(HttpServletRequest request) { if (!request.getParameterMap().isEmpty()) throw ApiFailure.invalid(null,"此操作不接受查询参数。"); }
}
